# Spec 06: Inkrementelle Synchronisation

**Issue:** #10
**Phase:** 3
**Abhängigkeiten:** #9 (Ingestion-Pipeline muss stehen)

---

## Ziel

Nur geänderte und neue Seiten seit dem letzten Sync verarbeiten. Gelöschte Seiten aus Qdrant entfernen. Periodischer Scheduler.

## Package

`at.openaustria.confluencerag.ingestion`

---

## Sync-Strategie

### Delta-Erkennung via CQL

Confluence unterstützt CQL-Queries mit Datumsfilter:

```
space = "DEV" AND type = "page" AND lastModified > "2026-03-20 14:00"
```

Endpunkt: `GET /rest/api/content/search?cql={cql}&expand=body.storage,...`

### Gelöschte Seiten erkennen

Confluence hat keinen "gelöschte Seiten seit X"-Endpunkt. Strategie:

1. Alle aktuellen Page-IDs eines Spaces abrufen (leichter Request ohne Body-Expand)
2. Mit den in Qdrant gespeicherten Page-IDs vergleichen
3. IDs die in Qdrant aber nicht mehr in Confluence sind → Chunks löschen

```
GET /rest/api/content?spaceKey=DEV&type=page&limit=200&expand=
```

Nur `id` und `title` werden benötigt — kein Body-Expand, dadurch schnell und leichtgewichtig.

---

## Timestamp-Tracking

### SyncState

```java
public record SyncState(
    String spaceKey,
    Instant lastSyncTimestamp,
    int pagesProcessed,
    int pagesDeleted
) {}
```

### Persistenz

Einfachste Lösung für MVP: JSON-Datei im Dateisystem.

```java
@Component
public class SyncStateRepository {

    private final Path stateFile;  // z.B. ./data/sync-state.json

    /** Letzten Sync-Zeitstempel für einen Space lesen */
    public Optional<Instant> getLastSync(String spaceKey);

    /** Sync-Zeitstempel nach erfolgreichem Sync aktualisieren */
    public void updateLastSync(String spaceKey, Instant timestamp);

    /** Alle bekannten pageIds für einen Space (für Lösch-Erkennung) */
    public Set<String> getKnownPageIds(String spaceKey);

    /** Bekannte pageIds aktualisieren */
    public void updateKnownPageIds(String spaceKey, Set<String> pageIds);
}
```

### Datei-Format

```json
{
  "DEV": {
    "lastSync": "2026-03-20T14:30:00Z",
    "knownPageIds": ["12345", "12346", "12347"]
  },
  "OPS": {
    "lastSync": "2026-03-20T14:30:00Z",
    "knownPageIds": ["23001", "23002"]
  }
}
```

Speicherort konfigurierbar:

```yaml
confluence:
  sync:
    state-file: ${SYNC_STATE_FILE:./data/sync-state.json}
```

---

## Sync-Ablauf

```
1. Für jeden konfigurierten Space:
   a. lastSync = syncStateRepository.getLastSync(spaceKey)
   b. Falls lastSync vorhanden:
      - Geänderte Seiten via CQL abrufen
      - Für jede geänderte Seite:
        i.  Alte Chunks in Qdrant löschen (Filter: pageId == X)
        ii. Neues Dokument crawlen, chunken, embedden, speichern
      - Gelöschte Seiten erkennen:
        i.  Aktuelle pageIds aus Confluence abrufen
        ii. Vergleich mit knownPageIds
        iii. Fehlende pageIds → Chunks in Qdrant löschen
   c. Falls lastSync NICHT vorhanden (erster Lauf):
      - Vollständigen Crawl durchführen (wie ingestSpace)
   d. Neuen Timestamp + aktuelle pageIds speichern
2. Ergebnis loggen: X geändert, Y gelöscht, Z neu
```

### Chunks löschen in Qdrant

Spring AI VectorStore unterstützt Löschen via Filter. Alternativ direkt über Qdrant-Client:

```java
// Option 1: Spring AI (falls unterstützt)
vectorStore.delete(
    FilterExpressionBuilder.eq("pageId", pageId).build()
);

// Option 2: Qdrant Java Client direkt
qdrantClient.deleteAsync(
    collectionName,
    Filter.newBuilder()
        .addMust(FieldCondition.matchKeyword("pageId", pageId))
        .build()
);
```

Welche Option verfügbar ist, hängt von der Spring AI Qdrant-Version ab. Beim Implementieren prüfen.

---

## SyncService

```java
@Service
public class SyncService {

    private final CrawlerService crawlerService;
    private final ChunkingService chunkingService;
    private final VectorStore vectorStore;
    private final SyncStateRepository syncStateRepository;
    private final ConfluenceClient confluenceClient;

    /**
     * Inkrementeller Sync für alle konfigurierten Spaces.
     */
    public SyncResult syncAll();

    /**
     * Inkrementeller Sync für einen einzelnen Space.
     */
    public SyncResult syncSpace(String spaceKey);
}
```

### SyncResult

```java
public record SyncResult(
    int pagesUpdated,
    int pagesDeleted,
    int pagesNew,
    int chunksCreated,
    int errors,
    Duration duration
) {}
```

---

## Scheduler

```java
@Component
@ConditionalOnProperty(name = "confluence.sync.enabled", havingValue = "true")
public class SyncScheduler {

    private final SyncService syncService;

    @Scheduled(cron = "${confluence.sync.cron}")
    public void scheduledSync() {
        log.info("Geplanter Sync gestartet");
        SyncResult result = syncService.syncAll();
        log.info("Sync abgeschlossen: {}", result);
    }
}
```

### Konfiguration

```yaml
confluence:
  sync:
    enabled: true                   # false bis Phase 3 fertig
    cron: "0 */30 * * * *"          # alle 30 Minuten
    state-file: ./data/sync-state.json
```

---

## Admin-Endpunkte

```java
// Ergänzung zu AdminController (aus Spec 05)

@PostMapping("/sync")
public ResponseEntity<SyncResult> triggerSync();

@PostMapping("/sync/{spaceKey}")
public ResponseEntity<SyncResult> triggerSpaceSync(@PathVariable String spaceKey);

@GetMapping("/sync/status")
public ResponseEntity<Map<String, SyncState>> getSyncStatus();
```

---

## Akzeptanzkriterien

- [ ] Nur Seiten geändert seit letztem Sync werden verarbeitet
- [ ] Alte Chunks einer geänderten Seite werden vor Neuindexierung gelöscht
- [ ] Gelöschte Seiten werden erkannt und Chunks aus Qdrant entfernt
- [ ] Erster Lauf (kein lastSync) führt Voll-Crawl durch
- [ ] Sync-Timestamp wird nach erfolgreichem Sync persistiert
- [ ] `POST /api/admin/sync` triggert manuellen Sync
- [ ] `GET /api/admin/sync/status` zeigt letzten Sync-Zeitstempel pro Space
- [ ] Scheduler läuft periodisch (wenn enabled)
- [ ] Fehler bei einzelnen Seiten brechen nicht den gesamten Sync ab
