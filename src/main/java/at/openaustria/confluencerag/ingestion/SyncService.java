package at.openaustria.confluencerag.ingestion;

import at.openaustria.confluencerag.config.ConfluenceProperties;
import at.openaustria.confluencerag.crawler.CrawlerService;
import at.openaustria.confluencerag.crawler.client.ConfluenceClient;
import at.openaustria.confluencerag.crawler.model.ConfluenceDocument;
import at.openaustria.confluencerag.crawler.model.ConfluencePageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final CrawlerService crawlerService;
    private final ChunkingService chunkingService;
    private final VectorStore vectorStore;
    private final SyncStateRepository syncStateRepository;
    private final ConfluenceClient confluenceClient;
    private final ConfluenceProperties properties;
    private final IngestionService ingestionService;

    public SyncService(CrawlerService crawlerService,
                       ChunkingService chunkingService,
                       VectorStore vectorStore,
                       SyncStateRepository syncStateRepository,
                       ConfluenceClient confluenceClient,
                       ConfluenceProperties properties,
                       IngestionService ingestionService) {
        this.crawlerService = crawlerService;
        this.chunkingService = chunkingService;
        this.vectorStore = vectorStore;
        this.syncStateRepository = syncStateRepository;
        this.confluenceClient = confluenceClient;
        this.properties = properties;
        this.ingestionService = ingestionService;
    }

    public SyncResult syncAll() {
        List<String> spaces = properties.spaces();
        if (spaces == null || spaces.isEmpty()) {
            log.warn("Keine Spaces konfiguriert.");
            return new SyncResult(0, 0, 0, 0, 0, Duration.ZERO);
        }

        log.info("Sync gestartet");
        int totalUpdated = 0, totalDeleted = 0, totalNew = 0, totalChunks = 0, totalErrors = 0;
        Instant start = Instant.now();

        for (String spaceKey : spaces) {
            SyncResult result = syncSpace(spaceKey);
            totalUpdated += result.pagesUpdated();
            totalDeleted += result.pagesDeleted();
            totalNew += result.pagesNew();
            totalChunks += result.chunksCreated();
            totalErrors += result.errors();
        }

        Duration duration = Duration.between(start, Instant.now());
        log.info("Sync abgeschlossen: {} aktualisiert, {} gelöscht, {} Chunks in {}s",
                totalUpdated, totalDeleted, totalChunks, duration.toSeconds());

        return new SyncResult(totalUpdated, totalDeleted, totalNew, totalChunks, totalErrors, duration);
    }

    public SyncResult syncSpace(String spaceKey) {
        Instant start = Instant.now();
        Optional<Instant> lastSync = syncStateRepository.getLastSync(spaceKey);

        if (lastSync.isEmpty()) {
            log.info("Space {}: Erster Sync — Voll-Crawl wird durchgeführt", spaceKey);
            IngestionResult result = ingestionService.ingestSpace(spaceKey);

            // Save page IDs
            Set<String> pageIds = getCurrentPageIds(spaceKey);
            syncStateRepository.updateKnownPageIds(spaceKey, pageIds);
            syncStateRepository.updateLastSync(spaceKey, Instant.now());

            Duration duration = Duration.between(start, Instant.now());
            return new SyncResult(0, 0, result.pagesProcessed(), result.chunksCreated(),
                    result.errors(), duration);
        }

        // Incremental sync
        int updated = 0, deleted = 0, chunksCreated = 0, errors = 0;

        // 1. Get changed pages
        List<ConfluenceDocument> changedDocs = crawlerService.crawlChangesSince(spaceKey, lastSync.get());
        log.info("Space {}: {} geänderte Seiten seit {}", spaceKey, changedDocs.size(), lastSync.get());

        // 2. Re-ingest changed pages
        for (ConfluenceDocument doc : changedDocs) {
            try {
                deleteChunksForPage(String.valueOf(doc.pageId()));
                List<Document> chunks = chunkingService.chunkDocument(doc);
                if (!chunks.isEmpty()) {
                    vectorStore.add(chunks);
                    chunksCreated += chunks.size();
                }
                updated++;
            } catch (Exception e) {
                log.error("Fehler beim Sync von Seite '{}': {}", doc.title(), e.getMessage());
                errors++;
            }
        }

        // 3. Detect deleted pages
        Set<String> currentPageIds = getCurrentPageIds(spaceKey);
        Set<String> knownPageIds = syncStateRepository.getKnownPageIds(spaceKey);
        Set<String> deletedPageIds = new HashSet<>(knownPageIds);
        deletedPageIds.removeAll(currentPageIds);

        for (String deletedId : deletedPageIds) {
            try {
                deleteChunksForPage(deletedId);
                deleted++;
                log.info("Space {}: Gelöschte Seite {} — Chunks entfernt", spaceKey, deletedId);
            } catch (Exception e) {
                log.error("Fehler beim Löschen von Chunks für Seite {}: {}", deletedId, e.getMessage());
                errors++;
            }
        }

        // 4. Update state
        syncStateRepository.updateKnownPageIds(spaceKey, currentPageIds);
        syncStateRepository.updateLastSync(spaceKey, Instant.now());

        Duration duration = Duration.between(start, Instant.now());
        log.info("Space {}: Sync abgeschlossen — {} aktualisiert, {} gelöscht, {} Chunks in {}s",
                spaceKey, updated, deleted, chunksCreated, duration.toSeconds());

        return new SyncResult(updated, deleted, 0, chunksCreated, errors, duration);
    }

    private Set<String> getCurrentPageIds(String spaceKey) {
        return confluenceClient.getPageIds(spaceKey).stream()
                .map(p -> String.valueOf(p.id()))
                .collect(Collectors.toSet());
    }

    private void deleteChunksForPage(String pageId) {
        try {
            vectorStore.delete(List.of("pageId == '" + pageId + "'"));
        } catch (Exception e) {
            log.warn("Chunks für pageId {} konnten nicht gelöscht werden: {}", pageId, e.getMessage());
        }
    }
}
