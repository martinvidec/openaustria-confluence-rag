package at.openaustria.confluencerag.crawler.model;

import java.time.Instant;

public record CommentDocument(
    long commentId,
    String bodyText,
    String author,
    Instant createdAt
) {}
