# Scripts

Utility-Skripte für Entwicklung, Testing und Diagnose. Nicht Teil der produktiven App, aber ins Repo eingecheckt damit sie wiederverwendbar sind.

| Script | Zweck |
|--------|-------|
| `generate-test-pages.py` | Erzeugt synthetische Test-Seiten im lokalen Confluence-Container |
| `retrieval-quality-check.py` | Führt Test-Queries gegen die Qdrant-Collection aus und prüft Scores/Top-Matches |

---

## `retrieval-quality-check.py`

Diagnose-Script das eine feste Liste von Test-Queries gegen die laufende Qdrant-Collection schickt und die Top-K Treffer mit Similarity-Scores ausgibt. Hilft beim Vergleichen von Embedding-Modellen, Kalibrieren des Similarity-Thresholds und Spot-Checks nach Ingest-Änderungen.

### Voraussetzungen

- Ollama läuft auf `OLLAMA_URL` (default `http://localhost:11434`) und hat das Embedding-Modell verfügbar
- Qdrant läuft auf `QDRANT_URL` (default `http://localhost:6333`) und hat die Collection ingestet
- Python 3 (nur stdlib, keine extra Dependencies)

### Benutzung

```bash
# Defaults: bge-m3 gegen localhost
python3 scripts/retrieval-quality-check.py

# Anderes Modell zum Vergleich (z.B. alter Stand mit nomic-embed-text)
EMBED_MODEL=nomic-embed-text python3 scripts/retrieval-quality-check.py

# Anderer Qdrant-Host / andere Collection
QDRANT_URL=http://qdrant.prod:6333 COLLECTION=my-collection \
  python3 scripts/retrieval-quality-check.py

# Ad-hoc Einzel-Query von der Kommandozeile
python3 scripts/retrieval-quality-check.py "Wie funktioniert X?"
```

### Environment-Variablen

| Variable | Default | Bedeutung |
|----------|---------|-----------|
| `OLLAMA_URL` | `http://localhost:11434` | Ollama-Endpoint für Embeddings |
| `QDRANT_URL` | `http://localhost:6333` | Qdrant-REST-Endpoint |
| `COLLECTION` | `confluence-chunks` | Qdrant-Collection-Name |
| `EMBED_MODEL` | `bge-m3` | Ollama-Embedding-Modell |
| `TOP_K` | `10` | Anzahl der zurückgegebenen Treffer pro Query |

### Test-Queries anpassen

Die Queries sind oben im Script als `QUERIES`-Liste hart kodiert — zu jedem Query gehört ein Kommentar, was der erwartete Top-Treffer wäre. Für eigene Tests die Liste direkt im Script anpassen. Die Queries sind auf die Test-Daten der `openaustria-confluence-rag` Specs zugeschnitten und dienen als Beispiel.

### Interpretation der Ausgabe

Pro Query:
- `top1` / `top10` — höchster und niedrigster Score in den Top-K
- `spread` — Differenz (zeigt wie "eng" das Score-Band ist; bei Single-Domain-Corpora oft problematisch eng)
- Numerierte Treffer-Liste mit Score, Space-Key und Titel

**Faustregeln für bge-m3 auf deutschem Content:**
- Relevanter Treffer: `> 0.50`
- Off-topic: meist `< 0.45` (deshalb der Default-Threshold)
- Spread unter `0.05`: Keyword-Heuristik oder Reranker helfen am meisten

### Wann verwenden

- Vor/nach Embedding-Modell-Wechsel → Before/After-Vergleich
- Bei subjektivem Eindruck "die Quellen sind schlecht" → konkrete Daten holen
- Nach Threshold-Änderungen in `application.yml`
- Beim Evaluieren neuer Query-Formulierungen

---

## `generate-test-pages.py`

Siehe Kommentar im Script.
