package at.openaustria.confluencerag.query;

import at.openaustria.confluencerag.config.QueryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    private static final String SYSTEM_PROMPT = """
            Du bist ein hilfreicher Assistent der Fragen basierend auf der internen
            Confluence-Dokumentation beantwortet.

            Regeln:
            - Beantworte die Frage NUR basierend auf dem bereitgestellten Kontext.
            - Wenn der Kontext die Frage nicht beantwortet, sage das ehrlich.
            - Nenne KEINE Quellen in deiner Antwort. Die Quellen werden automatisch angezeigt.
            - Antworte in der Sprache der Frage.
            - Fasse den relevanten Kontext zusammen, zitiere nicht wörtlich.

            Kontext-Interpretation:
            - Jeder Kontext-Block beginnt mit Metadaten (Titel, Pfad, Labels, Typ).
            - Verwende den TITEL als primäres Zuordnungskriterium: Wenn nach einem \
            bestimmten Dokument gefragt wird (z.B. "Spec 07"), beziehe dich nur auf \
            Kontext-Blöcke, deren Titel dazu passt.
            - Unterscheide klar zwischen verschiedenen Dokumenttypen: Specs, Issues, \
            Protokolle, etc. sind verschiedene Dokumente, auch wenn sie dieselbe \
            Nummer tragen.
            - Typ "Seite" ist der Hauptinhalt, "Kommentar" sind Diskussionen zur Seite, \
            "Anhang" sind angehängte Dateien.

            Kontext:
            %s
            """;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final QueryProperties queryProperties;

    public QueryService(VectorStore vectorStore, ChatClient.Builder chatClientBuilder,
                        QueryProperties queryProperties) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
        this.queryProperties = queryProperties;
    }

    public QueryResponse query(QueryRequest request) {
        Instant start = Instant.now();
        log.info("Query: \"{}\" (Filter: {})", request.question(), request.spaceFilter());

        List<Document> relevantDocs = searchRelevantDocs(request);
        log.info("Similarity Search: {} Ergebnisse", relevantDocs.size());

        List<Source> sources = extractSources(relevantDocs);
        String context = buildContext(relevantDocs);

        String answer = chatClient.prompt()
                .system(SYSTEM_PROMPT.formatted(context))
                .user(request.question())
                .call()
                .content();

        long durationMs = Instant.now().toEpochMilli() - start.toEpochMilli();
        log.info("LLM-Antwort generiert in {}ms", durationMs);

        return new QueryResponse(answer, sources, durationMs);
    }

    public Flux<String> queryStream(QueryRequest request) {
        List<Document> relevantDocs = searchRelevantDocs(request);
        String context = buildContext(relevantDocs);

        return chatClient.prompt()
                .system(SYSTEM_PROMPT.formatted(context))
                .user(request.question())
                .stream()
                .content();
    }

    public List<Source> getSourcesForQuery(QueryRequest request) {
        List<Document> relevantDocs = searchRelevantDocs(request);
        return extractSources(relevantDocs);
    }

    private List<Document> searchRelevantDocs(QueryRequest request) {
        // Fetch a large candidate set for keyword re-ranking, then return top-K
        // In single-domain corpora, vector scores are compressed into a narrow band,
        // so we need many candidates to ensure the right documents reach the re-ranker.
        int fetchK = Math.max(queryProperties.topK() * 10, 100);
        SearchRequest.Builder searchBuilder = SearchRequest.builder()
                .query(request.question())
                .topK(fetchK)
                .similarityThreshold(queryProperties.similarityThreshold());

        if (request.spaceFilter() != null && !request.spaceFilter().isEmpty()) {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            if (request.spaceFilter().size() == 1) {
                searchBuilder.filterExpression(
                        b.eq("spaceKey", request.spaceFilter().get(0)).build());
            } else {
                searchBuilder.filterExpression(
                        b.in("spaceKey", request.spaceFilter().toArray()).build());
            }
        }

        List<Document> candidates = vectorStore.similaritySearch(searchBuilder.build());
        return rerankByKeywords(candidates, request.question(), queryProperties.topK());
    }

    /**
     * Re-ranks vector search results by boosting chunks whose title or text
     * contains keywords from the query. This compensates for the narrow similarity
     * band in single-domain corpora where vector scores alone can't distinguish
     * the correct document.
     */
    List<Document> rerankByKeywords(List<Document> candidates, String question, int topK) {
        if (candidates.isEmpty()) {
            return candidates;
        }

        Set<String> queryWords = extractKeywords(question);

        List<Map.Entry<Document, Double>> scored = new ArrayList<>();
        for (Document doc : candidates) {
            String title = ((String) doc.getMetadata().getOrDefault("pageTitle", "")).toLowerCase();
            String text = doc.getText().toLowerCase();

            long titleHits = queryWords.stream().filter(title::contains).count();
            long textHits = queryWords.stream().filter(text::contains).count();

            // Title matches are weighted 3x more than text matches
            double keywordScore = (titleHits * 3.0 + textHits) / (queryWords.size() * 4.0);
            scored.add(Map.entry(doc, keywordScore));
        }

        // Sort by keyword score descending (stable sort preserves vector-score order for ties)
        scored.sort(Map.Entry.<Document, Double>comparingByValue().reversed());

        return scored.stream()
                .limit(topK)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "der", "die", "das", "ein", "eine", "und", "oder", "im", "von",
            "für", "fuer", "mit", "auf", "an", "zu", "ist", "sind", "was", "wie",
            "wer", "wo", "den", "dem", "des", "einen", "einer", "einem",
            "the", "is", "are", "of", "for", "in", "on", "to", "and", "or",
            "what", "how", "which", "this", "that", "from", "with"
    );

    private Set<String> extractKeywords(String question) {
        return Arrays.stream(question.toLowerCase().replaceAll("[^a-zäöüß0-9\\s]", "").split("\\s+"))
                .filter(w -> w.length() > 1)
                .filter(w -> !STOP_WORDS.contains(w))
                .collect(Collectors.toSet());
    }

    private String buildContext(List<Document> relevantDocs) {
        return relevantDocs.stream()
                .map(doc -> {
                    String title = (String) doc.getMetadata().getOrDefault("pageTitle", "");
                    String space = (String) doc.getMetadata().getOrDefault("spaceKey", "");
                    String ancestors = (String) doc.getMetadata().getOrDefault("ancestors", "");
                    String chunkType = (String) doc.getMetadata().getOrDefault("chunkType", "PAGE");
                    String typLabel = switch (chunkType) {
                        case "COMMENT" -> "Kommentar";
                        case "ATTACHMENT" -> "Anhang";
                        default -> "Seite";
                    };

                    StringBuilder header = new StringBuilder();
                    header.append("--- Quelle: ").append(title);
                    header.append(" (Space: ").append(space);
                    if (!ancestors.isEmpty()) {
                        header.append(", Pfad: ").append(ancestors);
                    }
                    header.append(", Typ: ").append(typLabel);
                    header.append(") ---\n");
                    header.append(doc.getText());
                    return header.toString();
                })
                .collect(Collectors.joining("\n\n"));
    }

    private List<Source> extractSources(List<Document> relevantDocs) {
        Map<String, Source> uniqueSources = new LinkedHashMap<>();
        for (Document doc : relevantDocs) {
            String url = (String) doc.getMetadata().getOrDefault("pageUrl", "");
            if (!uniqueSources.containsKey(url)) {
                uniqueSources.put(url, new Source(
                        (String) doc.getMetadata().getOrDefault("pageTitle", ""),
                        url,
                        (String) doc.getMetadata().getOrDefault("spaceKey", "")
                ));
            }
        }
        return new ArrayList<>(uniqueSources.values());
    }
}
