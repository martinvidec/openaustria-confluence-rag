package at.openaustria.confluencerag.crawler.client;

import at.openaustria.confluencerag.config.ConfluenceProperties;
import at.openaustria.confluencerag.crawler.model.ConfluencePageResponse;
import at.openaustria.confluencerag.crawler.model.ConfluenceSpaceResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConfluenceClientTest {

    private HttpServer server;
    private ConfluenceClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        int port = server.getAddress().getPort();
        baseUrl = "http://localhost:" + port;

        ConfluenceProperties props = new ConfluenceProperties(
                baseUrl, "test-pat", List.of("DEV"), null);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .registerModule(new JavaTimeModule());

        client = new ConfluenceClient(httpClient, mapper, props);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void getSpaces_returnsParsedSpaces() {
        server.createContext("/rest/api/space", exchange -> {
            String json = """
                {
                  "results": [
                    {"key": "DEV", "name": "Development", "type": "global"},
                    {"key": "OPS", "name": "Operations", "type": "global"}
                  ],
                  "start": 0, "limit": 50, "size": 2,
                  "_links": {}
                }
                """;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json.getBytes());
            }
        });

        List<ConfluenceSpaceResponse> spaces = client.getSpaces();
        assertEquals(2, spaces.size());
        assertEquals("DEV", spaces.get(0).key());
        assertEquals("Operations", spaces.get(1).name());
    }

    @Test
    void getPages_handlesPagination() {
        AtomicInteger requestCount = new AtomicInteger(0);
        server.createContext("/rest/api/content", exchange -> {
            int count = requestCount.incrementAndGet();
            String json;
            if (count == 1) {
                json = """
                    {
                      "results": [
                        {"id": 1, "title": "Page 1", "status": "current"}
                      ],
                      "start": 0, "limit": 50, "size": 1,
                      "_links": {"next": "/rest/api/content?start=50"}
                    }
                    """;
            } else {
                json = """
                    {
                      "results": [
                        {"id": 2, "title": "Page 2", "status": "current"}
                      ],
                      "start": 50, "limit": 50, "size": 1,
                      "_links": {}
                    }
                    """;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json.getBytes());
            }
        });

        List<ConfluencePageResponse> pages = client.getPages("DEV");
        assertEquals(2, pages.size());
        assertEquals("Page 1", pages.get(0).title());
        assertEquals("Page 2", pages.get(1).title());
        assertEquals(2, requestCount.get());
    }

    @Test
    void getPages_sendsAuthorizationHeader() {
        server.createContext("/rest/api/content", exchange -> {
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            assertEquals("Bearer test-pat", authHeader);

            String json = """
                {"results": [], "start": 0, "limit": 50, "size": 0, "_links": {}}
                """;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json.getBytes());
            }
        });

        client.getPages("DEV");
    }

    @Test
    void getPages_throws_on401() {
        server.createContext("/rest/api/content", exchange -> {
            String body = "Unauthorized";
            exchange.sendResponseHeaders(401, body.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body.getBytes());
            }
        });

        ConfluenceApiException ex = assertThrows(ConfluenceApiException.class,
                () -> client.getPages("DEV"));
        assertTrue(ex.getMessage().contains("401"));
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    void getPages_retriesOn500() {
        AtomicInteger attempts = new AtomicInteger(0);
        server.createContext("/rest/api/content", exchange -> {
            int attempt = attempts.incrementAndGet();
            if (attempt <= 2) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            } else {
                String json = """
                    {"results": [{"id": 1, "title": "Page 1", "status": "current"}],
                     "start": 0, "limit": 50, "size": 1, "_links": {}}
                    """;
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, json.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(json.getBytes());
                }
            }
        });

        List<ConfluencePageResponse> pages = client.getPages("DEV");
        assertEquals(1, pages.size());
        assertEquals(3, attempts.get());
    }
}
