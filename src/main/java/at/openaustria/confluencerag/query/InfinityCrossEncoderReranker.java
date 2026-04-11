package at.openaustria.confluencerag.query;

import at.openaustria.confluencerag.config.QueryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * Cross-encoder reranker that calls an external infinity container
 * (BAAI/bge-reranker-v2-m3) to re-score vector search candidates.
 *
 * Activated via {@code query.reranker.type=infinity}. Requires the
 * infinity container (~4.5 GB) to be running and reachable. Falls back
 * to the original candidate order on any HTTP error so failures never
 * break the query path.
 */
@Service
@ConditionalOnProperty(prefix = "query.reranker", name = "type", havingValue = "infinity")
public class InfinityCrossEncoderReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(InfinityCrossEncoderReranker.class);

    private final QueryProperties.InfinityRerankerProperties config;
    private final RestClient restClient;

    @Autowired
    public InfinityCrossEncoderReranker(QueryProperties queryProperties) {
        this.config = queryProperties.reranker().infinity();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(config.timeoutSeconds()));

        this.restClient = RestClient.builder()
                .baseUrl(config.baseUrl())
                .requestFactory(requestFactory)
                .build();

        log.info("InfinityCrossEncoderReranker konfiguriert: baseUrl={}, model={}, candidateCount={}",
                config.baseUrl(), config.model(), config.candidateCount());
    }

    /**
     * Package-private constructor for tests — allows injecting a RestClient
     * pointed at a test HTTP server.
     */
    InfinityCrossEncoderReranker(QueryProperties queryProperties, RestClient restClient) {
        this.config = queryProperties.reranker().infinity();
        this.restClient = restClient;
    }

    @Override
    public List<Document> rerank(String query, List<Document> candidates, int topK) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        try {
            List<String> texts = candidates.stream()
                    .map(this::buildRerankText)
                    .toList();

            RerankRequest req = new RerankRequest(config.model(), query, texts, topK, false);

            RerankResponse resp = restClient.post()
                    .uri("/rerank")
                    .body(req)
                    .retrieve()
                    .body(RerankResponse.class);

            if (resp == null || resp.results() == null || resp.results().isEmpty()) {
                log.warn("Reranker lieferte leeres Ergebnis, fallback auf Vektor-Reihenfolge");
                return candidates.stream().limit(topK).toList();
            }

            return resp.results().stream()
                    .filter(r -> r.index() >= 0 && r.index() < candidates.size())
                    .map(r -> candidates.get(r.index()))
                    .limit(topK)
                    .toList();
        } catch (Exception e) {
            log.warn("Reranker-Call fehlgeschlagen, fallback auf Vektor-Reihenfolge: {}", e.getMessage());
            return candidates.stream().limit(topK).toList();
        }
    }

    @Override
    public int candidateCount() {
        return config.candidateCount();
    }

    private String buildRerankText(Document doc) {
        String title = (String) doc.getMetadata().getOrDefault("pageTitle", "");
        String text = doc.getText();
        return title.isEmpty() ? text : title + "\n" + text;
    }

    record RerankRequest(String model, String query, List<String> documents, Integer top_n, Boolean return_documents) {}
    record RerankResponse(List<RerankResult> results) {}
    record RerankResult(int index, double relevance_score) {}
}
