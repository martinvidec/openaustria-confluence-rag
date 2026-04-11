# Scripts

Utility-Skripte für Entwicklung, Testing und Diagnose. Nicht Teil der produktiven App, aber ins Repo eingecheckt damit sie wiederverwendbar sind.

| Script | Zweck |
|--------|-------|
| `generate-test-pages.py` | Erzeugt synthetische Test-Seiten im lokalen Confluence-Container |
| `retrieval-quality-check.py` | Führt Test-Queries gegen die Qdrant-Collection aus, vergleicht Vektor-Suche mit/ohne LLM-Listwise-Rerank |

---

## `retrieval-quality-check.py`

Diagnose-Script das eine feste Liste von Test-Queries gegen die laufende Qdrant-Collection schickt und die Top-K Treffer mit Similarity-Scores ausgibt. Unterstützt drei Modi:

- **`vector`** — nur die rohe Vektor-Suche (das "vor dem Reranker"-Baseline)
- **`rerank`** — nur die Top-K nach LLM-Listwise-Rerank (das "nach dem Reranker"-View)
- **`both`** (Default) — Side-by-Side mit Rang-Änderungs-Pfeilen

Die Rerank-Logik im Script ist eine 1:1-Reproduktion des Java `LlmListwiseReranker` (gleicher Prompt, gleicher JSON-Parser). Wenn der Java-Code geändert wird, muss das Script mit-aktualisiert werden — die Synchronisation ist manuell aber bewusst, weil das Script ein eigenständiges Diagnose-Tool ist und die App nicht laufen muss.

### Voraussetzungen

- Ollama läuft auf `OLLAMA_URL` (default `http://localhost:11434`) und hat das Embedding-Modell verfügbar
- Qdrant läuft auf `QDRANT_URL` (default `http://localhost:6333`) und hat die Collection ingestet
- Für `MODE=rerank` und `MODE=both`: zusätzlich das Rerank-LLM (default `qwen3:0.6b`)
- Python 3 (nur stdlib, keine extra Dependencies)

### Benutzung

```bash
# Defaults: bge-m3 + qwen3:0.6b Rerank, Side-by-Side
python3 scripts/retrieval-quality-check.py

# Nur Vektor-Baseline (kein Rerank-Call → spart Latenz)
MODE=vector python3 scripts/retrieval-quality-check.py

# Nur Reranker-Output
MODE=rerank python3 scripts/retrieval-quality-check.py

# Mit anderem Rerank-Modell vergleichen
RERANK_MODEL=gemma3:4b python3 scripts/retrieval-quality-check.py

# Mit anderem Embedding-Modell (alter Stand: nomic-embed-text)
EMBED_MODEL=nomic-embed-text python3 scripts/retrieval-quality-check.py

# Anderer Qdrant-Host / andere Collection
QDRANT_URL=http://qdrant.prod:6333 COLLECTION=my-collection \
  python3 scripts/retrieval-quality-check.py

# Ad-hoc Einzel-Query
python3 scripts/retrieval-quality-check.py "Wie funktioniert X?"
```

### Environment-Variablen

| Variable | Default | Bedeutung |
|----------|---------|-----------|
| `OLLAMA_URL` | `http://localhost:11434` | Ollama-Endpoint für Embeddings + Rerank |
| `QDRANT_URL` | `http://localhost:6333` | Qdrant-REST-Endpoint |
| `COLLECTION` | `confluence-chunks` | Qdrant-Collection-Name |
| `EMBED_MODEL` | `bge-m3` | Ollama-Embedding-Modell |
| `TOP_K` | `5` | Anzahl der zurückgegebenen Treffer pro Query |
| `MODE` | `both` | `vector` \| `rerank` \| `both` |
| `RERANK_MODEL` | `qwen3:0.6b` | Ollama-Modell für den Listwise-Rerank |
| `CANDIDATE_COUNT` | `15` | Anzahl Kandidaten die in den Reranker gehen |
| `MAX_CHUNK_CHARS` | `500` | Truncation pro Chunk im Rerank-Prompt |
| `RERANK_TIMEOUT` | `60` | Timeout für den Rerank-LLM-Call (Sekunden) |

### Test-Queries anpassen

Die Queries sind oben im Script als `QUERIES`-Liste hart kodiert — zu jedem Query gehört ein Kommentar, was der erwartete Top-Treffer wäre. Für eigene Tests die Liste direkt im Script anpassen. Die Queries sind auf die Test-Daten der `openaustria-confluence-rag` Specs zugeschnitten und dienen als Beispiel.

### Interpretation der Ausgabe

**MODE=vector** und **MODE=rerank** zeigen pro Query:
- `top1` / `top<K>` — höchster und niedrigster Score in den Top-K
- `spread` — Differenz (zeigt wie "eng" das Score-Band ist; bei Single-Domain-Corpora oft problematisch eng)
- Numerierte Treffer-Liste mit Score, Space-Key und Titel

**MODE=both** zeigt zusätzlich die Bewegung jedes Treffers durch den Rerank:
- `↑ from #N` — der Reranker hat das Dokument nach oben geschoben
- `↓ from #N` — der Reranker hat es nach unten geschoben
- `= unchanged` — gleiche Position
- `★ promoted (was #N)` — war außerhalb der Vector-Top-K und wurde reingepromoted

So ist auf einen Blick zu sehen, ob und wo der Reranker tatsächlich Werte schafft.

**Faustregeln für bge-m3 auf deutschem Content:**
- Relevanter Treffer: `> 0.50`
- Off-topic: meist `< 0.45` (deshalb der Default-Threshold)
- Spread unter `0.05`: Reranker-Effekt am wahrscheinlichsten

### Wann verwenden

- Vor/nach Embedding-Modell-Wechsel → Before/After-Vergleich (`EMBED_MODEL` umsetzen)
- Vor/nach Reranker-Aktivierung → `MODE=both` zeigt Rang-Änderungen
- Vergleich verschiedener Rerank-Modelle (`RERANK_MODEL=qwen3:0.6b` vs `gemma3:4b` vs `qwen3:1.7b`)
- Bei subjektivem Eindruck "die Quellen sind schlecht" → konkrete Daten holen
- Nach Threshold-Änderungen in `application.yml`
- Beim Evaluieren neuer Query-Formulierungen

### Sync mit dem Java-Reranker

Das Script repliziert intern die Logik aus `LlmListwiseReranker.java`:
- Gleicher Prompt-Aufbau (Listwise mit nummerierten Chunks)
- Gleiche Truncation auf `MAX_CHUNK_CHARS`
- Gleicher Regex-basierter JSON-Array-Parser
- Gleiche Padding-Logik wenn der LLM weniger als `top_k` Indices liefert

Wenn der Java-Code geändert wird, muss `_build_rerank_prompt` und `_parse_and_pad` im Script entsprechend angepasst werden. Die Tests in `LlmListwiseRerankerTest.java` sind die Quelle der Wahrheit für das erwartete Verhalten.

---

## `generate-test-pages.py`

Siehe Kommentar im Script.
