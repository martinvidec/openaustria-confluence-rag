# Progress-Feedback bei Ingest & Sync — Technische Spezifikation

> Voraussetzung: [Ist-Stand & Anforderungsanalyse](12_progress-feedback-analyse.md)

## 1. Übersicht

Erweiterung der bestehenden Polling-Architektur um ein `progress`-Feld im `JobStatus`, das bei jedem Poll-Request den aktuellen Fortschritt des laufenden Jobs liefert. Im Frontend wird dieses Feld als Progress Bar mit Phase/Detail-Text dargestellt.

## 2. Backend-Design

### 2.1 Neues Record: `JobProgress`

```java
// AdminController.java (oder eigene Datei)
public record JobProgress(
    String phase,           // CRAWLING, CHUNKING, STORING, UPDATING, DELETING
    String detail,          // Freitext: "Space DS: Seite 3/15 — 'Projektübersicht'"
    int currentItem,        // Aktuelle Position (z.B. Seite 3)
    int totalItems,         // Gesamtzahl (z.B. 15 Seiten)
    int chunksProcessed,    // Bisher erzeugte/gespeicherte Chunks
    int errors,             // Bisherige Fehler
    String currentSpace     // Aktueller Space Key (bei Multi-Space-Jobs)
) {
    public int percentComplete() {
        return totalItems > 0 ? (int) ((currentItem * 100L) / totalItems) : 0;
    }
}
```

### 2.2 Erweiterung: `JobStatus`

```java
public record JobStatus(
    String status,       // idle, running, completed, failed
    String operation,    // ingest, sync, ingest:SPACEKEY, sync:SPACEKEY
    Object result,       // IngestionResult or SyncResult (nur bei completed)
    String error,        // Fehlermeldung (nur bei failed)
    JobProgress progress // NEU: Live-Fortschritt (nur bei running)
) {}
```

### 2.3 Progress-Callback-Mechanismus

Statt die Services direkt an den Controller zu koppeln, wird ein einfacher `Consumer<JobProgress>` als Callback verwendet:

```java
// AdminController — bei Jobstart:
Consumer<JobProgress> progressCallback = progress ->
    currentJob.set(new JobStatus("running", operation, null, null, progress));
```

Die Services erhalten diesen Callback als Parameter:

```java
// IngestionService
public IngestionResult ingestAll(Consumer<JobProgress> onProgress) { ... }

// SyncService
public SyncResult syncAll(Consumer<JobProgress> onProgress) { ... }
```

**Abwärtskompatibilität:** Die bestehenden parameterlosen Methoden bleiben als Overloads erhalten (z.B. für SyncScheduler):

```java
public IngestionResult ingestAll() {
    return ingestAll(p -> {}); // No-op callback
}
```

### 2.4 Progress-Updates im IngestionService

```java
public IngestionResult ingestAll(Consumer<JobProgress> onProgress) {
    // Phase 1: Crawling
    onProgress.accept(new JobProgress("CRAWLING", "Crawle Spaces...",
        0, 0, 0, 0, null));

    List<ConfluenceDocument> documents = crawlerService.crawlAll();

    // Phase 2: Chunking
    int total = documents.size();
    for (int i = 0; i < total; i++) {
        ConfluenceDocument doc = documents.get(i);
        onProgress.accept(new JobProgress("CHUNKING",
            String.format("Seite %d/%d — '%s'", i + 1, total, doc.title()),
            i + 1, total, chunksCreated, errors, doc.spaceKey()));
        // ... chunk logic ...
    }

    // Phase 3: Storing
    // storeBatched() bekommt ebenfalls den Callback
    chunksStored = storeBatched(allChunks, onProgress, total);

    return new IngestionResult(...);
}
```

Storing-Progress innerhalb `storeBatched()`:

```java
private int storeBatched(List<Document> chunks,
                         Consumer<JobProgress> onProgress, int totalPages) {
    int stored = 0;
    for (int i = 0; i < chunks.size(); i += batchSize) {
        // ... batch logic ...
        stored += batch.size();
        onProgress.accept(new JobProgress("STORING",
            String.format("%d/%d Chunks gespeichert", stored, chunks.size()),
            stored, chunks.size(), stored, errors, null));
    }
    return stored;
}
```

### 2.5 Progress-Updates im SyncService

