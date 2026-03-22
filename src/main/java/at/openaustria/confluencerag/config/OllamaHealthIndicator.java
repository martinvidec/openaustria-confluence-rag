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
public class OllamaHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(OllamaHealthIndicator.class);

    private final ConfluenceProperties properties;

    public OllamaHealthIndicator(ConfluenceProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        try {
            // We read the Ollama base URL from Spring AI config via environment
            String ollamaUrl = System.getenv("OLLAMA_BASE_URL");
            if (ollamaUrl == null || ollamaUrl.isBlank()) {
                ollamaUrl = "http://localhost:11434";
            }

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl + "/api/tags"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return Health.up()
                        .withDetail("url", ollamaUrl)
                        .build();
            }
            return Health.down()
                    .withDetail("status", response.statusCode())
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
