package at.openaustria.confluencerag.config;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class QdrantHealthIndicator implements HealthIndicator {

    private final VectorStore vectorStore;

    public QdrantHealthIndicator(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public Health health() {
        try {
            // A simple search to verify connectivity
            vectorStore.similaritySearch(
                    org.springframework.ai.vectorstore.SearchRequest.builder()
                            .query("health-check")
                            .topK(1)
                            .build());
            return Health.up().withDetail("status", "connected").build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
