package at.openaustria.confluencerag.crawler.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfluenceCommentResponse(
    long id,
    String title,
    ConfluenceBody body,
    ConfluenceVersion version,
    @JsonProperty("_links") ConfluenceLinks links
) {}
