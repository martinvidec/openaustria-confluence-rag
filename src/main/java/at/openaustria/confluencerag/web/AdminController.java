package at.openaustria.confluencerag.web;

import at.openaustria.confluencerag.ingestion.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final IngestionService ingestionService;
    private final SyncService syncService;
    private final SyncStateRepository syncStateRepository;

    public AdminController(IngestionService ingestionService,
                           SyncService syncService,
                           SyncStateRepository syncStateRepository) {
        this.ingestionService = ingestionService;
        this.syncService = syncService;
        this.syncStateRepository = syncStateRepository;
    }

    @PostMapping("/ingest")
    public ResponseEntity<IngestionResult> triggerIngestion() {
        return ResponseEntity.ok(ingestionService.ingestAll());
    }

    @PostMapping("/ingest/{spaceKey}")
    public ResponseEntity<IngestionResult> triggerSpaceIngestion(@PathVariable String spaceKey) {
        return ResponseEntity.ok(ingestionService.ingestSpace(spaceKey));
    }

    @PostMapping("/sync")
    public ResponseEntity<SyncResult> triggerSync() {
        return ResponseEntity.ok(syncService.syncAll());
    }

    @PostMapping("/sync/{spaceKey}")
    public ResponseEntity<SyncResult> triggerSpaceSync(@PathVariable String spaceKey) {
        return ResponseEntity.ok(syncService.syncSpace(spaceKey));
    }

    @GetMapping("/sync/status")
    public ResponseEntity<Map<String, SyncStateRepository.SpaceState>> getSyncStatus() {
        return ResponseEntity.ok(syncStateRepository.getAllStates());
    }
}
