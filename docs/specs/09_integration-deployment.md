# Spec 09: Integration, Error Handling & Deployment

**Issues:** #15, #16
**Phase:** 6
**Abhängigkeiten:** #14 (alle vorherigen Komponenten müssen stehen)

---

## Issue #15 — Error Handling, Logging & E2E

### Ziel

Robuste Fehlerbehandlung über alle Komponenten. Strukturiertes Logging. End-to-End Validierung.

---

### Error Handling

#### Global Exception Handler

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ConfluenceApiException.class)
    public ResponseEntity<ErrorResponse> handleConfluenceError(ConfluenceApiException e) {
        log.error("Confluence API Fehler: {}", e.getMessage());
        return ResponseEntity.status(502).body(
            new ErrorResponse("CONFLUENCE_ERROR", "Confluence-Server nicht erreichbar oder Fehler: " + e.getMessage())
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception e) {
        log.error("Unerwarteter Fehler", e);
        return ResponseEntity.status(500).body(
            new ErrorResponse("INTERNAL_ERROR", "Ein interner Fehler ist aufgetreten")
        );
    }
}

public record ErrorResponse(String code, String message) {}
```

#### Fehlerszenarien und Verhalten

| Komponente | Fehler | Verhalten |
|---|---|---|
| **Confluence Client** | Verbindung fehlgeschlagen | Retry (3x), dann `ConfluenceApiException` |
| **Confluence Client** | PAT ungültig (401) | Sofort `ConfluenceApiException`, kein Retry |
| **Confluence Client** | Space nicht gefunden (404) | Warnung loggen, Space überspringen |
| **HTML Converter** | Parse-Fehler | Warnung loggen, Roh-Text als Fallback |
| **Tika Extraction** | PDF nicht lesbar | Warnung loggen, Attachment überspringen |
| **Ollama Embedding** | Modell nicht geladen | `503 Service Unavailable` mit Modellname in der Meldung |
| **Ollama Chat** | Timeout | `504 Gateway Timeout` |
| **Qdrant** | Verbindung fehlgeschlagen | Health DOWN, `503` bei Queries |
| **Qdrant** | Collection existiert nicht | Wird automatisch erstellt (`initialize-schema: true`) |

#### Custom Exceptions

```java
public class ConfluenceApiException extends RuntimeException {
    private final int statusCode;
    // ...
}

public class IngestionException extends RuntimeException {
    private final String spaceKey;
    private final Long pageId;
    // ...
}
```

---

### Logging

#### Log-Konfiguration (application.yml)

```yaml
logging:
  level:
    at.openaustria.confluencerag: INFO
    at.openaustria.confluencerag.crawler: INFO
    at.openaustria.confluencerag.ingestion: INFO
    at.openaustria.confluencerag.query: INFO
    org.springframework.ai: WARN
  pattern:
    console: "%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n"
```

#### Log-Nachrichten pro Komponente

**Crawler:**
```
INFO  CrawlerService     - Crawl gestartet: Spaces [DEV, OPS]
INFO  CrawlerService     - Space DEV: 128 Seiten gefunden
INFO  CrawlerService     - Space DEV: 42/128 "API-Dokumentation" (3 Kommentare, 1 Attachment)
WARN  CrawlerService     - Space DEV: Seite 55 - Attachment "large.zip" übersprungen (72 MB > 50 MB Limit)
ERROR CrawlerService     - Space DEV: Seite 67 - Kommentare konnten nicht abgerufen werden: 500 Internal Server Error
INFO  CrawlerService     - Crawl abgeschlossen: 192 Seiten, 89 Kommentare, 23 Attachments in 45s
```

**Ingestion:**
```
INFO  IngestionService   - Ingestion gestartet: 192 Dokumente
INFO  IngestionService   - Chunking: 192 Dokumente → 847 Chunks
INFO  IngestionService   - Embedding & Upsert: Batch 1/17 (50 Chunks)
INFO  IngestionService   - Ingestion abgeschlossen: 847 Chunks in 120s
```

**Query:**
```
INFO  QueryService       - Query: "Wie funktioniert die REST API?" (Filter: [DEV])
INFO  QueryService       - Similarity Search: 5 Ergebnisse (beste Relevanz: 0.87)
INFO  QueryService       - LLM-Antwort generiert in 2.3s
```

**Sync:**
```
INFO  SyncService        - Sync gestartet
INFO  SyncService        - Space DEV: 3 geänderte Seiten seit 2026-03-20T14:30:00Z
INFO  SyncService        - Space DEV: 1 gelöschte Seite erkannt
INFO  SyncService        - Sync abgeschlossen: 3 aktualisiert, 1 gelöscht, 12 Chunks in 8s
```

---

### Health Checks

```java
@Component
public class QdrantHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        // Qdrant Connectivity prüfen
    }
}

@Component
public class OllamaHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        // Ollama Erreichbarkeit + Modelle geladen prüfen
    }
}

