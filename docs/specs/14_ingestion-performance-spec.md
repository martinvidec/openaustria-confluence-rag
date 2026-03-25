# Spec: Ingestion-Performance-Optimierung

**Datum:** 2026-03-25
**Status:** Spec
**Analyse:** [14_ingestion-performance-ist-analyse.md](14_ingestion-performance-ist-analyse.md)
**Betroffene Dateien:**
- `src/main/java/at/openaustria/confluencerag/config/IngestionProperties.java`
- `src/main/java/at/openaustria/confluencerag/ingestion/IngestionService.java`
- `src/main/resources/application.yml`

---

## 1. Ziel

Ingestion-Dauer für ~3300 Chunks auf langsamer Hardware von ~2h auf ~30-60 min reduzieren — **ohne Qualitäts- oder Informationsverlust**. Chunks, Embeddings und Suchergebnisse bleiben identisch.

---

## 2. Änderungen

### 2.1 IngestionProperties erweitern

**Datei:** `config/IngestionProperties.java`

Neue Felder im Record:

| Feld | Typ | Default | Env-Variable | Beschreibung |
|------|-----|---------|-------------|-------------|
| `parallelThreads` | int | 2 | `INGESTION_PARALLEL_THREADS` | Anzahl paralleler Embedding-Threads |
| `chunkTimeout` | int | 300 | `INGESTION_CHUNK_TIMEOUT` | Timeout in Sekunden pro Batch |

```java
@ConfigurationProperties(prefix = "ingestion")
public record IngestionProperties(
    int chunkSize,
    int chunkOverlap,
    int batchSize,
    int parallelThreads,
    int chunkTimeout
) {}
```

### 2.2 application.yml anpassen

**Datei:** `src/main/resources/application.yml`

```yaml
ingestion:
  chunk-size: ${CHUNK_SIZE:500}
  chunk-overlap: ${CHUNK_OVERLAP:50}
  batch-size: ${BATCH_SIZE:50}              # erhöht von 20
  parallel-threads: ${INGESTION_PARALLEL_THREADS:2}  # neu
  chunk-timeout: ${INGESTION_CHUNK_TIMEOUT:300}      # neu (in Sekunden)
```

### 2.3 IngestionService refactoren

**Datei:** `ingestion/IngestionService.java`

#### a) ExecutorService durch konfigurierbaren ThreadPool ersetzen

```
Alt:  private final ExecutorService executor = Executors.newSingleThreadExecutor();
Neu:  private final ExecutorService executor; // FixedThreadPool(parallelThreads)
```

Der Executor wird im Konstruktor mit `Executors.newFixedThreadPool(parallelThreads)` erstellt.

#### b) Timeout konfigurierbar machen

```
Alt:  private static final int CHUNK_TIMEOUT_SECONDS = 120;
Neu:  private final int chunkTimeoutSeconds; // aus IngestionProperties
```

#### c) storeBatched() parallelisieren

**Bisheriges Verhalten (sequentiell):**
```
Batch 1 → embed + store → Batch 2 → embed + store → ... → Batch 165
```

**Neues Verhalten (parallel, N Threads):**
```
Thread 1: Batch 1 → embed + store → Batch 3 → ...
Thread 2: Batch 2 → embed + store → Batch 4 → ...
```

Implementierung mit `CompletableFuture.supplyAsync()` und dem konfigurierten ThreadPool. Progress-Updates bleiben erhalten (thread-safe via `AtomicInteger`).

```java
private int storeBatched(List<Document> chunks, Consumer<JobProgress> onProgress, int errors) {
    AtomicInteger stored = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(errors);

    // Batches erstellen
    List<List<Document>> batches = new ArrayList<>();
    for (int i = 0; i < chunks.size(); i += batchSize) {
        batches.add(chunks.subList(i, Math.min(i + batchSize, chunks.size())));
    }

    // Parallel verarbeiten
    List<CompletableFuture<Void>> futures = batches.stream()
        .map(batch -> CompletableFuture.runAsync(() -> {
            try {
                vectorStore.add(batch);
                int current = stored.addAndGet(batch.size());
                log.info("Batch gespeichert: {}/{} Chunks", current, chunks.size());
            } catch (Exception e) {
                log.warn("Batch fehlgeschlagen, versuche einzeln: {}", e.getMessage());
                stored.addAndGet(storeIndividually(batch));
            }
            onProgress.accept(new JobProgress("STORING",
                String.format("%d/%d Chunks gespeichert", stored.get(), chunks.size()),
                stored.get(), chunks.size(), stored.get(), errorCount.get(), null));
        }, executor))
        .toList();

    // Auf Abschluss warten
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .join();

    return stored.get();
}
```

#### d) addWithTimeout() anpassen

Timeout aus `chunkTimeoutSeconds` lesen statt hardcoded 120.

#### e) Executor-Shutdown

`@PreDestroy`-Methode für sauberes Shutdown des ThreadPools hinzufügen.

---

## 3. Konfigurationsmatrix

| Hardware | parallel-threads | batch-size | chunk-timeout | Erwartete Dauer (3300 Chunks) |
|----------|-----------------|------------|---------------|-------------------------------|
| Alter CPU, kein GPU | 2 | 50 | 300 | ~45-60 min |
| Moderner CPU, kein GPU | 2-4 | 50 | 300 | ~20-30 min |
| GPU vorhanden | 4 | 100 | 120 | ~5-10 min |

---

## 4. Akzeptanzkriterien

- [ ] Batch-Größe, Parallel-Threads und Timeout sind konfigurierbar via ENV-Variablen
- [ ] Default-Werte: `batch-size=50`, `parallel-threads=2`, `chunk-timeout=300`
- [ ] Embedding-Ergebnisse sind identisch (gleiche Chunks, gleiche Vektoren)
- [ ] Progress-Feedback funktioniert weiterhin korrekt
- [ ] Executor wird bei Application-Shutdown sauber beendet
- [ ] Bestehende Tests laufen weiterhin grün
- [ ] Fallback auf Einzel-Chunk-Verarbeitung bei Fehler bleibt erhalten

---

## 5. Nicht im Scope

- Pipelining von Crawl → Chunk → Embed (architekturelle Komplexität)
- Änderung der Chunk-Größe oder Overlap (Qualitätseinfluss)
- Wechsel des Embedding-Models
- Ollama-seitige Konfiguration (liegt beim Betreiber)
