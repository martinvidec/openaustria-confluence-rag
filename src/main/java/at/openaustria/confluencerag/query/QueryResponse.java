package at.openaustria.confluencerag.query;

import java.util.List;

public record QueryResponse(
    String answer,
    List<Source> sources,
    long durationMs
) {}
