package at.openaustria.confluencerag.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "confluence.sync.enabled", havingValue = "true")
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);
    private final SyncService syncService;

    public SyncScheduler(SyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(cron = "${confluence.sync.cron}")
    public void scheduledSync() {
        log.info("Geplanter Sync gestartet");
        SyncResult result = syncService.syncAll();
        log.info("Geplanter Sync abgeschlossen: {}", result);
    }
}