```java
public SyncResult syncAll(Consumer<JobProgress> onProgress) {
    for (int s = 0; s < spaces.size(); s++) {
        String spaceKey = spaces.get(s);
        onProgress.accept(new JobProgress("CRAWLING_CHANGES",
            String.format("Space %s (%d/%d)", spaceKey, s + 1, spaces.size()),
            s + 1, spaces.size(), 0, 0, spaceKey));

        SyncResult result = syncSpace(spaceKey, onProgress);
        // ... aggregate results ...
    }
}

public SyncResult syncSpace(String spaceKey, Consumer<JobProgress> onProgress) {
    // Bei erstem Sync → delegiert an ingestSpace(spaceKey, onProgress)

    // Inkrementeller Sync:
    // Phase: CRAWLING_CHANGES
    List<ConfluenceDocument> changedDocs = crawlerService.crawlChangesSince(...);
    onProgress.accept(new JobProgress("CRAWLING_CHANGES",
        String.format("Space %s: %d geänderte Seiten", spaceKey, changedDocs.size()),
        0, changedDocs.size(), 0, errors, spaceKey));

    // Phase: UPDATING (pro geänderter Seite)
    for (int i = 0; i < changedDocs.size(); i++) {
        onProgress.accept(new JobProgress("UPDATING",
            String.format("Seite %d/%d — '%s'", i + 1, changedDocs.size(), doc.title()),
            i + 1, changedDocs.size(), chunksCreated, errors, spaceKey));
        // ... update logic ...
    }

    // Phase: DETECTING_DELETIONS
    onProgress.accept(new JobProgress("DETECTING_DELETIONS",
        "Prüfe gelöschte Seiten...", 0, 0, chunksCreated, errors, spaceKey));

    // Phase: DELETING (pro gelöschter Seite)
    for (int i = 0; i < deletedPageIds.size(); i++) {
        onProgress.accept(new JobProgress("DELETING",
            String.format("Lösche Seite %d/%d", i + 1, deletedPageIds.size()),
            i + 1, deletedPageIds.size(), chunksCreated, errors, spaceKey));
        // ... delete logic ...
    }
}
```

### 2.6 AdminController-Anpassung

```java
@PostMapping("/ingest")
public ResponseEntity<JobStatus> triggerIngestion() {
    if (isRunning()) return ResponseEntity.status(409).body(currentJob.get());

    currentJob.set(new JobStatus("running", "ingest", null, null, null));

    Consumer<JobProgress> onProgress = progress ->
        currentJob.set(new JobStatus("running", "ingest", null, null, progress));

    CompletableFuture.runAsync(() -> {
        try {
            IngestionResult result = ingestionService.ingestAll(onProgress);
            currentJob.set(new JobStatus("completed", "ingest", result, null, null));
        } catch (Exception e) {
            currentJob.set(new JobStatus("failed", "ingest", null, e.getMessage(), null));
        }
    });
    return ResponseEntity.accepted().body(currentJob.get());
}
```

## 3. Frontend-Design

### 3.1 Progress Bar HTML (im Admin Panel)

```html
<!-- Neues Element im Admin Panel, über den Space-Rows -->
<div id="job-progress" class="job-progress hidden">
    <div class="job-progress-header">
        <span id="job-progress-phase" class="job-progress-phase"></span>
        <span id="job-progress-percent" class="job-progress-percent"></span>
    </div>
    <div class="progress-bar-container">
        <div id="job-progress-bar" class="progress-bar"></div>
    </div>
    <div id="job-progress-detail" class="job-progress-detail"></div>
    <div class="job-progress-stats">
        <span id="job-progress-chunks"></span>
        <span id="job-progress-errors"></span>
    </div>
</div>
```

### 3.2 Progress Bar Styling

```css
.job-progress {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 12px 16px;
    margin-bottom: 12px;
}
.job-progress.hidden { display: none; }

.job-progress-header {
    display: flex;
    justify-content: space-between;
    margin-bottom: 6px;
    font-size: 0.85rem;
}
.job-progress-phase { font-weight: 600; }
.job-progress-percent { color: var(--text-secondary); }

.progress-bar-container {
    height: 8px;
    background: var(--border);
    border-radius: 4px;
    overflow: hidden;
}
.progress-bar {
    height: 100%;
    background: var(--primary);
    border-radius: 4px;
    transition: width 0.3s ease;
    width: 0%;
}

.job-progress-detail {
    margin-top: 6px;
    font-size: 0.8rem;
    color: var(--text-secondary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}
.job-progress-stats {
    display: flex;
    gap: 16px;
    margin-top: 4px;
    font-size: 0.8rem;
    color: var(--text-secondary);
}
```

