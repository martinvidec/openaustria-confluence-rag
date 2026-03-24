package at.openaustria.confluencerag.ingestion;

import at.openaustria.confluencerag.config.ConfluenceProperties;
import at.openaustria.confluencerag.config.IngestionProperties;
import at.openaustria.confluencerag.crawler.CrawlerService;
import at.openaustria.confluencerag.crawler.model.ConfluenceDocument;
import at.openaustria.confluencerag.web.JobProgress;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
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
import java.util.function.Consumer;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int CHUNK_TIMEOUT_SECONDS = 120;
    private static final int VECTOR_DIMENSION = 768;
    private final int batchSize;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
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
        List<Document> chunks = chunkingService.chunkDocument(doc);
        if (!chunks.isEmpty()) {
            storeBatched(chunks, p -> {}, 0);
        }
    }

    private int storeBatched(List<Document> chunks, Consumer<JobProgress> onProgress, int errors) {
        int stored = 0;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            List<Document> batch = chunks.subList(i, Math.min(i + batchSize, chunks.size()));
            try {
                addWithTimeout(batch);
                stored += batch.size();
                log.info("Batch gespeichert: {}/{} Chunks",
                        Math.min(i + batchSize, chunks.size()), chunks.size());
            } catch (TimeoutException e) {
                log.warn("Batch {}-{} Timeout ({}s), versuche einzeln",
                        i, Math.min(i + batchSize, chunks.size()), CHUNK_TIMEOUT_SECONDS);
                stored += storeIndividually(batch);
            } catch (Exception e) {
                log.warn("Batch {}-{} fehlgeschlagen, versuche einzeln: {}",
                        i, Math.min(i + batchSize, chunks.size()), e.getMessage());
                stored += storeIndividually(batch);
            }
            onProgress.accept(new JobProgress("STORING",
                    String.format("%d/%d Chunks gespeichert", stored, chunks.size()),
                    stored, chunks.size(), stored, errors, null));
        }
        return stored;
    }

    private int storeIndividually(List<Document> chunks) {
        int stored = 0;
        for (Document chunk : chunks) {
            try {
                addWithTimeout(List.of(chunk));
                stored++;
            } catch (TimeoutException e) {
                log.error("Chunk Timeout ({}s, {} Zeichen, Seite: '{}')",
                        CHUNK_TIMEOUT_SECONDS,
                        chunk.getText().length(),
                        chunk.getMetadata().getOrDefault("pageTitle", "?"));
            } catch (Exception e) {
                log.error("Chunk übersprungen ({} Zeichen, Seite: '{}'): {}",
                        chunk.getText().length(),
                        chunk.getMetadata().getOrDefault("pageTitle", "?"),
                        e.getMessage());
            }
        }
        return stored;
    }

    private void addWithTimeout(List<Document> docs) throws TimeoutException, Exception {
        Future<?> future = executor.submit(() -> vectorStore.add(docs));
        try {
            future.get(CHUNK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw (Exception) e.getCause();
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
