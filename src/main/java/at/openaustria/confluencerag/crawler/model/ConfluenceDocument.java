package at.openaustria.confluencerag.crawler.model;

import java.time.Instant;
import java.util.List;

public record ConfluenceDocument(
    long pageId,
    String spaceKey,
    String spaceName,
    String title,
    String url,
    String bodyText,
    List<String> labels,
    List<CommentDocument> comments,
    List<AttachmentDocument> attachments,
    String author,
    Instant lastModified,
    List<String> ancestorTitles
) {}
