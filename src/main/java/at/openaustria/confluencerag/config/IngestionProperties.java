package at.openaustria.confluencerag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ingestion")
public record IngestionProperties(
    int chunkSize,
    int chunkOverlap,
    int batchSize
) {}
