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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Listwise reranker that uses a small Ollama LLM to score the top-N
 * vector-search candidates in a single chat call.
 *
 * Approach: build a numbered list of all candidates with title + truncated
 * content, ask the LLM to return the indices of the top-K relevant ones
 * as a JSON array, parse the array robustly and reorder the documents.
 *
 * Falls back to the original vector-order on any error (HTTP failure,
 * timeout, JSON parse problem) so the query path stays safe.
 */
@Service
@ConditionalOnProperty(prefix = "query.reranker", name = "type",
        havingValue = "llm", matchIfMissing = true)
public class LlmListwiseReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(LlmListwiseReranker.class);

    /** Match the first JSON array literal in any string, even with extra text around it. */
    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[\\s*\\d+(?:\\s*,\\s*\\d+)*\\s*]");

    private final QueryProperties.LlmRerankerProperties config;
    private final RestClient restClient;

    @Autowired
    public LlmListwiseReranker(QueryProperties queryProperties) {
        this.config = queryProperties.reranker().llm();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(config.timeoutSeconds()));

        this.restClient = RestClient.builder()
                .baseUrl(config.baseUrl())
                .requestFactory(requestFactory)
                .build();

        log.info("LlmListwiseReranker konfiguriert: baseUrl={}, model={}, candidateCount={}, maxChunkChars={}",
                config.baseUrl(), config.model(), config.candidateCount(), config.maxChunkChars());
    }

    /**
     * Package-private constructor for tests — allows injecting a RestClient
     * pointed at a test HTTP server.
     */
    LlmListwiseReranker(QueryProperties queryProperties, RestClient restClient) {
        this.config = queryProperties.reranker().llm();
        this.restClient = restClient;
    }

    @Override
    public List<Document> rerank(String query, List<Document> candidates, int topK) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (candidates.size() <= topK) {
            return candidates;
        }

        try {
            String prompt = buildPrompt(query, candidates, topK);
            String responseText = callOllama(prompt);

            List<Integer> oneBasedIndices = parseJsonArray(responseText);
            return mapToDocuments(oneBasedIndices, candidates, topK);
        } catch (Exception e) {
            log.warn("LLM-Rerank fehlgeschlagen, fallback auf Vektor-Reihenfolge: {}",
                    e.getMessage());
            return candidates.stream().limit(topK).toList();
        }
    }

    @Override
    public int candidateCount() {
        return config.candidateCount();
    }

    private String buildPrompt(String query, List<Document> candidates, int topK) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("Du bist ein Such-Assistent. Bewerte die Relevanz folgender Dokument-Auszüge ")
          .append("für die Frage.\n\n");
        sb.append("Frage: \"").append(query).append("\"\n\n");
        sb.append("Dokumente:\n");

        int n = Math.min(candidates.size(), config.candidateCount());
        for (int i = 0; i < n; i++) {
            Document doc = candidates.get(i);
            String title = (String) doc.getMetadata().getOrDefault("pageTitle", "");
            String text = doc.getText() == null ? "" : doc.getText();
            String truncated = text.length() > config.maxChunkChars()
                    ? text.substring(0, config.maxChunkChars()) + "..."
                    : text;
            sb.append("[").append(i + 1).append("] Titel: ").append(title).append("\n");
            sb.append(truncated).append("\n\n");
        }

        sb.append("Gib die Nummern der ").append(topK)
          .append(" relevantesten Dokumente in absteigender Relevanz als JSON-Array zurück. ")
          .append("Beispiel: [3, 7, 1, 12, 5]\n\n")
          .append("Antworte AUSSCHLIESSLICH mit dem Array. Keine Erklärung, kein Markdown, ")
          .append("keine Anführungszeichen drumherum. Nur das Array.");
        return sb.toString();
    }

    private String callOllama(String prompt) {
        OllamaRequest req = new OllamaRequest(
                config.model(),
                prompt,
                false,
                new OllamaOptions(0.0, 0.1, 64));

        OllamaResponse resp = restClient.post()
                .uri("/api/generate")
                .body(req)
                .retrieve()
                .body(OllamaResponse.class);

        if (resp == null || resp.response() == null) {
            throw new IllegalStateException("Empty Ollama response");
        }
        return resp.response();
    }

    /**
     * Extract a list of integers from the LLM response. Handles markdown wraps,
     * leading/trailing text, and missing brackets by using a regex to find the
     * first JSON-array-shaped substring.
     */
    static List<Integer> parseJsonArray(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Matcher m = JSON_ARRAY_PATTERN.matcher(text);
        if (!m.find()) {
            return List.of();
        }
        String arrayLiteral = m.group();
        // Strip brackets, split, parse
        String inner = arrayLiteral.substring(1, arrayLiteral.length() - 1);
        List<Integer> result = new ArrayList<>();
        for (String token : inner.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            try {
                result.add(Integer.parseInt(trimmed));
            } catch (NumberFormatException ignored) {
                // skip non-numeric tokens
            }
        }
        return result;
    }

    /**
     * Map LLM-returned 1-based indices to documents, validating range and
     * removing duplicates. Pads with original-order top documents if the
     * LLM returned fewer than topK valid indices.
     */
    private List<Document> mapToDocuments(List<Integer> oneBasedIndices,
                                          List<Document> candidates,
                                          int topK) {
        Set<Integer> usedZeroBased = new LinkedHashSet<>();
        for (Integer idx : oneBasedIndices) {
            if (idx == null) continue;
            int zeroBased = idx - 1;
            if (zeroBased >= 0 && zeroBased < candidates.size()) {
                usedZeroBased.add(zeroBased);
            }
            if (usedZeroBased.size() >= topK) break;
        }

        // Pad with original-order top documents not yet used
        if (usedZeroBased.size() < topK) {
            for (int i = 0; i < candidates.size() && usedZeroBased.size() < topK; i++) {
                usedZeroBased.add(i);
            }
        }

        List<Document> result = new ArrayList<>(usedZeroBased.size());
        for (Integer idx : usedZeroBased) {
            result.add(candidates.get(idx));
        }
        return result;
    }

    record OllamaRequest(String model, String prompt, boolean stream, OllamaOptions options) {}
    record OllamaOptions(double temperature, double top_p, int num_predict) {}
    record OllamaResponse(String model, String response) {}
}
