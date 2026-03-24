package at.openaustria.confluencerag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "query")
public record QueryProperties(
    int topK,
    double similarityThreshold
) {}
