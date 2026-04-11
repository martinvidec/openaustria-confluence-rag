package at.openaustria.confluencerag.query;

import at.openaustria.confluencerag.config.QueryProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InfinityCrossEncoderRerankerTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private QueryProperties props(int candidateCount) {
        return new QueryProperties(5, 0.45,
                new QueryProperties.RerankerProperties(
                        "infinity",
                        new QueryProperties.LlmRerankerProperties(
                                "http://localhost:11434", "qwen3:0.6b", 15, 60, 500),
                        new QueryProperties.InfinityRerankerProperties(
                                baseUrl, "BAAI/bge-reranker-v2-m3", candidateCount, 5)));
    }

    private InfinityCrossEncoderReranker serviceFor(QueryProperties properties) {
        RestClient client = RestClient.builder().baseUrl(baseUrl).build();
        return new InfinityCrossEncoderReranker(properties, client);
    }

    private Document doc(String title, String text) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("pageTitle", title);
        return new Document(text, meta);
    }

    @Test
    void rerank_reordersCandidates_whenServerReturnsResults() {
        server.createContext("/rerank", exchange -> {
            // Server returns results in reversed order: 2, 1, 0
            String json = """
                    {"results": [
                        {"index": 2, "relevance_score": 0.95},
                        {"index": 1, "relevance_score": 0.80},
                        {"index": 0, "relevance_score": 0.40}
                    ]}
                    """;
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json.getBytes());
            }
        });

        InfinityCrossEncoderReranker service = serviceFor(props(30));
        List<Document> candidates = List.of(
                doc("Page A", "first text"),
                doc("Page B", "second text"),
                doc("Page C", "third text"));

        List<Document> result = service.rerank("question", candidates, 3);

        assertEquals(3, result.size());
        assertEquals("Page C", result.get(0).getMetadata().get("pageTitle"));
        assertEquals("Page B", result.get(1).getMetadata().get("pageTitle"));
        assertEquals("Page A", result.get(2).getMetadata().get("pageTitle"));
    }

    @Test
    void rerank_limitsToTopK() {
        server.createContext("/rerank", exchange -> {
            String json = """
                    {"results": [
                        {"index": 0, "relevance_score": 0.95},
                        {"index": 1, "relevance_score": 0.80},
                        {"index": 2, "relevance_score": 0.40}
                    ]}
                    """;
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json.getBytes());
            }
        });

        InfinityCrossEncoderReranker service = serviceFor(props(30));
        List<Document> candidates = List.of(
                doc("A", "x"), doc("B", "y"), doc("C", "z"));

        List<Document> result = service.rerank("q", candidates, 2);

        assertEquals(2, result.size());
        assertEquals("A", result.get(0).getMetadata().get("pageTitle"));
        assertEquals("B", result.get(1).getMetadata().get("pageTitle"));
    }

    @Test
    void rerank_fallsBackOnHttpError() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/rerank", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        InfinityCrossEncoderReranker service = serviceFor(props(30));
        List<Document> candidates = List.of(
                doc("A", "x"), doc("B", "y"), doc("C", "z"));

        List<Document> result = service.rerank("q", candidates, 5);

        assertEquals(1, calls.get());
        assertEquals(3, result.size());
        assertEquals("A", result.get(0).getMetadata().get("pageTitle"));
        assertEquals("B", result.get(1).getMetadata().get("pageTitle"));
        assertEquals("C", result.get(2).getMetadata().get("pageTitle"));
    }

    @Test
    void rerank_returnsEmptyList_forEmptyInput() {
        InfinityCrossEncoderReranker service = serviceFor(props(30));
        List<Document> result = service.rerank("q", List.of(), 5);
        assertTrue(result.isEmpty());
    }

    @Test
    void rerank_skipsInvalidIndices() {
        server.createContext("/rerank", exchange -> {
            // Server returns an out-of-range index — should be filtered out
            String json = """
                    {"results": [
                        {"index": 99, "relevance_score": 0.95},
                        {"index": 1, "relevance_score": 0.80}
                    ]}
                    """;
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json.getBytes());
            }
        });

        InfinityCrossEncoderReranker service = serviceFor(props(30));
        List<Document> candidates = List.of(doc("A", "x"), doc("B", "y"));

        List<Document> result = service.rerank("q", candidates, 5);

        assertEquals(1, result.size());
        assertEquals("B", result.get(0).getMetadata().get("pageTitle"));
    }

    @Test
    void candidateCount_returnsConfiguredValue() {
        InfinityCrossEncoderReranker service = serviceFor(props(42));
        assertEquals(42, service.candidateCount());
    }
}
