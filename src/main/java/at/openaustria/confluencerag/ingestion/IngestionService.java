package at.openaustria.confluencerag.ingestion;

import at.openaustria.confluencerag.config.ConfluenceProperties;
import at.openaustria.confluencerag.config.IngestionProperties;
import at.openaustria.confluencerag.crawler.CrawlerService;
import at.openaustria.confluencerag.crawler.model.ConfluenceDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private final int batchSize;
    private final CrawlerService crawlerService;
    private final ChunkingService chunkingService;
    private final VectorStore vectorStore;
    private final ConfluenceProperties properties;

    public IngestionService(CrawlerService crawlerService,
                            ChunkingService chunkingService,
                            VectorStore vectorStore,
                            ConfluenceProperties properties,
                            IngestionProperties ingestionProperties) {
        this.crawlerService = crawlerService;
        this.chunkingService = chunkingService;
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.batchSize = ingestionProperties.batchSize();
    }

    public IngestionResult ingestAll() {
        Instant start = Instant.now();
        List<ConfluenceDocument> documents = crawlerService.crawlAll();

        log.info("Ingestion gestartet: {} Dokumente", documents.size());
        int chunksCreated = 0;
        int chunksStored = 0;
        int errors = 0;

        List<Document> allChunks = new ArrayList<>();
        for (ConfluenceDocument doc : documents) {
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
        chunksStored = storeBatched(allChunks);

        Duration duration = Duration.between(start, Instant.now());
        log.info("Ingestion abgeschlossen: {} Chunks in {}s",
                chunksStored, duration.toSeconds());

        return new IngestionResult(
                properties.spaces() != null ? properties.spaces().size() : 0,
                documents.size(), chunksCreated, chunksStored, errors, duration);
    }

    public IngestionResult ingestSpace(String spaceKey) {
        Instant start = Instant.now();
        List<ConfluenceDocument> documents = crawlerService.crawlSpace(spaceKey);

        int chunksCreated = 0;
        int chunksStored = 0;
        int errors = 0;

        List<Document> allChunks = new ArrayList<>();
        for (ConfluenceDocument doc : documents) {
            try {
                List<Document> chunks = chunkingService.chunkDocument(doc);
                allChunks.addAll(chunks);
                chunksCreated += chunks.size();
            } catch (Exception e) {
                log.error("Chunking fehlgeschlagen für Seite '{}': {}", doc.title(), e.getMessage());
                errors++;
            }
        }

        chunksStored = storeBatched(allChunks);

        Duration duration = Duration.between(start, Instant.now());
        return new IngestionResult(1, documents.size(), chunksCreated, chunksStored, errors, duration);
    }

    public void ingestDocument(ConfluenceDocument doc) {
        List<Document> chunks = chunkingService.chunkDocument(doc);
        if (!chunks.isEmpty()) {
            storeBatched(chunks);
        }
    }

    private int storeBatched(List<Document> chunks) {
        int stored = 0;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            List<Document> batch = chunks.subList(i, Math.min(i + batchSize, chunks.size()));
            try {
                vectorStore.add(batch);
                stored += batch.size();
                log.debug("Batch gespeichert: {}/{} Chunks",
                        Math.min(i + batchSize, chunks.size()), chunks.size());
            } catch (Exception e) {
                log.error("Batch-Upsert fehlgeschlagen (Chunks {}-{}): {}",
                        i, Math.min(i + batchSize, chunks.size()), e.getMessage());
            }
        }
        return stored;
    }
}
