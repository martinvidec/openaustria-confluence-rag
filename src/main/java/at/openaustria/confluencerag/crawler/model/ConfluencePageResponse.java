package at.openaustria.confluencerag.crawler.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfluencePageResponse(
    long id,
    String title,
    String status,
    ConfluenceBody body,
    ConfluenceSpace space,
    ConfluenceVersion version,
    ConfluenceMetadata metadata,
    List<ConfluenceAncestor> ancestors,
    @JsonProperty("_links") ConfluenceLinks links
) {}
