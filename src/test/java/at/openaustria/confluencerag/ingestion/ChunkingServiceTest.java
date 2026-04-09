package at.openaustria.confluencerag.ingestion;

import at.openaustria.confluencerag.config.IngestionProperties;
import at.openaustria.confluencerag.crawler.model.AttachmentDocument;
import at.openaustria.confluencerag.crawler.model.CommentDocument;
import at.openaustria.confluencerag.crawler.model.ConfluenceDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkingServiceTest {

    private static final String LONG_TEXT = """
            This is a sufficiently long text that should exceed the minimum chunk length
            requirement of the TokenTextSplitter. It needs to contain enough words and
            characters to be considered a valid chunk by the splitter. The splitter has a
            minimum chunk length of 50 characters, so we need to make sure our test data
            is long enough to pass this threshold. This paragraph should be sufficient.
            """;
    private static final String LONG_COMMENT = """
            This is a comment with enough text to be chunked properly by the splitter.
            Comments in Confluence can be quite detailed and contain valuable information
            about the page content, decisions made, or questions from team members.
            """;
    private static final String LONG_ATTACHMENT = """
            This is extracted text from a PDF attachment that contains important
            documentation about the project architecture, deployment procedures, and
            operational guidelines. The text is long enough to be processed by the chunker.
            """;

    private ChunkingService chunkingService;

    @BeforeEach
    void setUp() {
        chunkingService = new ChunkingService(new IngestionProperties(500, 50, 50, 2, 300, 1024));
    }

    @Test
    void chunksSimpleDocument() {
        ConfluenceDocument doc = createDoc(LONG_TEXT, List.of(), List.of());
        List<Document> chunks = chunkingService.chunkDocument(doc);

        assertFalse(chunks.isEmpty());
        assertEquals("PAGE", chunks.get(0).getMetadata().get("chunkType"));
        assertEquals("12345", chunks.get(0).getMetadata().get("pageId"));
        assertEquals("DEV", chunks.get(0).getMetadata().get("spaceKey"));
        assertEquals("Test Page", chunks.get(0).getMetadata().get("pageTitle"));
    }

    @Test
    void chunkTextStartsWithHeader() {
        ConfluenceDocument doc = createDoc(LONG_TEXT, List.of(), List.of());
        List<Document> chunks = chunkingService.chunkDocument(doc);

        assertFalse(chunks.isEmpty());
        String chunkText = chunks.get(0).getText();
        assertTrue(chunkText.startsWith("Titel: Test Page\n"), "Chunk should start with title header");
        assertTrue(chunkText.contains("Pfad: Parent > Grandparent"), "Chunk should contain ancestors");
        assertTrue(chunkText.contains("Labels: api,rest"), "Chunk should contain labels");
        assertTrue(chunkText.contains("Typ: PAGE"), "Chunk should contain chunk type");
    }

    @Test
    void chunkHeaderOmitsEmptyFields() {
        ConfluenceDocument doc = new ConfluenceDocument(
                12345L, "DEV", "Development", "Test Page",
                "https://confluence/display/DEV/Test+Page",
                LONG_TEXT, List.of(), List.of(), List.of(),
                "Author", Instant.now(), List.of()
        );
        List<Document> chunks = chunkingService.chunkDocument(doc);

        String chunkText = chunks.get(0).getText();
        assertTrue(chunkText.contains("Titel: Test Page\n"));
        assertFalse(chunkText.contains("Pfad:"), "Empty ancestors should be omitted");
        assertFalse(chunkText.contains("Labels:"), "Empty labels should be omitted");
    }

    @Test
    void commentChunkHasCommentTypeInHeader() {
        CommentDocument comment = new CommentDocument(1L, LONG_COMMENT, "User", Instant.now());
        ConfluenceDocument doc = createDoc(LONG_TEXT, List.of(comment), List.of());
        List<Document> chunks = chunkingService.chunkDocument(doc);

        Document commentChunk = chunks.stream()
                .filter(c -> "COMMENT".equals(c.getMetadata().get("chunkType")))
                .findFirst().orElseThrow();
        assertTrue(commentChunk.getText().contains("Typ: COMMENT"));
    }

    @Test
    void attachmentChunkHasAttachmentTypeInHeader() {
        AttachmentDocument att = new AttachmentDocument(1L, "doc.pdf", "application/pdf", LONG_ATTACHMENT);
        ConfluenceDocument doc = createDoc(LONG_TEXT, List.of(), List.of(att));
        List<Document> chunks = chunkingService.chunkDocument(doc);

        Document attChunk = chunks.stream()
                .filter(c -> "ATTACHMENT".equals(c.getMetadata().get("chunkType")))
                .findFirst().orElseThrow();
        assertTrue(attChunk.getText().contains("Typ: ATTACHMENT"));
    }

    @Test
    void applyOverlapAddsOverlapText() {
        List<String> chunks = List.of("First chunk with some content here.", "Second chunk with other content.");
        List<String> overlapped = chunkingService.applyOverlap(chunks);

        assertEquals(2, overlapped.size());
        assertEquals("First chunk with some content here.", overlapped.get(0));
        // Second chunk should contain overlap from first chunk
        assertTrue(overlapped.get(1).contains("Second chunk with other content."));
        assertTrue(overlapped.get(1).length() > "Second chunk with other content.".length(),
                "Overlapped chunk should be longer than original");
    }

    @Test
    void applyOverlapSingleChunkUnchanged() {
        List<String> chunks = List.of("Only one chunk.");
        List<String> overlapped = chunkingService.applyOverlap(chunks);

        assertEquals(1, overlapped.size());
        assertEquals("Only one chunk.", overlapped.get(0));
    }

    @Test
    void applyOverlapEmptyListUnchanged() {
        List<String> overlapped = chunkingService.applyOverlap(List.of());
        assertTrue(overlapped.isEmpty());
    }

    @Test
    void chunksComments() {
        CommentDocument comment = new CommentDocument(1L, LONG_COMMENT, "User", Instant.now());
        ConfluenceDocument doc = createDoc(LONG_TEXT, List.of(comment), List.of());
        List<Document> chunks = chunkingService.chunkDocument(doc);

        long commentChunks = chunks.stream()
                .filter(c -> "COMMENT".equals(c.getMetadata().get("chunkType")))
                .count();
        assertTrue(commentChunks > 0);
    }

    @Test
    void chunksAttachments() {
        AttachmentDocument att = new AttachmentDocument(1L, "doc.pdf", "application/pdf", LONG_ATTACHMENT);
        ConfluenceDocument doc = createDoc(LONG_TEXT, List.of(), List.of(att));
        List<Document> chunks = chunkingService.chunkDocument(doc);

        List<Document> attChunks = chunks.stream()
                .filter(c -> "ATTACHMENT".equals(c.getMetadata().get("chunkType")))
                .toList();
        assertFalse(attChunks.isEmpty());
        assertEquals("doc.pdf", attChunks.get(0).getMetadata().get("attachmentName"));
    }

    @Test
    void skipsEmptyBody() {
        ConfluenceDocument doc = createDoc("", List.of(), List.of());
        List<Document> chunks = chunkingService.chunkDocument(doc);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void skipsEmptyComments() {
        CommentDocument comment = new CommentDocument(1L, "", "User", Instant.now());
        ConfluenceDocument doc = createDoc(LONG_TEXT, List.of(comment), List.of());
        List<Document> chunks = chunkingService.chunkDocument(doc);

        long commentChunks = chunks.stream()
                .filter(c -> "COMMENT".equals(c.getMetadata().get("chunkType")))
                .count();
        assertEquals(0, commentChunks);
    }

    @Test
    void metadataContainsAllFields() {
        ConfluenceDocument doc = createDoc(LONG_TEXT, List.of(), List.of());
        List<Document> chunks = chunkingService.chunkDocument(doc);

        var metadata = chunks.get(0).getMetadata();
        assertNotNull(metadata.get("pageId"));
        assertNotNull(metadata.get("spaceKey"));
        assertNotNull(metadata.get("pageTitle"));
        assertNotNull(metadata.get("pageUrl"));
        assertNotNull(metadata.get("labels"));
        assertNotNull(metadata.get("chunkType"));
        assertNotNull(metadata.get("author"));
        assertNotNull(metadata.get("lastModified"));
        assertNotNull(metadata.get("ancestors"));
    }

    private ConfluenceDocument createDoc(String body, List<CommentDocument> comments,
                                          List<AttachmentDocument> attachments) {
        return new ConfluenceDocument(
                12345L, "DEV", "Development", "Test Page",
                "https://confluence/display/DEV/Test+Page",
                body, List.of("api", "rest"), comments, attachments,
                "Author", Instant.now(), List.of("Parent", "Grandparent")
        );
    }
}
