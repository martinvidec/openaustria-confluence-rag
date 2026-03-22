package at.openaustria.confluencerag.crawler.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfluenceVersion(
    int number,
    Instant when,
    ConfluenceUser by
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfluenceUser(
        String displayName,
        String type
    ) {}

    public String getAuthorName() {
        return by != null && by.displayName() != null ? by.displayName() : "";
    }
}
