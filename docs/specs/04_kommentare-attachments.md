# Spec 04: Kommentare, Attachments & Crawler-Orchestrierung

**Issues:** #6, #7
**Phase:** 2
**Abhängigkeiten:** #3, #4, #5

---

## Issue #6 — Kommentare & Attachments

### Ziel

Kommentare und Attachments einer Seite abrufen, konvertieren und als Teil des `ConfluenceDocument` bereitstellen.

### Package

`at.openaustria.confluencerag.crawler`

---

### Kommentare

#### API-Aufruf

```
GET /rest/api/content/{pageId}/child/comment?expand=body.storage,version&limit=50
```

Kommentare sind paginiert — gleiche Paginierungslogik wie bei Seiten.

#### Verarbeitung

1. `body.storage.value` mit `ConfluenceHtmlConverter` in Plaintext konvertieren
2. Autor und Datum aus `version` extrahieren
3. Als `CommentDocument` der Seite zuordnen

```java
public record CommentDocument(
    long commentId,
    String bodyText,
    String author,
    Instant createdAt
) {}
```

---

### Attachments

#### API-Aufruf

```
GET /rest/api/content/{pageId}/child/attachment?limit=50
```

#### Filter-Logik

Nur relevante Attachments verarbeiten:

| MediaType | Verarbeitung |
|---|---|
| `application/pdf` | Herunterladen + Tika-Extraktion |
| `application/msword`, `application/vnd.openxmlformats-officedocument.*` | Herunterladen + Tika-Extraktion |
| `text/plain` | Herunterladen + direkt als Text |
| Andere (Bilder, ZIPs, etc.) | Überspringen mit Log-Hinweis |

#### Download

```
GET {confluence.baseUrl}{attachment._links.download}
Authorization: Bearer {PAT}
```

#### Text-Extraktion

```java
@Component
public class AttachmentTextExtractor {

    /**
     * Extrahiert Text aus einem Attachment via Apache Tika.
     * @param content Dateiinhalt als byte[]
     * @param fileName Dateiname für Typ-Erkennung
     * @return extrahierter Text, oder empty wenn nicht extrahierbar
     */
    public Optional<String> extractText(byte[] content, String fileName) {
        try {
            TikaDocumentReader reader = new TikaDocumentReader(
                new ByteArrayResource(content)
            );
            List<Document> docs = reader.get();
            return docs.stream()
                .map(Document::getContent)
                .filter(text -> !text.isBlank())
                .reduce((a, b) -> a + "\n" + b);
        } catch (Exception e) {
            log.warn("Konnte Attachment '{}' nicht extrahieren: {}", fileName, e.getMessage());
            return Optional.empty();
        }
    }
}
```

#### Attachment-Modell

```java
public record AttachmentDocument(
    long attachmentId,
    String fileName,
    String mediaType,
    String extractedText
) {}
```

#### Größenlimit

Attachments über 50 MB werden übersprungen (konfigurierbar). Confluence liefert die Dateigröße im Attachment-Metadaten-Objekt (`extensions.fileSize`).

---

## Issue #7 — Crawler-Orchestrierung

### Ziel

Service der alle Crawler-Komponenten zusammenführt und für konfigurierte Spaces den kompletten Crawl durchführt.

---

### ConfluenceDocument (Ergebnis-Modell)

```java
public record ConfluenceDocument(
    long pageId,
    String spaceKey,
    String spaceName,
    String title,
    String url,                      // webui Link
    String bodyText,                 // Konvertierter Plaintext
    List<String> labels,
    List<CommentDocument> comments,
    List<AttachmentDocument> attachments,
    String author,
    Instant lastModified,
    List<String> ancestorTitles      // Seitenhierarchie: [Grandparent, Parent]
) {}
```

### CrawlerService

```java
@Service
public class CrawlerService {

    private final ConfluenceClient client;
    private final ConfluenceHtmlConverter converter;
    private final AttachmentTextExtractor attachmentExtractor;
    private final ConfluenceProperties properties;

    /**
     * Crawlt alle konfigurierten Spaces und liefert Dokumente.
     * Loggt Fortschritt: "Space DEV: Seite 42/128 - Seitentitel"
     */
    public List<ConfluenceDocument> crawlAll();

    /**
     * Crawlt einen einzelnen Space.
     */
    public List<ConfluenceDocument> crawlSpace(String spaceKey);

    /**
     * Crawlt nur Seiten die seit 'since' geändert wurden (für inkr. Sync).
     */
    public List<ConfluenceDocument> crawlChangesSince(String spaceKey, Instant since);
}
```

### Crawl-Ablauf pro Seite

```
1. Seite abrufen (aus bereits paginierter Liste)
2. body.storage.value → ConfluenceHtmlConverter → bodyText
3. Labels aus metadata.labels.results extrahieren
4. Kommentare abrufen → konvertieren
5. Attachments abrufen → filtern → herunterladen → extrahieren
6. ConfluenceDocument zusammenbauen
7. Fortschritt loggen
```

### Fehlerbehandlung pro Seite

Fehler bei einer einzelnen Seite (z.B. Kommentare abrufen schlägt fehl) dürfen **nicht** den gesamten Crawl abbrechen:

```java
try {
    comments = fetchAndConvertComments(page.id());
} catch (Exception e) {
    log.error("Fehler bei Kommentaren für Seite {}: {}", page.id(), e.getMessage());
    comments = List.of();  // Weiter ohne Kommentare
}
```

### Fortschritts-Logging

```
INFO  CrawlerService - Crawl gestartet für Spaces: [DEV, OPS]
INFO  CrawlerService - Space DEV: 128 Seiten gefunden
INFO  CrawlerService - Space DEV: Seite 1/128 - "Architektur-Übersicht"
INFO  CrawlerService - Space DEV: Seite 2/128 - "API-Dokumentation"
...
INFO  CrawlerService - Space DEV: Crawl abgeschlossen. 128 Seiten, 45 Kommentare, 12 Attachments.
INFO  CrawlerService - Space OPS: 64 Seiten gefunden
...
INFO  CrawlerService - Crawl abgeschlossen. 192 Seiten total in 2 Spaces.
```

---

## Akzeptanzkriterien

### Issue #6

- [ ] Kommentare werden als Plaintext extrahiert und der Seite zugeordnet
- [ ] PDF-Attachments werden heruntergeladen und Text wird via Tika extrahiert
- [ ] Word-Dokumente werden heruntergeladen und Text wird extrahiert
- [ ] Nicht-unterstützte Attachments werden übersprungen (mit Log-Hinweis)
- [ ] Attachments > 50 MB werden übersprungen
- [ ] Fehler bei einem Attachment brechen nicht den Crawl ab

### Issue #7

- [ ] `crawlAll()` crawlt alle konfigurierten Spaces
- [ ] `crawlSpace(spaceKey)` crawlt einen einzelnen Space vollständig
- [ ] Output: Liste von `ConfluenceDocument` mit allen Feldern befüllt
- [ ] Fortschritt wird geloggt (Space + Seitennummer + Titel)
- [ ] Fehler bei einer Seite bricht nicht den Crawl ab
- [ ] Integration-Test mit Mock-Confluence-Server (WireMock)
