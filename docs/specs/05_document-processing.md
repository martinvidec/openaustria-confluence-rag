# Spec 05: Document Processing — Chunking & Embedding

**Issues:** #8, #9
**Phase:** 3
**Abhängigkeiten:** #7 (Crawler liefert ConfluenceDocuments)

---

## Ziel

`ConfluenceDocument`-Objekte in Chunks aufteilen, embedden und in Qdrant speichern.

## Package

`at.openaustria.confluencerag.ingestion`

---

## Issue #8 — Chunking-Pipeline

### Chunk-Strategie

Jedes `ConfluenceDocument` erzeugt mehrere Chunks. Body, Kommentare und Attachments werden separat gechunkt, damit die Herkunft pro Chunk klar ist.

| Quelle | Chunk-Typ | Vorgehen |
|---|---|---|
| `bodyText` | `PAGE` | TokenTextSplitter, 800 Token, 100 Token Overlap |
| Jeder `CommentDocument.bodyText` | `COMMENT` | Kommentare sind meist kurz — nur splitten wenn > 800 Token |
| Jeder `AttachmentDocument.extractedText` | `ATTACHMENT` | TokenTextSplitter, 800 Token, 100 Token Overlap |

### Chunk-Modell

Kein eigenes Modell nötig — Spring AI's `Document` Klasse verwenden:

```java
// Spring AI Document:
// - content: String (der Chunk-Text)
// - metadata: Map<String, Object> (beliebige Metadaten)

Document chunk = new Document(chunkText, Map.of(
    "pageId",       String.valueOf(doc.pageId()),
    "spaceKey",     doc.spaceKey(),
    "pageTitle",    doc.title(),
    "pageUrl",      doc.url(),
    "labels",       String.join(",", doc.labels()),
    "chunkType",    "PAGE",          // oder COMMENT, ATTACHMENT
    "author",       doc.author(),
    "lastModified", doc.lastModified().toString(),
    "ancestors",    String.join(" > ", doc.ancestorTitles())
));
```

### ChunkingService

```java
@Service
public class ChunkingService {

    private final TokenTextSplitter textSplitter;

    public ChunkingService() {
        this.textSplitter = new TokenTextSplitter(
            800,    // defaultChunkSize
            100,    // minChunkSizeChars
            50,     // minChunkLengthToEmbed
            200,    // maxNumChunks (pro Dokument)
            true    // keepSeparator
        );
    }

    /**
     * Erzeugt Spring AI Documents mit Metadaten aus einem ConfluenceDocument.
     */
    public List<Document> chunkDocument(ConfluenceDocument doc);
}
```

### Chunk-Erzeugung

```
Für jedes ConfluenceDocument:
  1. bodyText → textSplitter.split() → Documents mit chunkType=PAGE
  2. Für jeden Kommentar:
     - Text < 800 Token → 1 Document mit chunkType=COMMENT
     - Text >= 800 Token → textSplitter.split() → Documents mit chunkType=COMMENT
  3. Für jedes Attachment:
     - extractedText → textSplitter.split() → Documents mit chunkType=ATTACHMENT
     - Zusätzliches Metadatum: "attachmentName" = fileName
  4. Alle Chunks erben die Seiten-Metadaten (pageId, spaceKey, title, url, labels)
```

### Leere Inhalte

- Leerer `bodyText` → Keine PAGE-Chunks, Warnung loggen
- Leere Kommentare/Attachments → Überspringen
- Dokument ohne jeglichen Text → Komplett überspringen mit Warnung

---

## Issue #9 — Embedding & Qdrant-Upsert

### Qdrant Collection

Spring AI erstellt die Collection automatisch (`initialize-schema: true`). Konfiguration:

```yaml
spring:
  ai:
    vectorstore:
      qdrant:
        collection-name: confluence-chunks
        initialize-schema: true
```

Die Vektordimension wird automatisch vom Embedding-Modell bestimmt:
- `nomic-embed-text`: 768 Dimensionen
- `mxbai-embed-large`: 1024 Dimensionen

### IngestionService

```java
@Service
public class IngestionService {

    private final CrawlerService crawlerService;
    private final ChunkingService chunkingService;
    private final VectorStore vectorStore;

    /**
     * Vollständiger Ingestion-Lauf: Crawlen → Chunken → Embedden → Speichern.
     */
    public IngestionResult ingestAll();

    /**
     * Ingestion für einen einzelnen Space.
     */
    public IngestionResult ingestSpace(String spaceKey);

    /**
     * Einzelnes Dokument ingesten (für inkr. Sync).
     */
    public void ingestDocument(ConfluenceDocument doc);
}
```

### IngestionResult

```java
public record IngestionResult(
    int spacesProcessed,
    int pagesProcessed,
    int chunksCreated,
    int chunksStored,
    int errors,
    Duration duration
) {}
```

### Batch-Verarbeitung

Spring AI's `VectorStore.add()` akzeptiert eine `List<Document>`. Für Performance in Batches von 50 Chunks aufteilen:

```java
private void storeBatched(List<Document> chunks, int batchSize) {
    for (int i = 0; i < chunks.size(); i += batchSize) {
        List<Document> batch = chunks.subList(i, Math.min(i + batchSize, chunks.size()));
        vectorStore.add(batch);
        log.debug("Batch gespeichert: {}/{} Chunks", Math.min(i + batchSize, chunks.size()), chunks.size());
    }
}
```

### Metadaten in Qdrant

Die Metadaten werden automatisch als Qdrant Payload gespeichert und sind filterbar:

```json
{
  "vector": [0.1, 0.2, ...],
  "payload": {
    "pageId": "12345",
    "spaceKey": "DEV",
    "pageTitle": "API-Dokumentation",
    "pageUrl": "https://confluence.example.com/display/DEV/API-Dokumentation",
    "labels": "api,rest,documentation",
    "chunkType": "PAGE",
    "author": "Max Mustermann",
    "lastModified": "2026-03-20T14:30:00Z",
    "ancestors": "Entwicklung > Backend"
  }
}
```

### Trigger

Der initiale Ingestion-Lauf wird manuell ausgelöst (REST-Endpunkt oder CLI-Command):

```java
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @PostMapping("/ingest")
    public ResponseEntity<IngestionResult> triggerIngestion();

    @PostMapping("/ingest/{spaceKey}")
    public ResponseEntity<IngestionResult> triggerSpaceIngestion(@PathVariable String spaceKey);
}
```

---

## Akzeptanzkriterien

### Issue #8

- [ ] `ConfluenceDocument` wird korrekt in Chunks aufgeteilt
- [ ] Chunks sind innerhalb der konfigurierten Größe (800 Token)
- [ ] Jeder Chunk hat vollständige Metadaten
- [ ] `chunkType` unterscheidet PAGE, COMMENT, ATTACHMENT
- [ ] Leere Inhalte werden übersprungen
- [ ] Unit-Tests für verschiedene Dokument-Konstellationen

### Issue #9

- [ ] Chunks werden embedded und in Qdrant gespeichert
- [ ] Qdrant Collection wird automatisch angelegt
- [ ] Metadaten sind als Payload in Qdrant sichtbar (Qdrant Dashboard prüfen)
- [ ] Batch-Verarbeitung (50 Chunks pro Batch)
- [ ] `POST /api/admin/ingest` triggert den Ingestion-Lauf
- [ ] `IngestionResult` wird korrekt befüllt und zurückgegeben
