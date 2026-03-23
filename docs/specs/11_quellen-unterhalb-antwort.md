# Spec 11: Quellen unterhalb der Antwort zusammengefasst

**Issue:** #18
**Phase:** Post-MVP Enhancement
**Abhängigkeiten:** #13, #14, #17 (Chat-Frontend muss stehen)

---

## Analyse Ist-Stand

### Aktuelles Verhalten

Quellen werden an **zwei Stellen** angezeigt:

1. **Im LLM-Antworttext** — Der System-Prompt enthält die Anweisung: *"Nenne am Ende deiner Antwort die relevanten Quellen mit Seitentitel."* Dadurch nennt das LLM die Quellen als Fließtext am Ende der Antwort (z.B. "Quellen: API-Dokumentation, REST Guidelines").

2. **Als separate UI-Komponente** — Nach dem Streaming wird ein `<div class="sources">` an die Assistant-Message angehängt. Jede Quelle ist ein klickbarer Link mit Space-Badge.

### Problem

- **Redundanz**: Die Quellen erscheinen doppelt — einmal als Text im LLM-Output und einmal als klickbare Links darunter.
- **Inkonsistenz**: Die LLM-generierten Quellennennungen im Text sind nicht klickbar und haben kein einheitliches Format.
- **Platzverbrauch**: Beide Quellen-Darstellungen zusammen nehmen unnötig viel Platz ein.

### Gewünschtes Verhalten

Die Quellen sollen **nur einmal** angezeigt werden — als dedizierte, klickbare Quellen-Sektion **unterhalb der Antwort**, nicht als Teil des LLM-Textes.

### Technischer Ist-Stand

**Backend (QueryService.java):**
- System-Prompt Zeile: `"Nenne am Ende deiner Antwort die relevanten Quellen mit Seitentitel."`
- `extractSources()`: Extrahiert Quellen aus Qdrant-Chunk-Metadaten, dedupliziert nach URL
- `Source` Record: `title`, `url`, `spaceKey`

**Backend (ChatController.java):**
- Streaming: Zuerst `token`-Events, dann ein `sources`-Event, dann `done`
- Quellen werden im `sources`-Event als JSON-Array gesendet — unabhängig vom LLM-Text

**Frontend (app.js):**
- `renderSources(messageEl, sources)`: Hängt `<div class="sources">` an die Message an
- Quellen als `<ul>` mit Links + Space-Badges
- Wird nach Streaming-Ende aufgerufen

**Frontend (app.css):**
- `.sources`: `margin-top: 12px`, `border-top: 1px solid`, unter der Message
- `.sources a`: Blau, klickbar, öffnet im neuen Tab

---

## Lösung

### 1. System-Prompt anpassen (Backend)

Die Anweisung an das LLM, Quellen im Text zu nennen, wird entfernt:

**Vorher:**
```
- Nenne am Ende deiner Antwort die relevanten Quellen mit Seitentitel.
```

**Nachher:**
```
- Nenne KEINE Quellen in deiner Antwort. Die Quellen werden automatisch angezeigt.
```

Damit enthält der LLM-Output nur noch die eigentliche Antwort.

### 2. Frontend: Quellen-Darstellung verbessern

Die bestehende `renderSources()`-Funktion bleibt im Kern erhalten, wird aber visuell aufgewertet:

- Quellen-Box mit dezenter Hintergrundfarbe statt nur Border-Top
- Confluence-Icon oder Link-Icon vor jedem Eintrag
- Kompaktere Darstellung bei vielen Quellen

### Geändertes Rendering

```
┌─────────────────────────────────────────────┐
│  [Bot]  Die REST API unterstützt folgende   │
│         Endpunkte: GET /api/users liefert   │
│         eine Liste aller Benutzer...        │
│                                             │
│  ┌─ Quellen ──────────────────────────────┐ │
│  │  ↗ API-Dokumentation          [DEV]    │ │
│  │  ↗ REST Guidelines            [ARCH]   │ │
│  │  ↗ Getting Started            [DEV]    │ │
│  └────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

### Geänderter CSS-Stil

```css
.sources {
    margin-top: 12px;
    padding: 10px 14px;
    background: var(--source-bg);       /* #f1f5f9 — dezenter Hintergrund */
    border-radius: 8px;
    border: none;                        /* Kein border-top mehr */
}
```

---

## Betroffene Dateien

| Datei | Änderung |
|---|---|
| `QueryService.java` | System-Prompt: Quellen-Anweisung entfernen |
| `app.js` | `renderSources()`: Keine funktionale Änderung, ggf. Icon-Prefix |
| `app.css` | `.sources`: Hintergrundfarbe statt Border, abgerundete Ecken |

---

## Akzeptanzkriterien

- [ ] LLM-Antwort enthält keine Quellennennungen mehr im Text
- [ ] Quellen werden als dedizierte Box unterhalb der Antwort angezeigt
- [ ] Jede Quelle ist ein klickbarer Link zu Confluence
- [ ] Space-Badge weiterhin sichtbar
- [ ] Quellen-Box hat dezenten Hintergrund und abgerundete Ecken
- [ ] Bei 0 Quellen wird keine Quellen-Box angezeigt
- [ ] Streaming-Verhalten unverändert (Quellen erscheinen nach letztem Token)
