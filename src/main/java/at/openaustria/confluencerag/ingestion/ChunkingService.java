package at.openaustria.confluencerag.ingestion;

import at.openaustria.confluencerag.config.IngestionProperties;
import at.openaustria.confluencerag.crawler.model.AttachmentDocument;
import at.openaustria.confluencerag.crawler.model.CommentDocument;
import at.openaustria.confluencerag.crawler.model.ConfluenceDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChunkingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);
    private static final int MAX_CHUNK_CHARS = 2000;

    private final TokenTextSplitter textSplitter;
    private final int chunkOverlap;

    public ChunkingService(IngestionProperties properties) {
        int chunkSize = properties.chunkSize();
        this.chunkOverlap = properties.chunkOverlap();
        log.info("ChunkingService initialisiert: chunkSize={}, chunkOverlap={}, maxChunkChars={}",
                chunkSize, chunkOverlap, MAX_CHUNK_CHARS);
        this.textSplitter = new TokenTextSplitter(
                chunkSize,                    // defaultChunkSize (tokens)
                50,                           // minChunkSizeChars
                50,                           // minChunkLengthToEmbed
                500,                          // maxNumChunks
                true                          // keepSeparator
        );
    }

    public List<Document> chunkDocument(ConfluenceDocument doc) {
        List<Document> chunks = new ArrayList<>();
        Map<String, Object> baseMetadata = buildBaseMetadata(doc);

        // 1. Body text chunks
        if (doc.bodyText() != null && !doc.bodyText().isBlank()) {
            List<Document> bodyDocs = splitText(doc.bodyText(), baseMetadata, "PAGE");
            chunks.addAll(bodyDocs);
        } else {
            log.warn("Seite '{}' (ID: {}) hat keinen Body-Text", doc.title(), doc.pageId());
        }

        // 2. Comment chunks
        if (doc.comments() != null) {
            for (CommentDocument comment : doc.comments()) {
                if (comment.bodyText() == null || comment.bodyText().isBlank()) {
                    continue;
                }
                List<Document> commentDocs = splitText(comment.bodyText(), baseMetadata, "COMMENT");
                chunks.addAll(commentDocs);
            }
        }

        // 3. Attachment chunks
        if (doc.attachments() != null) {
            for (AttachmentDocument att : doc.attachments()) {
                if (att.extractedText() == null || att.extractedText().isBlank()) {
                    continue;
                }
                Map<String, Object> attMetadata = new HashMap<>(baseMetadata);
                attMetadata.put("attachmentName", att.fileName());
                List<Document> attDocs = splitText(att.extractedText(), attMetadata, "ATTACHMENT");
                chunks.addAll(attDocs);
            }
        }

        return chunks;
    }

    private List<Document> splitText(String text, Map<String, Object> baseMetadata, String chunkType) {
        Document sourceDoc = new Document(text);
        List<Document> split = textSplitter.apply(List.of(sourceDoc));

        // Apply overlap between consecutive chunks
        List<String> chunkTexts = split.stream().map(Document::getText).toList();
        List<String> overlappedTexts = applyOverlap(chunkTexts);

        // Build header with title, path, labels, type
        String header = buildChunkHeader(baseMetadata, chunkType);

        List<Document> result = new ArrayList<>();
        for (String rawText : overlappedTexts) {
            String chunkText = header + rawText;
            // Hard limit: truncate chunks that are still too large
            if (chunkText.length() > MAX_CHUNK_CHARS) {
                log.warn("Chunk gekürzt: {} → {} Zeichen (Seite: {})",
                        chunkText.length(), MAX_CHUNK_CHARS,
                        baseMetadata.get("pageTitle"));
                chunkText = chunkText.substring(0, MAX_CHUNK_CHARS);
            }
            Map<String, Object> metadata = new HashMap<>(baseMetadata);
            metadata.put("chunkType", chunkType);
            result.add(new Document(chunkText, metadata));
        }
        return result;
    }

    private String buildChunkHeader(Map<String, Object> metadata, String chunkType) {
        StringBuilder header = new StringBuilder();
        header.append("Titel: ").append(metadata.getOrDefault("pageTitle", "")).append("\n");

        String ancestors = (String) metadata.getOrDefault("ancestors", "");
        if (!ancestors.isEmpty()) {
            header.append("Pfad: ").append(ancestors).append("\n");
        }

        String labels = (String) metadata.getOrDefault("labels", "");
        if (!labels.isEmpty()) {
            header.append("Labels: ").append(labels).append("\n");
        }

        header.append("Typ: ").append(chunkType).append("\n\n");
        return header.toString();
    }

    List<String> applyOverlap(List<String> chunks) {
        if (chunks.size() <= 1 || chunkOverlap <= 0) {
            return chunks;
        }

        // 1 token ≈ 3 chars (German text)
        int overlapChars = chunkOverlap * 3;

        List<String> overlapped = new ArrayList<>();
        overlapped.add(chunks.get(0));

        for (int i = 1; i < chunks.size(); i++) {
            String prevChunk = chunks.get(i - 1);
            String currentChunk = chunks.get(i);

            int overlapStart = Math.max(0, prevChunk.length() - overlapChars);
            String overlapText = prevChunk.substring(overlapStart);
            overlapped.add(overlapText + "\n" + currentChunk);
        }
        return overlapped;
    }

    private Map<String, Object> buildBaseMetadata(ConfluenceDocument doc) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("pageId", String.valueOf(doc.pageId()));
        metadata.put("spaceKey", doc.spaceKey());
        metadata.put("pageTitle", doc.title());
        metadata.put("pageUrl", doc.url() != null ? doc.url() : "");
        metadata.put("labels", doc.labels() != null ? String.join(",", doc.labels()) : "");
        metadata.put("author", doc.author() != null ? doc.author() : "");
        metadata.put("lastModified", doc.lastModified() != null ? doc.lastModified().toString() : "");
        metadata.put("ancestors", doc.ancestorTitles() != null ? String.join(" > ", doc.ancestorTitles()) : "");
        return metadata;
    }
}
