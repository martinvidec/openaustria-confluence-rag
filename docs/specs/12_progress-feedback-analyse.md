# Progress-Feedback bei Ingest & Sync — Ist-Stand & Anforderungsanalyse

## 1. Ist-Stand-Analyse

### 1.1 Aktuelle Architektur (Datenfluss)

```
[Frontend]                [AdminController]              [Services]
    |                           |                            |
    |-- POST /admin/ingest ---->|                            |
    |<-- 202 {status:running} --|                            |
    |                           |-- CompletableFuture ------>|
    |                           |   (async, Fire & Forget)   |
    |-- GET /admin/job/status ->|                            |
    |<-- {status:running} ------|                            |
    |    (alle 3s polling)      |                            |
    |                           |                         [crawl]
    |                           |                         [chunk]
    |                           |                         [store]
    |                           |                            |
    |-- GET /admin/job/status ->|                            |
    |<-- {status:completed,  ---|<-- Ergebnis ---------------|
    |     result:{...}}         |                            |
```

### 1.2 Aktuelles Feedback an den User

| Zeitpunkt | Was der User sieht | Was tatsächlich passiert |
|-----------|---------------------|------------------------|
| Start | Toast: "Ingest gestartet..." | HTTP 202 → Async-Job startet |
| Während (bis zu Minuten) | Button zeigt "Sync..." (disabled) | Crawling → Chunking → Storing |
| Ende | Toast mit Endergebnis | JobStatus wechselt auf "completed" |

**Problem:** Zwischen Start und Ende gibt es **keinerlei Fortschrittsanzeige**. Der User sieht nur, dass "etwas läuft", hat aber keine Information über:
- Wie viele Seiten insgesamt verarbeitet werden müssen
- Wie viele bereits verarbeitet sind
- In welcher Phase sich der Prozess befindet (Crawling, Chunking, Storing)
- Geschätzte verbleibende Zeit
- Welche Seite gerade verarbeitet wird

### 1.3 Verfügbare Daten in den Services

#### IngestionService.ingestAll()
| Phase | Verfügbare Information | Aktuell genutzt? |
|-------|----------------------|------------------|
| **Crawling** | Seitenanzahl pro Space (nach `client.getPages()`) | Nur Log |
| **Chunking** | Fortlaufender Zähler `doc[i]` von `documents.size()` | Nein |
| **Chunking** | Seitenname (`doc.title()`) | Nur im Fehlerfall geloggt |
| **Storing** | Batch-Fortschritt `[stored/total]` Chunks | Nur Log |
| **Storing** | Fallback auf Einzel-Insert | Nur Log |

#### SyncService.syncSpace()
| Phase | Verfügbare Information | Aktuell genutzt? |
|-------|----------------------|------------------|
| **Erster Sync** | Delegation an `ingestSpace()` | Nein |
| **Änderungs-Crawl** | Anzahl geänderter Seiten | Nur Log |
| **Re-Ingest** | Fortlaufender Zähler pro geänderter Seite | Nein |
| **Löscherkennung** | Anzahl gelöschter Seiten | Nur Log |

#### CrawlerService.processPages()
| Phase | Verfügbare Information | Aktuell genutzt? |
|-------|----------------------|------------------|
| **Seitenabruf** | `pages.size()` total | Nur Log |
| **Pro Seite** | `i+1 / pages.size()`, Seitenname | Nur Log |
| **Pro Seite** | Anzahl Kommentare, Attachments | Nur Log |

### 1.4 Bestehende Infrastruktur

- **JobStatus Record**: `{status, operation, result, error}` — kein Feld für Fortschritt
- **AtomicReference<JobStatus>**: Wird nur bei Start/Ende/Fehler gesetzt, nie während der Verarbeitung
- **Polling-Intervall**: 3 Sekunden — grundsätzlich für Progress-Updates geeignet
- **Frontend**: `pollJobStatus()` prüft nur `completed` oder `failed`, ignoriert Zwischenstatus

---

## 2. Anforderungsanalyse

### 2.1 Funktionale Anforderungen

