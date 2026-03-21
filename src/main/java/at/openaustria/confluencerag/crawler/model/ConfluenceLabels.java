package at.openaustria.confluencerag.crawler.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfluenceLabels(
    List<ConfluenceLabel> results
) {}
