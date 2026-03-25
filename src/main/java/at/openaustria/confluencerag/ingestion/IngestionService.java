package at.openaustria.confluencerag.ingestion;

import at.openaustria.confluencerag.config.ConfluenceProperties;
import at.openaustria.confluencerag.config.IngestionProperties;
import at.openaustria.confluencerag.crawler.CrawlerService;
import at.openaustria.confluencerag.crawler.model.ConfluenceDocument;
import at.openaustria.confluencerag.web.JobProgress;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int VECTOR_DIMENSION = 768;
    private final int batchSize;
    private final int chunkTimeoutSeconds;
    private final ExecutorService executor;
    private final CrawlerService crawlerService;
    private final ChunkingService chunkingService;
    private final VectorStore vectorStore;
    private final QdrantClient qdrantClient;
    private final String collectionName;
    private final ConfluenceProperties properties;

    public IngestionService(CrawlerService crawlerService,
                            ChunkingService chunkingService,
                            VectorStore vectorStore,
                            QdrantClient qdrantClient,
                            @Value("${spring.ai.vectorstore.qdrant.collection-name:confluence-chunks}") String collectionName,
                            ConfluenceProperties properties,
                            IngestionProperties ingestionProperties) {
        this.crawlerService = crawlerService;
        this.chunkingService = chunkingService;
        this.vectorStore = vectorStore;
        this.qdrantClient = qdrantClient;
        this.collectionName = collectionName;
        this.properties = properties;
        this.batchSize = ingestionProperties.batchSize();
        this.chunkTimeoutSeconds = ingestionProperties.chunkTimeout();
        this.executor = Executors.newFixedThreadPool(ingestionProperties.parallelThreads());
        log.info("IngestionService konfiguriert: batchSize={}, parallelThreads={}, chunkTimeout={}s",
                batchSize, ingestionProperties.parallelThreads(), chunkTimeoutSeconds);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public IngestionResult ingestAll() {
        return ingestAll(p -> {});
    }

    public IngestionResult ingestAll(Consumer<JobProgress> onProgress) {
        Instant start = Instant.now();

        onProgress.accept(new JobProgress("CLEARING", "Lösche alte Chunks...",
                0, 0, 0, 0, null));
        clearCollection();

        onProgress.accept(new JobProgress("CRAWLING", "Crawle alle Spaces...",
                0, 0, 0, 0, null));

        List<ConfluenceDocument> documents = crawlerService.crawlAll();

        log.info("Ingestion gestartet: {} Dokumente", documents.size());
        int chunksCreated = 0;
        int errors = 0;

        List<Document> allChunks = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            ConfluenceDocument doc = documents.get(i);
            onProgress.accept(new JobProgress("CHUNKING",
                    String.format("Seite %d/%d — '%s'", i + 1, documents.size(), doc.title()),
                    i + 1, documents.size(), chunksCreated, errors, doc.spaceKey()));
            try {
                List<Document> chunks = chunkingService.chunkDocument(doc);
                allChunks.addAll(chunks);
                chunksCreated += chunks.size();
            } catch (Exception e) {
                log.error("Chunking fehlgeschlagen für Seite '{}': {}", doc.title(), e.getMessage());
                errors++;
            }
        }

        log.info("Chunking: {} Dokumente → {} Chunks", documents.size(), chunksCreated);
        int chunksStored = storeBatched(allChunks, onProgress, errors);

        Duration duration = Duration.between(start, Instant.now());
        log.info("Ingestion abgeschlossen: {} Chunks in {}s",
                chunksStored, duration.toSeconds());

        return new IngestionResult(
                properties.spaces() != null ? properties.spaces().size() : 0,
                documents.size(), chunksCreated, chunksStored, errors, duration);
    }

    public IngestionResult ingestSpace(String spaceKey) {
        return ingestSpace(spaceKey, p -> {});
    }

    public IngestionResult ingestSpace(String spaceKey, Consumer<JobProgress> onProgress) {
        Instant start = Instant.now();

        onProgress.accept(new JobProgress("CLEARING",
                String.format("Lösche alte Chunks für Space %s...", spaceKey),
                0, 0, 0, 0, spaceKey));
        deleteChunksForSpace(spaceKey);

        onProgress.accept(new JobProgress("CRAWLING",
                String.format("Crawle Space %s...", spaceKey),
                0, 0, 0, 0, spaceKey));

        List<ConfluenceDocument> documents = crawlerService.crawlSpace(spaceKey);

        int chunksCreated = 0;
        int errors = 0;

        List<Document> allChunks = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            ConfluenceDocument doc = documents.get(i);
            onProgress.accept(new JobProgress("CHUNKING",
                    String.format("Seite %d/%d — '%s'", i + 1, documents.size(), doc.title()),
                    i + 1, documents.size(), chunksCreated, errors, spaceKey));
            try {
                List<Document> chunks = chunkingService.chunkDocument(doc);
                allChunks.addAll(chunks);
                chunksCreated += chunks.size();
            } catch (Exception e) {
                log.error("Chunking fehlgeschlagen für Seite '{}': {}", doc.title(), e.getMessage());
                errors++;
            }
        }

        int chunksStored = storeBatched(allChunks, onProgress, errors);

        Duration duration = Duration.between(start, Instant.now());
        return new IngestionResult(1, documents.size(), chunksCreated, chunksStored, errors, duration);
    }

    public void ingestDocument(ConfluenceDocument doc) {
        deleteChunksForPage(String.valueOf(doc.pageId()));
        List<Document> chunks = chunkingService.chunkDocument(doc);
        if (!chunks.isEmpty()) {
            storeBatched(chunks, p -> {}, 0);
        }
    }

    private int storeBatched(List<Document> chunks, Consumer<JobProgress> onProgress, int errors) {
        List<List<Document>> batches = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i += batchSize) {
            batches.add(new ArrayList<>(chunks.subList(i, Math.min(i + batchSize, chunks.size()))));
        }

        log.info("Starte parallele Embedding-Verarbeitung: {} Batches à max {} Chunks",
                batches.size(), batchSize);

        AtomicInteger stored = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(errors);
        int totalChunks = chunks.size();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (List<Document> batch : batches) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                vectorStore.add(batch);
                int current = stored.addAndGet(batch.size());
                log.info("Batch gespeichert: {}/{} Chunks", current, totalChunks);
            }, executor)
            .orTimeout(chunkTimeoutSeconds, TimeUnit.SECONDS)
            .exceptionally(ex -> {
                Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                if (cause instanceof TimeoutException) {
                    log.warn("Batch Timeout ({}s, {} Chunks), versuche einzeln",
                            chunkTimeoutSeconds, batch.size());
                } else {
                    log.warn("Batch fehlgeschlagen ({} Chunks), versuche einzeln: {}",
                            batch.size(), cause.getMessage());
                }
                stored.addAndGet(storeIndividually(batch));
                return null;
            })
            .thenRun(() -> onProgress.accept(new JobProgress("STORING",
                    String.format("%d/%d Chunks gespeichert", stored.get(), totalChunks),
                    stored.get(), totalChunks, stored.get(), errorCount.get(), null)));
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return stored.get();
    }

    private int storeIndividually(List<Document> chunks) {
        int stored = 0;
        for (Document chunk : chunks) {
            try {
                vectorStore.add(List.of(chunk));
                stored++;
            } catch (Exception e) {
                log.error("Chunk übersprungen ({} Zeichen, Seite: '{}'): {}",
                        chunk.getText().length(),
                        chunk.getMetadata().getOrDefault("pageTitle", "?"),
                        e.getMessage());
            }
        }
        return stored;
    }

    private void deleteChunksForSpace(String spaceKey) {
        try {
            vectorStore.delete(List.of("spaceKey == '" + spaceKey + "'"));
            log.info("Chunks für Space '{}' gelöscht", spaceKey);
        } catch (Exception e) {
            log.warn("Chunks für Space '{}' konnten nicht gelöscht werden: {}", spaceKey, e.getMessage());
        }
    }

    private void deleteChunksForPage(String pageId) {
        try {
            vectorStore.delete(List.of("pageId == '" + pageId + "'"));
            log.info("Chunks für Seite '{}' gelöscht", pageId);
        } catch (Exception e) {
            log.warn("Chunks für pageId {} konnten nicht gelöscht werden: {}", pageId, e.getMessage());
        }
    }

    private void clearCollection() {
        try {
            qdrantClient.deleteCollectionAsync(collectionName).get(30, TimeUnit.SECONDS);
            log.info("Qdrant Collection '{}' gelöscht", collectionName);

            qdrantClient.createCollectionAsync(collectionName,
                    VectorParams.newBuilder()
                            .setSize(VECTOR_DIMENSION)
                            .setDistance(Distance.Cosine)
                            .build()
            ).get(30, TimeUnit.SECONDS);
            log.info("Qdrant Collection '{}' neu erstellt", collectionName);
        } catch (Exception e) {
            log.warn("Collection-Reset fehlgeschlagen, fahre fort: {}", e.getMessage());
        }
    }
}
