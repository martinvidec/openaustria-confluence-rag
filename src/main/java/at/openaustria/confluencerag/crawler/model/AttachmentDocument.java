package at.openaustria.confluencerag.crawler.model;

public record AttachmentDocument(
    long attachmentId,
    String fileName,
    String mediaType,
    String extractedText
) {}
