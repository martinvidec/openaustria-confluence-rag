# Spec 10: Frontend Admin-Panel — Ingest & Sync Steuerung

**Issue:** #17
**Phase:** Post-MVP Enhancement
**Abhängigkeiten:** #13, #14, #15 (Frontend + Admin API müssen stehen)

---

## Analyse Ist-Stand

### Frontend (aktuell)

Das Frontend ist eine Vanilla HTML/CSS/JS SPA (`src/main/resources/static/`). Es bietet:

- **Space-Filter** — Pill-Buttons zur Auswahl der zu durchsuchenden Spaces
- **Chat-Interface** — Nachrichtenverlauf mit Streaming (SSE), Markdown-Rendering, Quellenangaben
- **Eingabefeld** — Text-Input mit Senden-Button

Es gibt **keine UI-Elemente** für Admin-Operationen. Ingest und Sync können nur via `curl` oder Postman ausgelöst werden.

### Backend Admin-API (vorhanden)

| Methode | Endpunkt | Funktion | Response |
|---|---|---|---|
| `POST` | `/api/admin/ingest` | Voll-Crawl aller Spaces | `IngestionResult` |
| `POST` | `/api/admin/ingest/{spaceKey}` | Einzelnen Space ingesten | `IngestionResult` |
| `POST` | `/api/admin/sync` | Inkrementeller Sync aller Spaces | `SyncResult` |
| `POST` | `/api/admin/sync/{spaceKey}` | Einzelnen Space syncen | `SyncResult` |
| `GET` | `/api/admin/sync/status` | Letzter Sync-Zeitstempel pro Space | `Map<String, SpaceState>` |

### Response-Typen

```java
record IngestionResult(
    int spacesProcessed, int pagesProcessed,
    int chunksCreated, int chunksStored,
    int errors, Duration duration
) {}

record SyncResult(
    int pagesUpdated, int pagesDeleted, int pagesNew,
    int chunksCreated, int errors, Duration duration
) {}

record SpaceState(
    Instant lastSync,
    Set<String> knownPageIds
) {}
```

### Problem

Ein Benutzer muss aktuell die Kommandozeile nutzen, um neue Confluence-Inhalte in die RAG-Pipeline zu laden. Das ist für Nicht-Entwickler nicht praktikabel.

---

## Anforderungen

1. **Sync-Button im Header** — Prominenter Button der einen inkrementellen Sync auslöst
2. **Sync-Status Anzeige** — Letzter Sync-Zeitstempel pro Space sichtbar
3. **Fortschritts-Feedback** — Während Sync/Ingest läuft: Lade-Indikator + Ergebnis-Zusammenfassung
4. **Voll-Ingest Option** — Möglichkeit einen kompletten Neu-Crawl auszulösen (z.B. über Dropdown)
5. **Keine Unterbrechung des Chats** — Sync läuft asynchron, Chat bleibt nutzbar

---

## UI-Design

### Header-Erweiterung

```
+-------------------------------------------------------------+
|  Confluence RAG Chat    [Space-Filter]   [🔄 Sync] [⚙️]     |
+-------------------------------------------------------------+
```

- **Sync-Button** (`🔄 Sync`): Löst `POST /api/admin/sync` aus
- **Settings-Icon** (`⚙️`): Öffnet ein Dropdown/Panel mit:
  - Letzter Sync pro Space (Zeitstempel)
  - "Voll-Ingest" Button (mit Bestätigung)
  - Anzahl bekannter Seiten pro Space

### Admin-Panel (aufklappbar)

```
+-------------------------------------------------------------+
|  ⚙️ Admin                                            [✕]    |
|-------------------------------------------------------------|
|  Space ds — Letzter Sync: 22.03.2026 09:45                  |
|             12 Seiten indexiert                               |
|             [Sync] [Voll-Ingest]                             |
|-------------------------------------------------------------|
|  Space OP — Letzter Sync: 22.03.2026 09:45                  |
|             1 Seite indexiert                                 |
|             [Sync] [Voll-Ingest]                             |
|-------------------------------------------------------------|
|  [Alle Spaces syncen]    [Alle Spaces neu ingesten]          |
+-------------------------------------------------------------+
```

### Fortschritts-Feedback

Während ein Sync/Ingest läuft:

1. Sync-Button wird zum Spinner: `🔄 → ⏳ Sync läuft...`
2. Nach Abschluss: Toast-Benachrichtigung mit Ergebnis

```
✅ Sync abgeschlossen: 3 aktualisiert, 1 gelöscht, 12 Chunks (2s)
```

Bei Fehler:

