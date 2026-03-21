package at.openaustria.confluencerag.crawler.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfluenceSpaceResponse(
    String key,
    String name,
    String type,
    @JsonProperty("_links") ConfluenceLinks links
) {}
