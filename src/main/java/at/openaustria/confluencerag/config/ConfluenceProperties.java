package at.openaustria.confluencerag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "confluence")
public record ConfluenceProperties(
    String baseUrl,
    String pat,
    List<String> spaces,
    SyncProperties sync
) {
    public record SyncProperties(
        String cron,
        boolean enabled,
        String stateFile
    ) {}
}
