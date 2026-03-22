package at.openaustria.confluencerag.web;

public record ErrorResponse(
    String code,
    String message
) {}
