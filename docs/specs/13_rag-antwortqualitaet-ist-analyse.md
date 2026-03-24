# Ist-Analyse: RAG-Antwortqualität

**Datum:** 2026-03-24
**Status:** Analyse
**Betroffene Komponenten:** QueryService, ChunkingService, IngestionService, ConfluenceHtmlConverter

---

## 1. Problembeschreibung

Bei Benutzeranfragen wie z.B. "Was steht in Spec 07?" liefert das System falsche oder irrelevante Antworten — etwa Informationen über "Issue 07" statt über "Spec 07". Die korrekte Information wird im Similarity Search nicht gefunden, obwohl sie im Confluence-Space vorhanden und ingested ist.

---

## 2. Aktueller Query-Flow

```
User-Frage → Embedding (nomic-embed-text) → Qdrant Similarity Search → Top-5 Chunks → Kontext-Aufbau → LLM (gemma3:4b) → Antwort
```

### 2.1 Similarity Search (QueryService.java:86-104)

| Parameter | Wert | Konfigurierbar |
|-----------|------|----------------|
| Top-K | 5 | Nein (hardcoded) |
| Similarity Threshold | 0.5 | Nein (hardcoded) |
| Embedding Model | nomic-embed-text | Ja (ENV) |
| Filter | spaceKey (optional) | Ja |

### 2.2 Kontext-Aufbau (QueryService.java:106-113)

Chunks werden 1:1 konkateniert, ohne Gewichtung oder Re-Ranking:

```
--- Quelle: {pageTitle} (Space: {spaceKey}) ---
{chunkText}
```

### 2.3 System Prompt (QueryService.java:25-38)

Einfacher Prompt ohne Instruktionen zur Quellenpriorisierung, Titelgewichtung oder Disambiguation.

### 2.4 LLM-Konfiguration

| Parameter | Wert |
|-----------|------|
| Modell | gemma3:4b |
| Temperatur | 0.3 |

---

## 3. Aktueller Ingestion-Flow

```
Confluence Page → HTML → ConfluenceHtmlConverter → Plain Text → TokenTextSplitter → Chunks + Metadata → Qdrant
```

### 3.1 Chunking (ChunkingService.java:26-36)

| Parameter | Wert | Konfigurierbar |
|-----------|------|----------------|
| Chunk Size | 500 Tokens | Ja (ENV) |
| Chunk Overlap | 50 Tokens (konfiguriert aber **nicht genutzt**) | — |
| Max Chunk Chars | 2000 | Nein (hardcoded) |
| Min Chunk Size Chars | 50 | Nein |
| Max Chunks per Doc | 500 | Nein |

**Kritisch:** Der `chunk-overlap` Parameter aus `application.yml` wird im `TokenTextSplitter`-Konstruktor **nicht übergeben**. Es gibt kein Overlap zwischen Chunks.

### 3.2 Metadata pro Chunk

```
pageId, spaceKey, pageTitle, pageUrl, labels, author, lastModified, ancestors, chunkType
```

### 3.3 HTML-Konvertierung (ConfluenceHtmlConverter.java)

- Wandelt HTML → Markdown-ähnlichen Plain Text
- Überschriften: `#`, `##`, etc.
- Listen, Tabellen, Code-Blöcke
- Confluence-Macros: Admonitions, PlantUML, etc.
- Ignoriert: Bilder, TOC, Children-Macros

---

## 4. Identifizierte Schwachstellen

### 4.1 KRITISCH: Kein Chunk-Overlap

Der `chunkOverlap`-Parameter (50 Tokens) ist konfiguriert aber wird **nie an den TokenTextSplitter übergeben**. Dadurch:
- Information an Chunk-Grenzen geht verloren
- Zusammenhängende Absätze werden hart getrennt
- Kontext innerhalb eines Chunks ist unvollständig

### 4.2 KRITISCH: Kein Seitentitel im Chunk-Text

Der Seitentitel (`pageTitle`) wird nur als **Metadata** gespeichert, aber **nicht in den Chunk-Text eingebettet**. Da die Similarity Search auf dem Embedding des Chunk-Textes basiert, werden Suchanfragen wie "Spec 07" nur dann gemacht, wenn der Chunk-Text zufällig "Spec 07" enthält.

**Beispiel:** Eine Seite mit Titel "Spec 07 — Authentifizierung" enthält im Body-Text nur technische Details ohne "Spec 07". Der Chunk matched nicht auf die Anfrage "Was steht in Spec 07?", weil der Titel nicht im Embedding-Text enthalten ist.

