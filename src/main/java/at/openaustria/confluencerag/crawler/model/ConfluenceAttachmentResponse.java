package at.openaustria.confluencerag.crawler.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfluenceAttachmentResponse(
    long id,
    String title,
    String mediaType,
    ConfluenceAttachmentExtensions extensions,
    @JsonProperty("_links") ConfluenceLinks links
) {}