```
⚠️ Sync mit Fehlern: 2 aktualisiert, 1 Fehler (Details im Log)
```

Toast verschwindet nach 5 Sekunden oder per Klick.

---

## Technische Umsetzung

### Neue JavaScript-Funktionen (app.js)

```javascript
/** Sync-Status aller Spaces laden */
async function loadSyncStatus() → GET /api/admin/sync/status

/** Inkrementellen Sync für alle Spaces auslösen */
async function triggerSync() → POST /api/admin/sync

/** Inkrementellen Sync für einen Space auslösen */
async function triggerSpaceSync(spaceKey) → POST /api/admin/sync/{spaceKey}

/** Voll-Ingest für alle Spaces auslösen */
async function triggerIngest() → POST /api/admin/ingest

/** Voll-Ingest für einen Space auslösen */
async function triggerSpaceIngest(spaceKey) → POST /api/admin/ingest/{spaceKey}

/** Toast-Benachrichtigung anzeigen */
function showToast(message, type)  // type: 'success' | 'error' | 'info'
```

### Neue HTML-Elemente

```html
<!-- Im Header -->
<button id="sync-btn" class="sync-btn" title="Confluence-Daten synchronisieren">
    🔄 Sync
</button>
<button id="admin-toggle" class="admin-toggle-btn" title="Admin-Panel">⚙️</button>

<!-- Admin Panel (zunächst hidden) -->
<div id="admin-panel" class="admin-panel hidden">
    <div class="admin-header">
        <span>Admin</span>
        <button id="admin-close">✕</button>
    </div>
    <div id="admin-spaces"></div>
    <div class="admin-actions">
        <button id="sync-all-btn">Alle Spaces syncen</button>
        <button id="ingest-all-btn" class="danger-btn">Alle Spaces neu ingesten</button>
    </div>
</div>

<!-- Toast Container -->
<div id="toast-container" class="toast-container"></div>
```

### Neue CSS-Klassen

- `.sync-btn` — Sync-Button im Header (mit Spinner-State)
- `.admin-toggle-btn` — Settings-Icon
- `.admin-panel` — Aufklappbares Panel (absolut positioniert)
- `.admin-panel.hidden` — Versteckt
- `.space-status` — Sync-Status pro Space
- `.danger-btn` — Roter Button für Voll-Ingest (mit Hover-Warnung)
- `.toast-container` — Fixed unten rechts für Benachrichtigungen
- `.toast` — Einzelne Toast-Nachricht mit Typen (success/error/info)
- `.toast.fade-out` — Ausblend-Animation

### Bestätigungsdialog für Voll-Ingest

Voll-Ingest löscht und ersetzt alle Chunks. Dafür einen `confirm()`-Dialog:

```javascript
function triggerIngest() {
    if (!confirm('Alle Spaces komplett neu ingesten? Das kann mehrere Minuten dauern.')) {
        return;
    }
    // ...
}
```

### Sync-Status beim Start laden

Beim Laden der Seite wird automatisch der Sync-Status abgefragt:

```javascript
document.addEventListener('DOMContentLoaded', () => {
    loadSpaces();
    loadSyncStatus();  // NEU
    chatForm.addEventListener('submit', handleSubmit);
});
```

---

## Backend-Änderungen

Keine Backend-Änderungen nötig. Alle benötigten Endpunkte existieren bereits:

- `POST /api/admin/sync` — Inkrementeller Sync
- `POST /api/admin/ingest` — Voll-Ingest
- `GET /api/admin/sync/status` — Status-Abfrage

Die Response-Typen (`SyncResult`, `IngestionResult`) enthalten bereits alle nötigen Informationen für das Fortschritts-Feedback.

---

## Akzeptanzkriterien

- [ ] Sync-Button im Header löst inkrementellen Sync aus
- [ ] Während Sync/Ingest: Button zeigt Lade-Zustand
- [ ] Nach Sync/Ingest: Toast mit Ergebnis (Seiten aktualisiert/gelöscht, Chunks, Dauer)
- [ ] Bei Fehlern: Toast mit Fehlermeldung
- [ ] Admin-Panel zeigt letzten Sync-Zeitstempel pro Space
- [ ] Admin-Panel zeigt Anzahl bekannter Seiten pro Space
- [ ] Per-Space Sync und Ingest möglich
- [ ] "Alle Spaces syncen" und "Alle Spaces neu ingesten" Buttons
- [ ] Voll-Ingest erfordert Bestätigung (confirm-Dialog)
- [ ] Chat bleibt während Sync/Ingest nutzbar
- [ ] Sync-Status wird beim Laden der Seite automatisch abgefragt