### 3.3 JavaScript-Erweiterung

```javascript
// Erweiterte pollJobStatus() — Progress Bar aktualisieren
async function pollJobStatus(label) {
    showProgress(true);
    const poll = setInterval(async () => {
        const res = await fetch(`${API_BASE}/admin/job/status`);
        const job = await res.json();

        if (job.status === 'running' && job.progress) {
            updateProgress(job.progress);
        }

        if (job.status === 'completed' || job.status === 'failed') {
            clearInterval(poll);
            showProgress(false);
            // ... bestehende Logik ...
        }
    }, 3000);
}

function updateProgress(p) {
    const phaseLabels = {
        'CRAWLING': 'Crawling...',
        'CHUNKING': 'Chunking...',
        'STORING': 'Speichern...',
        'CRAWLING_CHANGES': 'Änderungen ermitteln...',
        'UPDATING': 'Aktualisieren...',
        'DETECTING_DELETIONS': 'Löschungen erkennen...',
        'DELETING': 'Löschen...'
    };

    document.getElementById('job-progress-phase').textContent =
        phaseLabels[p.phase] || p.phase;
    document.getElementById('job-progress-percent').textContent =
        p.totalItems > 0 ? `${p.percentComplete}%` : '';
    document.getElementById('job-progress-bar').style.width =
        (p.totalItems > 0 ? p.percentComplete : 0) + '%';
    document.getElementById('job-progress-detail').textContent =
        p.detail || '';
    document.getElementById('job-progress-chunks').textContent =
        p.chunksProcessed > 0 ? `${p.chunksProcessed} Chunks` : '';
    document.getElementById('job-progress-errors').textContent =
        p.errors > 0 ? `${p.errors} Fehler` : '';
}

function showProgress(visible) {
    document.getElementById('job-progress').classList.toggle('hidden', !visible);
}
```

### 3.4 Progress-Darstellung (Mockup)

```
┌─────────────────────────────────────────────────────────┐
│  Chunking...                                       62%  │
│  ████████████████████████████░░░░░░░░░░░░░░░░░░░░       │
│  Seite 26/42 — 'API-Dokumentation'                      │
│  156 Chunks                                             │
└─────────────────────────────────────────────────────────┘
```

## 4. API-Response-Beispiel

### GET /api/admin/job/status (während Ingest)

```json
{
  "status": "running",
  "operation": "ingest",
  "result": null,
  "error": null,
  "progress": {
    "phase": "CHUNKING",
    "detail": "Seite 26/42 — 'API-Dokumentation'",
    "currentItem": 26,
    "totalItems": 42,
    "chunksProcessed": 156,
    "errors": 0,
    "currentSpace": "DS",
    "percentComplete": 61
  }
}
```

### GET /api/admin/job/status (nach Abschluss)

```json
{
  "status": "completed",
  "operation": "ingest",
  "result": {
    "spacesProcessed": 2,
    "pagesProcessed": 42,
    "chunksCreated": 380,
    "chunksStored": 378,
    "errors": 2,
    "duration": 83.0
  },
  "error": null,
  "progress": null
}
```

## 5. Implementierungsplan

| Schritt | Beschreibung | Dateien |
|---------|-------------|---------|
| 1 | `JobProgress` Record erstellen | `AdminController.java` |
| 2 | `JobStatus` um `progress`-Feld erweitern | `AdminController.java` |
| 3 | `IngestionService` um `Consumer<JobProgress>` erweitern | `IngestionService.java` |
| 4 | `SyncService` um `Consumer<JobProgress>` erweitern | `SyncService.java` |
| 5 | `AdminController` Endpoints anpassen (Callback übergeben) | `AdminController.java` |
| 6 | Frontend: Progress Bar HTML + CSS | `index.html`, `style.css` |
| 7 | Frontend: `pollJobStatus()` erweitern, `updateProgress()` | `app.js` |
| 8 | Tests anpassen/erweitern | Tests |

## 6. Abgrenzung

**Nicht in Scope:**
- WebSocket/SSE für Push-basierte Updates (Polling reicht)
- Progress-Persistenz (nur In-Memory)
- Abbrechen laufender Jobs (separates Feature)
- ETA/Zeitschätzung (zu ungenau bei variablen Seitengrößen)
- Crawling-Unterfortschritt (Confluence-API gibt Seitenanzahl erst nach Pagination zurück)
