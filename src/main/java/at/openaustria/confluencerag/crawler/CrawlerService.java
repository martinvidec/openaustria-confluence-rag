package at.openaustria.confluencerag.crawler;

import at.openaustria.confluencerag.config.ConfluenceProperties;
import at.openaustria.confluencerag.crawler.client.ConfluenceClient;
import at.openaustria.confluencerag.crawler.converter.ConfluenceHtmlConverter;
import at.openaustria.confluencerag.crawler.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class CrawlerService {

    private static final Logger log = LoggerFactory.getLogger(CrawlerService.class);
    private static final long MAX_ATTACHMENT_SIZE_BYTES = 50 * 1024 * 1024; // 50 MB
    private static final Set<String> SUPPORTED_MEDIA_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain"
    );

    private final ConfluenceClient client;
    private final ConfluenceHtmlConverter converter;
    private final AttachmentTextExtractor attachmentExtractor;
    private final ConfluenceProperties properties;

    public CrawlerService(ConfluenceClient client,
                          ConfluenceHtmlConverter converter,
                          AttachmentTextExtractor attachmentExtractor,
                          ConfluenceProperties properties) {
        this.client = client;
        this.converter = converter;
        this.attachmentExtractor = attachmentExtractor;
        this.properties = properties;
    }

    public List<ConfluenceDocument> crawlAll() {
        List<String> spaces = properties.spaces();
        if (spaces == null || spaces.isEmpty()) {
            log.warn("Keine Spaces konfiguriert. Crawl wird übersprungen.");
            return List.of();
        }

        log.info("Crawl gestartet für Spaces: {}", spaces);
        List<ConfluenceDocument> allDocs = new ArrayList<>();
        for (String spaceKey : spaces) {
            allDocs.addAll(crawlSpace(spaceKey));
        }
        log.info("Crawl abgeschlossen. {} Seiten total in {} Spaces.", allDocs.size(), spaces.size());
        return allDocs;
    }

    public List<ConfluenceDocument> crawlSpace(String spaceKey) {
        List<ConfluencePageResponse> pages = client.getPages(spaceKey);
        log.info("Space {}: {} Seiten gefunden", spaceKey, pages.size());
        return processPages(spaceKey, pages);
    }

    public List<ConfluenceDocument> crawlChangesSince(String spaceKey, Instant since) {
        List<ConfluencePageResponse> pages = client.getPagesSince(spaceKey, since);
        log.info("Space {}: {} geänderte Seiten seit {}", spaceKey, pages.size(), since);
        return processPages(spaceKey, pages);
    }

    private List<ConfluenceDocument> processPages(String spaceKey, List<ConfluencePageResponse> pages) {
        List<ConfluenceDocument> documents = new ArrayList<>();
        int totalComments = 0;
        int totalAttachments = 0;

        for (int i = 0; i < pages.size(); i++) {
            ConfluencePageResponse page = pages.get(i);
            try {
                ConfluenceDocument doc = processPage(page);
                documents.add(doc);
                totalComments += doc.comments().size();
                totalAttachments += doc.attachments().size();
                log.info("Space {}: Seite {}/{} - \"{}\" ({} Kommentare, {} Attachments)",
                        spaceKey, i + 1, pages.size(), page.title(),
                        doc.comments().size(), doc.attachments().size());
            } catch (Exception e) {
                log.error("Space {}: Seite {}/{} - \"{}\" fehlgeschlagen: {}",
                        spaceKey, i + 1, pages.size(), page.title(), e.getMessage());
            }
        }

        log.info("Space {}: Crawl abgeschlossen. {} Seiten, {} Kommentare, {} Attachments.",
                spaceKey, documents.size(), totalComments, totalAttachments);
        return documents;
    }

    private ConfluenceDocument processPage(ConfluencePageResponse page) {
        // Convert body
        String bodyText = "";
        if (page.body() != null && page.body().storage() != null) {
            bodyText = converter.convert(page.body().storage().value());
        }

        // Extract labels
        List<String> labels = List.of();
        if (page.metadata() != null && page.metadata().labels() != null
                && page.metadata().labels().results() != null) {
            labels = page.metadata().labels().results().stream()
                    .map(ConfluenceLabel::name)
                    .toList();
        }

        // Extract ancestors
        List<String> ancestorTitles = List.of();
        if (page.ancestors() != null) {
            ancestorTitles = page.ancestors().stream()
                    .map(ConfluenceAncestor::title)
                    .toList();
        }

        // Fetch comments
        List<CommentDocument> comments = fetchComments(page.id());

        // Fetch attachments
        List<AttachmentDocument> attachments = fetchAttachments(page.id());

        // Build URL
        String url = "";
        if (page.links() != null && page.links().webui() != null) {
            url = properties.baseUrl() + page.links().webui();
        }

        // Extract author and lastModified
        String author = "";
        Instant lastModified = null;
        if (page.version() != null) {
            author = page.version().by() != null ? page.version().by() : "";
            lastModified = page.version().when();
        }

        // Space info
        String spaceName = "";
        String spaceKey = "";
        if (page.space() != null) {
            spaceKey = page.space().key();
            spaceName = page.space().name() != null ? page.space().name() : "";
        }

        return new ConfluenceDocument(
                page.id(), spaceKey, spaceName, page.title(), url,
                bodyText, labels, comments, attachments,
                author, lastModified, ancestorTitles
        );
    }

    private List<CommentDocument> fetchComments(long pageId) {
        try {
            List<ConfluenceCommentResponse> responses = client.getComments(pageId);
            return responses.stream()
                    .map(comment -> {
                        String text = "";
                        if (comment.body() != null && comment.body().storage() != null) {
                            text = converter.convert(comment.body().storage().value());
                        }
                        String author = "";
                        Instant when = null;
                        if (comment.version() != null) {
                            author = comment.version().by() != null ? comment.version().by() : "";
                            when = comment.version().when();
                        }
                        return new CommentDocument(comment.id(), text, author, when);
                    })
                    .filter(c -> !c.bodyText().isBlank())
                    .toList();
        } catch (Exception e) {
            log.error("Fehler bei Kommentaren für Seite {}: {}", pageId, e.getMessage());
            return List.of();
        }
    }

    private List<AttachmentDocument> fetchAttachments(long pageId) {
        try {
            List<ConfluenceAttachmentResponse> responses = client.getAttachments(pageId);
            List<AttachmentDocument> result = new ArrayList<>();

            for (ConfluenceAttachmentResponse att : responses) {
                String rawType = att.mediaType();
                if (rawType == null && att.extensions() != null) {
                    rawType = att.extensions().mediaType();
                }
                final String mediaType = rawType;
                if (mediaType == null || !isSupportedMediaType(mediaType)) {
                    log.debug("Attachment '{}' übersprungen (Typ: {})", att.title(), mediaType);
                    continue;
                }

                // Check file size
                if (att.extensions() != null && att.extensions().fileSize() != null
                        && att.extensions().fileSize() > MAX_ATTACHMENT_SIZE_BYTES) {
                    log.warn("Attachment '{}' übersprungen ({} MB > 50 MB Limit)",
                            att.title(), att.extensions().fileSize() / (1024 * 1024));
                    continue;
                }

                // Download and extract
                if (att.links() == null || att.links().download() == null) {
                    continue;
                }

                try {
                    byte[] content = client.downloadAttachment(att.links().download());
                    attachmentExtractor.extractText(content, att.title())
                            .ifPresent(text -> result.add(new AttachmentDocument(
                                    att.id(), att.title(), mediaType, text)));
                } catch (Exception e) {
                    log.warn("Attachment '{}' konnte nicht verarbeitet werden: {}",
                            att.title(), e.getMessage());
                }
            }

            return result;
        } catch (Exception e) {
            log.error("Fehler bei Attachments für Seite {}: {}", pageId, e.getMessage());
            return List.of();
        }
    }

    private boolean isSupportedMediaType(String mediaType) {
        return SUPPORTED_MEDIA_TYPES.stream()
                .anyMatch(supported -> mediaType.startsWith(supported));
    }
}
