package at.openaustria.confluencerag.crawler.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfluenceMetadata(
    ConfluenceLabels labels
) {}
