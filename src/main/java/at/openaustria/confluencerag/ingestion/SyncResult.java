package at.openaustria.confluencerag.ingestion;

import java.time.Duration;

public record SyncResult(
    int pagesUpdated,
    int pagesDeleted,
    int pagesNew,
    int chunksCreated,
    int errors,
    Duration duration
) {}
