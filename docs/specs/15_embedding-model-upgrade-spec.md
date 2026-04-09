# Spec: Embedding-Model-Upgrade (bge-m3)

**Datum:** 2026-04-09
**Status:** Spec
**Betroffene Dateien:**
- `src/main/resources/application.yml`
- `src/main/java/at/openaustria/confluencerag/ingestion/IngestionService.java`
- `docker-compose.yml` (optional, für Ollama-Modell-Preload)
- `CLAUDE.md` (Doku)

---

## 1. Ziel

Das aktuelle Embedding-Modell `nomic-embed-text` ist Englisch-optimiert und zeigt auf deutschem Fachvokabular schwache Trennschärfe: Cosine-Scores aller relevanten und irrelevanten Chunks liegen in einem engen Band (~0.55–0.70). Das führt dazu, dass irrelevante Quellen in die Top-K-Ergebnisse gelangen.

**Ziel:** Wechsel auf ein multilinguales State-of-the-Art-Embedding-Modell (`bge-m3`) um die semantische Trennschärfe auf deutschem Content deutlich zu verbessern.

---

## 2. Warum `bge-m3`?

| Kriterium | nomic-embed-text (aktuell) | bge-m3 (neu) |
|-----------|----------------------------|--------------|
| Sprachen | primär Englisch | 100+ Sprachen, speziell multilingual trainiert |
| Dimension | 768 | 1024 |
| Max Tokens | 8192 | 8192 |
| MTEB DE Score | mittelmäßig | top Tier |
| Ollama-Support | ✅ | ✅ (`ollama pull bge-m3`) |
| Lizenz | Apache 2.0 | MIT |
| Modell-Größe | ~275 MB | ~1.2 GB |

Alternativen, die im Rahmen der Analyse verworfen wurden:
- **`mxbai-embed-large`** (1024 dim) — gut, aber `bge-m3` ist in Benchmarks für DE leicht besser
- **`snowflake-arctic-embed2`** — vergleichbar, weniger verbreitet in Ollama-Community
- **OpenAI `text-embedding-3-large`** — nicht self-hosted → ausgeschlossen

---

## 3. Änderungen

### 3.1 `application.yml`

**Datei:** `src/main/resources/application.yml`

```yaml
spring:
  ai:
    ollama:
      embedding:
        model: ${OLLAMA_EMBEDDING_MODEL:bge-m3}   # war: nomic-embed-text
```

Der Default wird auf `bge-m3` geändert. Die ENV-Variable `OLLAMA_EMBEDDING_MODEL` erlaubt Override.

### 3.2 `IngestionService.java` — Vector-Dimension konfigurierbar machen

Aktuell ist die Dimension hardcoded:

```java
private static final int VECTOR_DIMENSION = 768;
```

Das muss konfigurierbar werden, da `bge-m3` 1024 Dimensionen hat.

**Neue ConfigProperty in `IngestionProperties.java`:**

```java
@ConfigurationProperties(prefix = "ingestion")
public record IngestionProperties(
    int chunkSize,
    int chunkOverlap,
    int batchSize,
    int parallelThreads,
    int chunkTimeout,
    int vectorDimension   // NEU
) {}
```

**`application.yml` ergänzen:**

```yaml
ingestion:
  vector-dimension: ${VECTOR_DIMENSION:1024}
```

**`IngestionService.java` anpassen:**

```java
private final int vectorDimension;

public IngestionService(..., IngestionProperties ingestionProperties) {
    // ...
    this.vectorDimension = ingestionProperties.vectorDimension();
}

private void clearCollection() {
    // ...
    qdrantClient.createCollectionAsync(collectionName,
            VectorParams.newBuilder()
                    .setSize(vectorDimension)    // war: VECTOR_DIMENSION
                    .setDistance(Distance.Cosine)
                    .build()
    ).get(30, TimeUnit.SECONDS);
}
```

Die Konstante `VECTOR_DIMENSION` wird entfernt.

### 3.3 Similarity-Threshold neu kalibrieren

`bge-m3` liefert andere Score-Verteilungen als `nomic-embed-text`. Der aktuelle Default von `0.45` muss evaluiert und ggf. angepasst werden.

Da die absoluten Scores nicht vorhersehbar sind, bleibt der Default zunächst bei `0.45`. Nach einem Test-Ingest wird empirisch evaluiert und angepasst. Die Anpassung erfolgt in einem Follow-up-Commit.

