package at.openaustria.confluencerag.web;

import at.openaustria.confluencerag.ingestion.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final IngestionService ingestionService;
    private final SyncService syncService;
    private final SyncStateRepository syncStateRepository;

    private final AtomicReference<JobStatus> currentJob = new AtomicReference<>(
            new JobStatus("idle", null, null, null, null));

    public AdminController(IngestionService ingestionService,
                           SyncService syncService,
                           SyncStateRepository syncStateRepository) {
        this.ingestionService = ingestionService;
        this.syncService = syncService;
        this.syncStateRepository = syncStateRepository;
    }

    @PostMapping("/ingest")
    public ResponseEntity<JobStatus> triggerIngestion() {
        if (isRunning()) {
            return ResponseEntity.status(409).body(currentJob.get());
        }
        String op = "ingest";
        currentJob.set(new JobStatus("running", op, null, null, null));
        var onProgress = progressCallback(op);
        CompletableFuture.runAsync(() -> {
            try {
                IngestionResult result = ingestionService.ingestAll(onProgress);
                currentJob.set(new JobStatus("completed", op, result, null, null));
                log.info("Ingest abgeschlossen: {}", result);
            } catch (Exception e) {
                currentJob.set(new JobStatus("failed", op, null, e.getMessage(), null));
                log.error("Ingest fehlgeschlagen", e);
            }
        });
        return ResponseEntity.accepted().body(currentJob.get());
    }

    @PostMapping("/ingest/{spaceKey}")
    public ResponseEntity<JobStatus> triggerSpaceIngestion(@PathVariable String spaceKey) {
        if (isRunning()) {
            return ResponseEntity.status(409).body(currentJob.get());
        }
        String op = "ingest:" + spaceKey;
        currentJob.set(new JobStatus("running", op, null, null, null));
        var onProgress = progressCallback(op);
        CompletableFuture.runAsync(() -> {
            try {
                IngestionResult result = ingestionService.ingestSpace(spaceKey, onProgress);
                currentJob.set(new JobStatus("completed", op, result, null, null));
            } catch (Exception e) {
                currentJob.set(new JobStatus("failed", op, null, e.getMessage(), null));
            }
        });
        return ResponseEntity.accepted().body(currentJob.get());
    }

    @PostMapping("/sync")
    public ResponseEntity<JobStatus> triggerSync() {
        if (isRunning()) {
            return ResponseEntity.status(409).body(currentJob.get());
        }
        String op = "sync";
        currentJob.set(new JobStatus("running", op, null, null, null));
        var onProgress = progressCallback(op);
        CompletableFuture.runAsync(() -> {
            try {
                SyncResult result = syncService.syncAll(onProgress);
                currentJob.set(new JobStatus("completed", op, result, null, null));
                log.info("Sync abgeschlossen: {}", result);
            } catch (Exception e) {
                currentJob.set(new JobStatus("failed", op, null, e.getMessage(), null));
                log.error("Sync fehlgeschlagen", e);
            }
        });
        return ResponseEntity.accepted().body(currentJob.get());
    }

    @PostMapping("/sync/{spaceKey}")
    public ResponseEntity<JobStatus> triggerSpaceSync(@PathVariable String spaceKey) {
        if (isRunning()) {
            return ResponseEntity.status(409).body(currentJob.get());
        }
        String op = "sync:" + spaceKey;
        currentJob.set(new JobStatus("running", op, null, null, null));
        var onProgress = progressCallback(op);
        CompletableFuture.runAsync(() -> {
            try {
                SyncResult result = syncService.syncSpace(spaceKey, onProgress);
                currentJob.set(new JobStatus("completed", op, result, null, null));
            } catch (Exception e) {
                currentJob.set(new JobStatus("failed", op, null, e.getMessage(), null));
            }
        });
        return ResponseEntity.accepted().body(currentJob.get());
    }

    private java.util.function.Consumer<JobProgress> progressCallback(String operation) {
        return progress -> currentJob.set(new JobStatus("running", operation, null, null, progress));
    }

    @GetMapping("/job/status")
    public ResponseEntity<JobStatus> getJobStatus() {
        return ResponseEntity.ok(currentJob.get());
    }

    @GetMapping("/sync/status")
    public ResponseEntity<Map<String, SyncStateRepository.SpaceState>> getSyncStatus() {
        return ResponseEntity.ok(syncStateRepository.getAllStates());
    }

    private boolean isRunning() {
        return "running".equals(currentJob.get().status());
    }

    public record JobStatus(
        String status,    // idle, running, completed, failed
        String operation, // ingest, sync, ingest:SPACEKEY, sync:SPACEKEY
        Object result,    // IngestionResult or SyncResult
        String error,
        JobProgress progress // live progress during running state
    ) {}
}
