# Ist-Analyse: Ingestion-Performance

**Datum:** 2026-03-25
**Status:** Analyse
**Betroffene Komponenten:** IngestionService, IngestionProperties, application.yml

---

## 1. Problembeschreibung

Auf einem Windows-Testrechner (32 GB RAM, älterer Prozessor ohne NPU/GPU) dauert die vollständige Ingestion ca. **2 Stunden bei ~3300 Chunks**. Dies macht iteratives Testen und erstmalige Einrichtung unpraktikabel.

---

## 2. Aktueller Ingestion-Flow

```
CrawlerService.crawlAll()           — sequentiell, alle Spaces nacheinander
  → Pagination (50 Seiten/Request)
  → Pro Seite: HTML-Konvertierung, Kommentare, Attachments (sequentiell)

ChunkingService.chunkDocument()     — sequentiell, pro Dokument
  → TokenTextSplitter (500 Tokens, 50 Overlap)

IngestionService.storeBatched()     — sequentiell, SingleThread-Executor
  → Batch (20 Chunks) → vectorStore.add() → Ollama Embedding + Qdrant Upsert
  → Bei Timeout (120s): Fallback auf Einzel-Chunk-Verarbeitung
```

### 2.1 Konfiguration (application.yml)

| Parameter | Wert | Konfigurierbar |
|-----------|------|----------------|
| Batch-Größe | 20 Chunks | Ja (`BATCH_SIZE`) |
| Chunk-Größe | 500 Tokens | Ja (`CHUNK_SIZE`) |
| Chunk-Overlap | 50 Tokens | Ja (`CHUNK_OVERLAP`) |
| Embedding-Model | nomic-embed-text (768 Dim.) | Ja (`OLLAMA_EMBEDDING_MODEL`) |
| Timeout pro Batch | 120s | Nein (hardcoded) |
| Parallele Threads | 1 | Nein (hardcoded `newSingleThreadExecutor`) |

### 2.2 Embedding-Verarbeitung (IngestionService.java:149-205)

```java
// Sequentielle Batch-Verarbeitung
for (int i = 0; i < chunks.size(); i += batchSize) {
    List<Document> batch = chunks.subList(i, ...);
    addWithTimeout(batch);  // blockierend, SingleThread
}

// Timeout-Wrapper mit SingleThreadExecutor
private final ExecutorService executor = Executors.newSingleThreadExecutor();
private void addWithTimeout(List<Document> docs) {
    Future<?> future = executor.submit(() -> vectorStore.add(docs));
    future.get(120, TimeUnit.SECONDS);  // hardcoded 120s
}
```

---

## 3. Bottleneck-Analyse

### 3.1 Hauptengpass: Sequentielle Embedding-Generierung (90%+ der Gesamtzeit)

**Berechnung für 3300 Chunks, Batch-Größe 20:**
- 165 Batches, **strikt sequentiell** (SingleThreadExecutor)
- Pro Batch: HTTP-Request → Ollama Embedding (CPU-gebunden) → Qdrant GRPC Upsert
- Auf älterem CPU ohne GPU: ~2-4 Sekunden pro Embedding-Call
- Geschätzte Embedding-Zeit: 165 Batches × ~40s = **~110 Minuten**

### 3.2 Timeout-Fallback verschlimmert das Problem

- Bei Timeout (120s) wechselt das System auf **Einzel-Chunk-Verarbeitung** (IngestionService.java:158-165)
- Statt 1 Batch-Call mit 20 Chunks → 20 einzelne Calls → **~20x langsamer** pro Batch
- Auf langsamerer Hardware werden Timeouts wahrscheinlicher → Teufelskreis

### 3.3 Kein Pipelining

- Crawling muss komplett abgeschlossen sein, bevor Chunking beginnt
- Chunking muss komplett abgeschlossen sein, bevor Embedding beginnt
- Keine Überlappung der Phasen möglich

### 3.4 Fehlende Ollama-Konfiguration

- `OLLAMA_NUM_PARALLEL` (Standard: 1) — Ollama verarbeitet nur 1 Request gleichzeitig
- `OLLAMA_FLASH_ATTENTION` nicht aktiviert — keine Flash-Attention-Beschleunigung
- Keine Dokumentation dieser Tuning-Optionen

---

## 4. Priorisierte Optimierungsmöglichkeiten

| # | Maßnahme | Impact | Aufwand | Qualitätsverlust |
|---|----------|--------|---------|------------------|
| 1 | Parallele Batch-Verarbeitung (N Threads) | Hoch | Mittel | Keiner |
| 2 | Batch-Größe erhöhen (20 → 50) | Mittel | Gering | Keiner |
| 3 | Konfigurierbarer Timeout (120s → 300s) | Mittel | Gering | Keiner |
| 4 | Ollama-Tuning-Dokumentation | Mittel | Gering | Keiner |

**Erwartete Verbesserung:** Mit parallelen Threads (2-4) und größerer Batch-Größe kann die Ingestion-Zeit auf ca. **30-60 Minuten** reduziert werden (abhängig von CPU und Ollama-Konfiguration).

---

## 5. Nicht empfohlene Maßnahmen

| Maßnahme | Grund |
|----------|-------|
| Chunk-Größe erhöhen | Qualitätsverlust bei RAG-Suche |
| Overlap reduzieren | Kontextverlust zwischen Chunks |
| Leichteres Embedding-Model | Qualitätsverlust bei Similarity Search |
| Pipelining (Crawl→Chunk→Embed) | Hoher Aufwand, architekturelle Komplexität |
