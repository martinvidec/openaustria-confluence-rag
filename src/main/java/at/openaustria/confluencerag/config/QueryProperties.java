package at.openaustria.confluencerag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "query")
public record QueryProperties(
    int topK,
    double similarityThreshold,
    RerankerProperties reranker
) {
    public record RerankerProperties(
        boolean enabled,
        String baseUrl,
        String model,
        int candidateCount,
        int timeoutSeconds
    ) {}
}
