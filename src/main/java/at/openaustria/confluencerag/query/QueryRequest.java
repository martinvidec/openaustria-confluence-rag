package at.openaustria.confluencerag.query;

import java.util.List;

public record QueryRequest(
    String question,
    List<String> spaceFilter
) {}
