package at.openaustria.confluencerag.web;

import at.openaustria.confluencerag.ingestion.IngestionResult;
import at.openaustria.confluencerag.ingestion.IngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final IngestionService ingestionService;

    public AdminController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<IngestionResult> triggerIngestion() {
        IngestionResult result = ingestionService.ingestAll();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/ingest/{spaceKey}")
    public ResponseEntity<IngestionResult> triggerSpaceIngestion(@PathVariable String spaceKey) {
        IngestionResult result = ingestionService.ingestSpace(spaceKey);
        return ResponseEntity.ok(result);
    }
}
