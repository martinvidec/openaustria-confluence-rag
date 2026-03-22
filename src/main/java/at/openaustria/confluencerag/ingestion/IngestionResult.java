package at.openaustria.confluencerag.ingestion;

import java.time.Duration;

public record IngestionResult(
    int spacesProcessed,
    int pagesProcessed,
    int chunksCreated,
    int chunksStored,
    int errors,
    Duration duration
) {}