#### FA-1: Phasen-Anzeige
Der User muss sehen können, in welcher Phase sich der aktuelle Job befindet:
- **Crawling** — Seiten werden von Confluence abgerufen
- **Chunking** — Seiten werden in Chunks aufgeteilt
- **Storing** — Chunks werden in die Vektordatenbank geschrieben
- **Deleting** (nur Sync) — Gelöschte Seiten werden entfernt

#### FA-2: Seitenfortschritt
Während der Verarbeitung muss angezeigt werden:
- Aktuelle Seite: `"Seite 5 von 42"`
- Name der aktuell verarbeiteten Seite (optional, für Debugging nützlich)

#### FA-3: Chunk-Fortschritt
Beim Storing muss angezeigt werden:
- Chunks gespeichert: `"120 von 380 Chunks gespeichert"`

#### FA-4: Progress Bar
Eine visuelle Fortschrittsanzeige (Progress Bar) im Admin Panel, die:
- Den Gesamtfortschritt prozentual anzeigt
- Die aktuelle Phase textlich beschreibt
- Sich während des Polling-Zyklus (3s) aktualisiert

#### FA-5: Space-spezifischer Fortschritt
Bei Multi-Space-Jobs (z.B. "Alle Spaces syncen") soll erkennbar sein, welcher Space gerade verarbeitet wird.

#### FA-6: Fehlerzähler live
Die Anzahl der Fehler soll schon während der Verarbeitung sichtbar sein, nicht erst im Endergebnis.

### 2.2 Nicht-funktionale Anforderungen

#### NFA-1: Performance
- Progress-Updates dürfen die Verarbeitung nicht verlangsamen
- Das Polling-Intervall von 3s ist ausreichend — kein WebSocket nötig

#### NFA-2: Thread-Safety
- Progress-Updates müssen thread-safe sein (wie bisher `AtomicReference`)

#### NFA-3: Abwärtskompatibilität
- Bestehende API-Antworten (`IngestionResult`, `SyncResult`) bleiben unverändert
- Das neue Progress-Feld im `JobStatus` ist additiv (bestehende Clients ignorieren es)

#### NFA-4: Einfachheit
- Kein WebSocket, keine SSE für Progress — Polling reicht bei 3s Intervall
- Kein persistenter Progress-State — nur In-Memory während der Jobausführung

### 2.3 Betroffene Komponenten

| Komponente | Änderung | Umfang |
|-----------|---------|--------|
| `AdminController.JobStatus` | Neues Feld `progress` | Klein |
| `IngestionService` | Progress-Callbacks während Crawl/Chunk/Store | Mittel |
| `SyncService` | Progress-Callbacks während Sync-Phasen | Mittel |
| `CrawlerService` | Möglichkeit, Fortschritt nach außen zu melden | Mittel |
| `app.js` (Frontend) | Progress Bar rendern, Polling erweitern | Mittel |
| `index.html` / `style.css` | Progress Bar Markup/Styling | Klein |

### 2.4 Informationsmodell — Was pro Phase angezeigt wird

```
Ingest-Job:
├── Phase: CRAWLING
│   └── "Space DS: Seite 3/15 — 'Projektübersicht'"
├── Phase: CHUNKING
│   └── "Seite 8/42 — 'API-Dokumentation' (156 Chunks bisher)"
├── Phase: STORING
│   └── "240/380 Chunks gespeichert (Batch 5/8)"
└── Ergebnis: 42 Seiten, 380 Chunks, 2 Fehler, 1m 23s

Sync-Job:
├── Phase: CRAWLING_CHANGES
│   └── "Space DS: 5 geänderte Seiten gefunden"
├── Phase: UPDATING
│   └── "Seite 2/5 — 'Release Notes' (re-indexing)"
├── Phase: DETECTING_DELETIONS
│   └── "Prüfe gelöschte Seiten..."
├── Phase: DELETING
│   └── "3 Seiten gelöscht"
└── Ergebnis: 5 aktualisiert, 3 gelöscht, 28 Chunks, 0 Fehler, 15s
```
