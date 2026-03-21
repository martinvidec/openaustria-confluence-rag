# Spec 02: Confluence REST API Client

**Issue:** #3
**Phase:** 2
**Abhängigkeiten:** #1, #2

---

## Ziel

Robuster HTTP-Client für die Confluence REST API mit PAT-Authentifizierung, automatischer Paginierung und Retry-Logik.

## Package

`at.openaustria.confluencerag.crawler.client`

---

## API-Endpunkte

Der Client muss folgende Confluence REST API Endpunkte abstrahieren:

| Methode | Endpunkt | Parameter | Zweck |
|---|---|---|---|
| GET | `/rest/api/space` | `limit`, `start` | Alle Spaces auflisten |
| GET | `/rest/api/content` | `spaceKey`, `type=page`, `limit`, `start`, `expand` | Seiten eines Spaces |
| GET | `/rest/api/content/{id}/child/comment` | `limit`, `start`, `expand` | Kommentare einer Seite |
| GET | `/rest/api/content/{id}/child/attachment` | `limit`, `start` | Attachments einer Seite |
| GET | `/rest/api/content/search` | `cql`, `limit`, `start`, `expand` | CQL-Suche (inkr. Sync) |
| GET | `{attachment.downloadLink}` | — | Attachment-Datei herunterladen |

## Expand-Parameter

Beim Abruf von Seiten immer diese Felder expanden:

```
expand=body.storage,metadata.labels,version,space,ancestors
```

Bei Kommentaren:

```
expand=body.storage,version
```

---

## Klassen

### ConfluenceClient

```java
@Component
public class ConfluenceClient {

    // Constructor: HttpClient, ConfluenceProperties

    /** Alle Seiten eines Spaces (paginiert, transparent) */
    public List<ConfluencePageResponse> getPages(String spaceKey);

    /** Seiten geändert seit timestamp (CQL-basiert) */
    public List<ConfluencePageResponse> getPagesSince(String spaceKey, Instant since);

    /** Kommentare einer Seite */
    public List<ConfluenceCommentResponse> getComments(long pageId);

    /** Attachments einer Seite (nur Metadaten) */
    public List<ConfluenceAttachmentResponse> getAttachments(long pageId);

    /** Attachment-Datei herunterladen */
    public byte[] downloadAttachment(String downloadPath);

    /** Alle Spaces abrufen */
    public List<ConfluenceSpaceResponse> getSpaces();
}
```

### HTTP-Konfiguration

```java
@Configuration
public class ConfluenceClientConfig {

    @Bean
    public HttpClient confluenceHttpClient(ConfluenceProperties props) {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }
}
```

### Authentifizierung

PAT wird als `Bearer`-Token im `Authorization`-Header mitgesendet:

```
Authorization: Bearer <PAT>
```

---

## Paginierung

Confluence paginiert via `start` + `limit` Parameter. Die Response enthält:

```json
{
  "results": [...],
  "start": 0,
  "limit": 25,
  "size": 25,
  "_links": {
    "next": "/rest/api/content?start=25&limit=25..."
  }
}
```

**Strategie:** Solange `_links.next` vorhanden ist, weitere Requests senden. `limit` auf `50` setzen (guter Kompromiss zwischen Requests und Payload-Größe).

### PaginationHelper

```java
/**
 * Generische Paginierung für alle Confluence-Endpunkte.
 * Ruft Seiten ab bis keine "next" Link mehr vorhanden ist.
 */
public <T> List<T> fetchAllPages(
    String baseUrl,
    Map<String, String> params,
    Function<JsonNode, List<T>> resultMapper
);
```

---

## Retry-Logik

| HTTP Status | Verhalten |
|---|---|
| 200 | Erfolgreich verarbeiten |
| 401 | Abbruch mit Fehlermeldung: PAT ungültig |
| 403 | Abbruch: Keine Berechtigung für diesen Space |
| 404 | Warnung loggen, überspringen |
| 429 | Retry nach `Retry-After` Header (oder 30 Sekunden Default) |
| 500, 502, 503 | Retry mit Exponential Backoff: 1s, 2s, 4s, max 3 Versuche |

### Implementierung

```java
private <T> T executeWithRetry(HttpRequest request, Function<HttpResponse<String>, T> handler) {
    int maxRetries = 3;
    for (int attempt = 0; attempt <= maxRetries; attempt++) {
        HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return handler.apply(response);
        }
        if (response.statusCode() == 429 || response.statusCode() >= 500) {
            long waitMs = calculateBackoff(attempt, response);
            Thread.sleep(waitMs);
            continue;
        }
        // 401, 403 etc. → sofort Fehler
        throw new ConfluenceApiException(response.statusCode(), response.body());
    }
    throw new ConfluenceApiException("Max retries exceeded");
}
```

---

## Response DTOs

```java
public record ConfluencePageResponse(
    long id,
    String title,
    String status,
    ConfluenceBody body,
    ConfluenceSpace space,
    ConfluenceVersion version,
    ConfluenceMetadata metadata,
    List<ConfluenceAncestor> ancestors,
    ConfluenceLinks links
) {}

public record ConfluenceBody(
    ConfluenceStorage storage
) {}

public record ConfluenceStorage(
    String value,           // XHTML Storage Format
    String representation   // "storage"
) {}

public record ConfluenceSpace(
    String key,
    String name
) {}

public record ConfluenceVersion(
    int number,
    Instant when,           // lastModified
    String by               // Author Display Name (vereinfacht)
) {}

public record ConfluenceMetadata(
    ConfluenceLabels labels
) {}

public record ConfluenceLabels(
    List<ConfluenceLabel> results
) {}

public record ConfluenceLabel(
    String name
) {}

public record ConfluenceCommentResponse(
    long id,
    String title,
    ConfluenceBody body,
    ConfluenceVersion version
) {}

public record ConfluenceAttachmentResponse(
    long id,
    String title,
    String mediaType,
    ConfluenceLinks links
) {}

public record ConfluenceLinks(
    String self,
    String webui,
    String download,    // nur bei Attachments
    String next         // Pagination
) {}

public record ConfluenceAncestor(
    long id,
    String title
) {}
```

### JSON-Deserialisierung

Jackson mit folgender Konfiguration:

```java
ObjectMapper mapper = new ObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .registerModule(new JavaTimeModule());
```

`FAIL_ON_UNKNOWN_PROPERTIES = false` ist wichtig, da Confluence je nach Version unterschiedliche Felder liefert.

---

## CQL-Abfragen

Für die inkrementelle Synchronisation (Issue #10) muss der Client CQL-Suchen unterstützen:

```
cql=space="DEV" AND type="page" AND lastModified>"2026-03-20 00:00"
```

Endpunkt: `GET /rest/api/content/search?cql={cql}&expand=body.storage,...`

Der CQL-String muss URL-encoded werden. Datumsformat: `yyyy-MM-dd HH:mm`.

---

## Akzeptanzkriterien

- [ ] `getPages("SPACEKEY")` liefert alle Seiten eines Spaces inkl. Body und Labels
- [ ] Paginierung funktioniert transparent für alle Endpunkte
- [ ] Bei HTTP 429 wird automatisch gewartet und retried
- [ ] Bei HTTP 5xx wird mit Backoff retried (max 3 Versuche)
- [ ] Bei HTTP 401 wird eine verständliche Exception geworfen
- [ ] Unit-Tests für Paginierung und Retry-Logik (mit MockWebServer oder WireMock)
