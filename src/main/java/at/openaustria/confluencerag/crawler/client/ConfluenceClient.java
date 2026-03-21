package at.openaustria.confluencerag.crawler.client;

import at.openaustria.confluencerag.config.ConfluenceProperties;
import at.openaustria.confluencerag.crawler.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ConfluenceClient {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceClient.class);
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_RETRIES = 3;
    private static final String PAGE_EXPAND = "body.storage,metadata.labels,version,space,ancestors";
    private static final String COMMENT_EXPAND = "body.storage,version";
    private static final DateTimeFormatter CQL_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ConfluenceProperties properties;

    public ConfluenceClient(HttpClient confluenceHttpClient,
                            ObjectMapper confluenceObjectMapper,
                            ConfluenceProperties properties) {
        this.httpClient = confluenceHttpClient;
        this.objectMapper = confluenceObjectMapper;
        this.properties = properties;
    }

    public List<ConfluenceSpaceResponse> getSpaces() {
        String url = properties.baseUrl() + "/rest/api/space";
        return fetchAllPages(url, Map.of(), new TypeReference<>() {});
    }

    public List<ConfluencePageResponse> getPages(String spaceKey) {
        String url = properties.baseUrl() + "/rest/api/content";
        Map<String, String> params = Map.of(
                "spaceKey", spaceKey,
                "type", "page",
                "expand", PAGE_EXPAND
        );
        return fetchAllPages(url, params, new TypeReference<>() {});
    }

    public List<ConfluencePageResponse> getPagesSince(String spaceKey, Instant since) {
        String cql = "space=\"%s\" AND type=\"page\" AND lastModified>\"%s\"".formatted(
                spaceKey, CQL_DATE_FORMAT.format(since));
        String url = properties.baseUrl() + "/rest/api/content/search";
        Map<String, String> params = Map.of(
                "cql", cql,
                "expand", PAGE_EXPAND
        );
        return fetchAllPages(url, params, new TypeReference<>() {});
    }

    public List<ConfluencePageResponse> getPageIds(String spaceKey) {
        String url = properties.baseUrl() + "/rest/api/content";
        Map<String, String> params = Map.of(
                "spaceKey", spaceKey,
                "type", "page"
        );
        return fetchAllPages(url, params, new TypeReference<>() {});
    }

    public List<ConfluenceCommentResponse> getComments(long pageId) {
        String url = properties.baseUrl() + "/rest/api/content/" + pageId + "/child/comment";
        Map<String, String> params = Map.of("expand", COMMENT_EXPAND);
        return fetchAllPages(url, params, new TypeReference<>() {});
    }

    public List<ConfluenceAttachmentResponse> getAttachments(long pageId) {
        String url = properties.baseUrl() + "/rest/api/content/" + pageId + "/child/attachment";
        return fetchAllPages(url, Map.of(), new TypeReference<>() {});
    }

    public byte[] downloadAttachment(String downloadPath) {
        String url = properties.baseUrl() + downloadPath;
        HttpRequest request = buildRequest(url);
        try {
            HttpResponse<byte[]> response = executeWithRetry(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            return response.body();
        } catch (Exception e) {
            throw new ConfluenceApiException("Failed to download attachment: " + downloadPath);
        }
    }

    private <T> List<T> fetchAllPages(String baseUrl, Map<String, String> params,
                                       TypeReference<T> typeRef) {
        List<T> allResults = new ArrayList<>();
        int start = 0;

        while (true) {
            String url = buildUrl(baseUrl, params, start, DEFAULT_LIMIT);
            HttpRequest request = buildRequest(url);

            String responseBody;
            try {
                HttpResponse<String> response = executeWithRetry(request,
                        HttpResponse.BodyHandlers.ofString());
                responseBody = response.body();
            } catch (ConfluenceApiException e) {
                throw e;
            } catch (Exception e) {
                throw new ConfluenceApiException("Request failed: " + e.getMessage());
            }

            try {
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode results = root.get("results");
                if (results != null && results.isArray()) {
                    for (JsonNode node : results) {
                        T item = objectMapper.treeToValue(node, objectMapper.constructType(typeRef.getType()));
                        allResults.add(item);
                    }
                }

                JsonNode links = root.get("_links");
                if (links == null || !links.has("next")) {
                    break;
                }
                start += DEFAULT_LIMIT;
            } catch (Exception e) {
                throw new ConfluenceApiException("Failed to parse response: " + e.getMessage());
            }
        }

        return allResults;
    }

    private <T> HttpResponse<T> executeWithRetry(HttpRequest request,
                                                  HttpResponse.BodyHandler<T> bodyHandler) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpResponse<T> response = httpClient.send(request, bodyHandler);
                int status = response.statusCode();

                if (status == 200) {
                    return response;
                }
                if (status == 401) {
                    throw new ConfluenceApiException(status, "PAT ungültig oder abgelaufen");
                }
                if (status == 403) {
                    throw new ConfluenceApiException(status, "Keine Berechtigung");
                }
                if (status == 404) {
                    throw new ConfluenceApiException(status, "Ressource nicht gefunden");
                }
                if (status == 429 || status >= 500) {
                    long waitMs = calculateBackoff(attempt, response);
                    log.warn("HTTP {} bei {}. Retry {}/{} nach {}ms",
                            status, request.uri(), attempt + 1, MAX_RETRIES, waitMs);
                    Thread.sleep(waitMs);
                    continue;
                }
                throw new ConfluenceApiException(status, "Unerwarteter HTTP Status");
            } catch (ConfluenceApiException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConfluenceApiException("Request interrupted");
            } catch (IOException e) {
                if (attempt < MAX_RETRIES) {
                    long waitMs = (long) Math.pow(2, attempt) * 1000;
                    log.warn("IO-Fehler bei {}. Retry {}/{} nach {}ms: {}",
                            request.uri(), attempt + 1, MAX_RETRIES, waitMs, e.getMessage());
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ConfluenceApiException("Request interrupted");
                    }
                    continue;
                }
                throw new ConfluenceApiException("Request failed after %d retries: %s"
                        .formatted(MAX_RETRIES, e.getMessage()));
            }
        }
        throw new ConfluenceApiException("Max retries exceeded for " + request.uri());
    }

    private <T> long calculateBackoff(int attempt, HttpResponse<T> response) {
        if (response.statusCode() == 429) {
            return response.headers()
                    .firstValueAsLong("Retry-After")
                    .orElse(30) * 1000;
        }
        return (long) Math.pow(2, attempt) * 1000;
    }

    private HttpRequest buildRequest(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("Accept", "application/json");

        if (properties.pat() != null && !properties.pat().isBlank()) {
            builder.header("Authorization", "Bearer " + properties.pat());
        }

        return builder.build();
    }

    private String buildUrl(String baseUrl, Map<String, String> params, int start, int limit) {
        StringBuilder sb = new StringBuilder(baseUrl);
        sb.append("?start=").append(start);
        sb.append("&limit=").append(limit);
        for (Map.Entry<String, String> entry : params.entrySet()) {
            sb.append("&").append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            sb.append("=").append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
