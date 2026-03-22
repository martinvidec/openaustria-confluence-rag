package at.openaustria.confluencerag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class ConfluenceHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceHealthIndicator.class);

    private final ConfluenceProperties properties;

    public ConfluenceHealthIndicator(ConfluenceProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
            return Health.unknown().withDetail("reason", "No Confluence URL configured").build();
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(properties.baseUrl() + "/rest/api/space?limit=1"))
                    .GET();

            if (properties.pat() != null && !properties.pat().isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + properties.pat());
            }

            HttpResponse<String> response = client.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return Health.up()
                        .withDetail("url", properties.baseUrl())
                        .build();
            }
            return Health.down()
                    .withDetail("status", response.statusCode())
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("url", properties.baseUrl())
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