### 4.3 KRITISCH: Keine Ancestor-/Breadcrumb-Information im Chunk-Text

Ancestors (z.B. "Projektdoku > Specs > Spec 07") sind nur Metadata, nicht im Chunk-Text. Hierarchische Zuordnungen wie "Spec" vs. "Issue" können bei der Vektorsuche nicht unterschieden werden.

### 4.4 HOCH: Top-K zu niedrig und hardcoded

Mit `topK=5` und keiner Relevanz-Sortierung innerhalb der Top-5 ist die Wahrscheinlichkeit hoch, dass relevante Chunks nicht abgerufen werden. Besonders bei:
- Seiten mit vielen Chunks (nur 5 von möglicherweise 50+ werden zurückgegeben)
- Ambigen Anfragen (mehrere Seiten matchen ähnlich gut)

### 4.5 HOCH: Kein Re-Ranking

Die Top-5 Ergebnisse aus der Vektorsuche werden 1:1 als Kontext übergeben. Es gibt:
- Kein Cross-Encoder Re-Ranking
- Keine Keyword-basierte Nachfilterung
- Keine Deduplizierung auf Inhaltsebene (nur auf URL-Ebene für Sources)

### 4.6 HOCH: Similarity Threshold zu niedrig

Ein Threshold von 0.5 lässt viele schwach relevante Chunks durch, die den Kontext verwässern und das LLM zu falschen Antworten verleiten.

### 4.7 MITTEL: Labels nicht im Chunk-Text

Labels (z.B. "spec", "requirement", "phase-2") sind nur Metadata. Sie könnten zur Disambiguierung beitragen, wenn sie im Embedding-Text wären.

### 4.8 MITTEL: System Prompt ohne Disambiguation-Instruktionen

Der Prompt sagt dem LLM nicht, wie es mit mehrdeutigen oder schwach relevanten Kontexten umgehen soll. Keine Instruktion, den Seitentitel als Hauptkriterium für Relevanz zu nutzen.

### 4.9 NIEDRIG: Kleines Chat-Modell (gemma3:4b)

Das 4B-Parameter-Modell hat limitierte Reasoning-Fähigkeit für komplexe Kontextzuordnungen. Bei ambigen Kontexten wählt es oft den falschen Bezug.

### 4.10 NIEDRIG: Chunk-Typ nicht im Kontext differenziert

Im Kontext-Aufbau wird nicht zwischen PAGE-, COMMENT- und ATTACHMENT-Chunks unterschieden. Kommentare und Anhänge können den Kontext mit weniger relevanter Information füllen.

---

## 5. Root-Cause-Analyse: Beispiel "Spec 07"

Wenn ein User nach "Spec 07" fragt:

1. **Embedding:** "Spec 07" wird als Vektor encoded
2. **Similarity Search:** Suche nach ähnlichen Vektoren in Qdrant
3. **Problem:** Der Chunk-Text der Seite "Spec 07" enthält technische Details, aber nicht den Titel "Spec 07". Der Titel ist nur in den Metadaten.
4. **Ergebnis:** Ein Chunk von "Issue 07" oder einer anderen Seite, die zufällig "07" oder ähnliche Begriffe im Body enthält, hat ein höheres Cosine-Similarity als der richtige Chunk
5. **Kontext:** Das LLM erhält den falschen Kontext und generiert eine falsche Antwort

**Kernproblem:** Titel, Labels und Breadcrumbs fließen nicht ins Embedding ein → semantische Suche kann dokumentenbezogene Anfragen nicht korrekt zuordnen.

---

## 6. Zusammenfassung der Schwachstellen

| # | Schwachstelle | Schwere | Aufwand |
|---|--------------|---------|---------|
| 1 | Kein Chunk-Overlap (Bug) | KRITISCH | Gering |
| 2 | Seitentitel nicht im Chunk-Text | KRITISCH | Gering |
| 3 | Ancestors nicht im Chunk-Text | KRITISCH | Gering |
| 4 | Top-K zu niedrig, hardcoded | HOCH | Gering |
| 5 | Kein Re-Ranking | HOCH | Mittel |
| 6 | Similarity Threshold zu niedrig | HOCH | Gering |
| 7 | Labels nicht im Chunk-Text | MITTEL | Gering |
| 8 | System Prompt ohne Disambiguation | MITTEL | Gering |
| 9 | Kleines Chat-Modell | NIEDRIG | — |
| 10 | Chunk-Typ nicht im Kontext | NIEDRIG | Gering |
