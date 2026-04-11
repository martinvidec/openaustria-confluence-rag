package at.openaustria.confluencerag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "query")
public record QueryProperties(
    int topK,
    double similarityThreshold,
    RerankerProperties reranker
) {
    public record RerankerProperties(
        String type,                              // llm | infinity | none
        LlmRerankerProperties llm,
        InfinityRerankerProperties infinity
    ) {}

    public record LlmRerankerProperties(
        String baseUrl,
        String model,
        int candidateCount,
        int timeoutSeconds,
        int maxChunkChars
    ) {}

    public record InfinityRerankerProperties(
        String baseUrl,
        String model,
        int candidateCount,
        int timeoutSeconds
    ) {}
}