### 3.4 Docker-Compose — Modell vorab pullen (optional)

Damit der erste Start nicht durch den Modell-Download blockiert:

```yaml
# docker-compose.yml (optional)
services:
  ollama:
    # ...
    entrypoint: ["/bin/sh", "-c", "ollama serve & sleep 5 && ollama pull bge-m3 && wait"]
```

**Alternative (präferiert):** Im README/CLAUDE.md-Hinweis dokumentieren, dass vor dem ersten Start `docker exec <ollama> ollama pull bge-m3` ausgeführt werden muss.

### 3.5 Dokumentation (CLAUDE.md)

- Default-Embedding-Modell von `nomic-embed-text` auf `bge-m3` aktualisieren
- Hinweis auf Vector-Dimension 1024 (nicht mehr 768)
- Setup-Schritt: `ollama pull bge-m3` vor erstem Ingest

---

## 4. Migrations-Hinweis

**Breaking Change:** Die Qdrant-Collection muss komplett neu angelegt werden, weil sich die Vektor-Dimension ändert (768 → 1024). Ein Upgrade ohne Re-Ingest ist nicht möglich.

**Prozess für bestehende Deployments:**
1. App stoppen
2. `bge-m3` in Ollama pullen
3. `OLLAMA_EMBEDDING_MODEL=bge-m3` und `VECTOR_DIMENSION=1024` setzen (falls nicht Default)
4. Alte Qdrant-Collection löschen (wird durch `POST /api/admin/ingest` automatisch gelöscht und neu angelegt)
5. App starten
6. Voll-Ingest triggern via Admin-Panel

---

## 5. Akzeptanzkriterien

- [ ] Default-Embedding-Modell ist `bge-m3`
- [ ] Vector-Dimension ist konfigurierbar über `ingestion.vector-dimension`
- [ ] Default-Dimension ist `1024`
- [ ] `VECTOR_DIMENSION`-Konstante in `IngestionService` entfernt
- [ ] Voll-Ingest auf `ds` oder `OP` Space läuft durch
- [ ] Qdrant-Collection wird mit korrekter Dimension (1024) angelegt
- [ ] Alle Tests grün
- [ ] `CLAUDE.md` aktualisiert (Modell-Name + Setup-Hinweis)

---

## 6. Test-Plan

### 6.1 Unit-Tests
- Bestehende Tests müssen grün bleiben (keine Logik-Änderung)
- Test: `IngestionProperties` default-Wert für `vectorDimension` = 1024

### 6.2 Manuelles Testen
1. `ollama pull bge-m3`
2. Qdrant + Ollama + App starten
3. Voll-Ingest über einen kleinen Test-Space
4. Prüfen: Collection hat Dimension 1024 (`curl http://localhost:6333/collections/confluence-chunks`)
5. Query-Tests mit bekannten problematischen Fragen aus der Vergangenheit
6. Prüfen: Top-K-Quellen sind relevanter als vorher

### 6.3 Qualitäts-Test (optional)
- 5-10 Fragen definieren, für die die erwarteten Quellen bekannt sind
- Mit altem Modell (nomic) vs. neuem Modell (bge-m3) vergleichen
- Precision@5 / Recall@5 messen

---

## 7. Nicht im Scope

- Hybrid Search (Dense + Sparse BM25) — separates Follow-up-Issue
- Cross-Encoder Reranker — separates Issue (Spec 16)
- Query-Rewriting / HyDE — Follow-up
- Re-Kalibrierung des Similarity-Thresholds — wird nach Test-Ingest in separatem Commit vorgenommen

---

## 8. Risiken

| Risiko | Mitigation |
|--------|-----------|
| `bge-m3` ist langsamer als `nomic-embed-text` (größeres Modell) | Ingestion-Performance messen; ggf. `INGESTION_PARALLEL_THREADS` erhöhen |
| Threshold 0.45 passt nicht mehr zu bge-m3-Scores | Nach Test-Ingest empirisch anpassen (Follow-up-Commit) |
| Erster Ingest blockiert durch Modell-Download | Im Setup-Hinweis dokumentieren, Modell vorab pullen |
| 1.2 GB Modell-Größe auf Disk | Dokumentieren; ist für Self-Hosted akzeptabel |