@Component
public class ConfluenceHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        // Confluence Erreichbarkeit prüfen (GET /rest/api/space?limit=1)
    }
}
```

Actuator Endpunkt: `GET /actuator/health`

```json
{
  "status": "UP",
  "components": {
    "confluence": { "status": "UP" },
    "qdrant": { "status": "UP", "details": { "collections": 1, "vectors": 847 } },
    "ollama": { "status": "UP", "details": { "models": ["llama3", "nomic-embed-text"] } }
  }
}
```

---

### End-to-End Test

Manueller Testlauf mit einem Test-Space:

```
1. Test-Space in Confluence vorbereiten (oder bestehenden verwenden)
   - Mindestens 5 Seiten mit unterschiedlichen Inhalten
   - Mindestens 1 Seite mit PlantUML-Makro
   - Mindestens 1 Seite mit PDF-Attachment
   - Mindestens 1 Seite mit Kommentaren

2. Initiale Ingestion:
   POST /api/admin/ingest/{spaceKey}
   → IngestionResult prüfen: Alle Seiten verarbeitet?

3. Qdrant prüfen:
   - Dashboard: Collection hat erwartete Anzahl Vektoren
   - Metadaten stichprobenartig prüfen

4. Query testen:
   POST /api/chat
   {"question": "...", "spaceFilter": ["{spaceKey}"]}
   → Antwort ist relevant? Quellen stimmen?

5. Streaming testen:
   POST /api/chat/stream
   → Events kommen Token-weise? Sources am Ende?

6. Frontend testen:
   - Chat-UI im Browser öffnen
   - Frage stellen → Antwort wird gestreamt
   - Quellen sind klickbar und führen zur richtigen Confluence-Seite
   - Space-Filter funktioniert

7. Inkrementeller Sync:
   - Eine Seite in Confluence ändern
   - POST /api/admin/sync/{spaceKey}
   - Erneut fragen → Antwort enthält aktualisierte Information
```

---

## Issue #16 — Docker Compose Final & README

### Docker Compose (finalisiert)

```yaml
services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      CONFLUENCE_BASE_URL: ${CONFLUENCE_BASE_URL}
      CONFLUENCE_PAT: ${CONFLUENCE_PAT}
      CONFLUENCE_SPACES: ${CONFLUENCE_SPACES}
      OLLAMA_BASE_URL: http://ollama:11434
      QDRANT_HOST: qdrant
      QDRANT_GRPC_PORT: 6334
    depends_on:
      qdrant:
        condition: service_healthy
      ollama:
        condition: service_started
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3

  qdrant:
    image: qdrant/qdrant:latest
    ports:
      - "6333:6333"
      - "6334:6334"
    volumes:
      - qdrant_data:/qdrant/storage
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:6333/healthz"]
      interval: 10s
      timeout: 5s
      retries: 3

  ollama:
    image: ollama/ollama:latest
    ports:
      - "11434:11434"
    volumes:
      - ollama_data:/root/.ollama
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: all
              capabilities: [gpu]

volumes:
  qdrant_data:
  ollama_data:
```

### Dockerfile

```dockerfile
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### .env (Template)

```env
CONFLUENCE_BASE_URL=https://confluence.example.com
CONFLUENCE_PAT=your-personal-access-token
CONFLUENCE_SPACES=DEV,OPS,TEAM
```

### README-Struktur

```markdown
# Confluence RAG

KI-gestütztes Q&A über Confluence-Inhalte.

## Voraussetzungen
- Docker + Docker Compose
- Confluence On-Premise mit PAT
- GPU empfohlen (für Ollama)

## Schnellstart
1. `.env` Datei anlegen
2. `docker compose up -d`
3. Ollama-Modelle laden
4. Initialen Crawl starten
5. Chat-UI öffnen

## Konfiguration
(Tabelle mit allen Umgebungsvariablen)

## Architektur
(Kurze Beschreibung der Komponenten)

## API-Endpunkte
(Tabelle mit Endpunkten)
```

---

## Akzeptanzkriterien

### Issue #15

- [ ] Alle erwartbaren Fehler werden abgefangen und als verständliche Meldung zurückgegeben
- [ ] Fehler bei einzelnen Seiten/Attachments brechen nicht den Crawl/Sync ab
- [ ] Health-Endpunkt zeigt Status aller Abhängigkeiten
- [ ] Logging zeigt Crawl-Fortschritt, Ingestion-Status und Query-Latenz
- [ ] E2E-Test: Crawl → Ingestion → Query → Antwort mit Quellen funktioniert

### Issue #16

- [ ] `docker compose up` startet das komplette System (App + Qdrant + Ollama)
- [ ] `.env.example` dokumentiert alle Umgebungsvariablen
- [ ] Dockerfile baut die Anwendung korrekt
- [ ] README beschreibt Setup, Konfiguration und ersten Crawl
- [ ] Ein Test-Space ist erfolgreich indexiert und über das Chat-UI abfragbar
