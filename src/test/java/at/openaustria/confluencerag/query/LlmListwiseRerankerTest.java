package at.openaustria.confluencerag.query;

import at.openaustria.confluencerag.config.QueryProperties;
import com.sun.net.httpserver.HttpExchange;
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

class LlmListwiseRerankerTest {

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
        return props(candidateCount, 60);
    }

    private QueryProperties props(int candidateCount, int timeoutSeconds) {
        return new QueryProperties(5, 0.45,
                new QueryProperties.RerankerProperties(
                        "llm",
                        new QueryProperties.LlmRerankerProperties(
                                baseUrl, "qwen3:0.6b", candidateCount, timeoutSeconds, 500),
                        new QueryProperties.InfinityRerankerProperties(
                                "http://localhost:7997", "BAAI/bge-reranker-v2-m3", 30, 10)));
    }

    private LlmListwiseReranker rerankerFor(QueryProperties properties) {
        RestClient client = RestClient.builder().baseUrl(baseUrl).build();
        return new LlmListwiseReranker(properties, client);
    }

    private Document doc(String title, String text) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("pageTitle", title);
        return new Document(text, meta);
    }

    private void respondJson(HttpExchange exchange, String responseField) throws IOException {
        String json = "{\"model\":\"qwen3:0.6b\",\"response\":" + jsonString(responseField) + "}";
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, json.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json.getBytes());
        }
    }

    private String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private List<Document> sixCandidates() {
        return List.of(
                doc("Doc1", "alpha"),
                doc("Doc2", "beta"),
                doc("Doc3", "gamma"),
                doc("Doc4", "delta"),
                doc("Doc5", "epsilon"),
                doc("Doc6", "zeta"));
    }

    // -------- 1. Happy path --------

    @Test
    void rerank_happyPath_reordersByLlmRanking() {
        server.createContext("/api/generate", exchange -> respondJson(exchange, "[3, 1, 5]"));

        LlmListwiseReranker reranker = rerankerFor(props(15));
        List<Document> result = reranker.rerank("q", sixCandidates(), 3);

        assertEquals(3, result.size());
        assertEquals("Doc3", result.get(0).getMetadata().get("pageTitle"));
        assertEquals("Doc1", result.get(1).getMetadata().get("pageTitle"));
        assertEquals("Doc5", result.get(2).getMetadata().get("pageTitle"));
    }

    // -------- 2. Markdown wrap --------

    @Test
    void rerank_extractsArrayFromMarkdownCodeBlock() {
        server.createContext("/api/generate", exchange ->
                respondJson(exchange, "```json\n[2, 4]\n```"));

        LlmListwiseReranker reranker = rerankerFor(props(15));
        List<Document> result = reranker.rerank("q", sixCandidates(), 2);

        assertEquals(2, result.size());
        assertEquals("Doc2", result.get(0).getMetadata().get("pageTitle"));
        assertEquals("Doc4", result.get(1).getMetadata().get("pageTitle"));
    }

    // -------- 3. Explanation text before --------

    @Test
    void rerank_extractsArrayFromTextWithExplanation() {
        server.createContext("/api/generate", exchange ->
                respondJson(exchange, "Hier sind die relevantesten: [3, 6, 1] — viel Erfolg!"));

        LlmListwiseReranker reranker = rerankerFor(props(15));
        List<Document> result = reranker.rerank("q", sixCandidates(), 3);

        assertEquals(3, result.size());
        assertEquals("Doc3", result.get(0).getMetadata().get("pageTitle"));
        assertEquals("Doc6", result.get(1).getMetadata().get("pageTitle"));
        assertEquals("Doc1", result.get(2).getMetadata().get("pageTitle"));
    }

    // -------- 4. Out-of-range index --------

    @Test
    void rerank_skipsOutOfRangeIndices() {
        server.createContext("/api/generate", exchange -> respondJson(exchange, "[99, 2, 100, 4]"));

        LlmListwiseReranker reranker = rerankerFor(props(15));
        List<Document> result = reranker.rerank("q", sixCandidates(), 2);

        assertEquals(2, result.size());
        assertEquals("Doc2", result.get(0).getMetadata().get("pageTitle"));
        assertEquals("Doc4", result.get(1).getMetadata().get("pageTitle"));
    }

    // -------- 5. Duplicates --------

    @Test
    void rerank_dedupsRepeatedIndices() {
        server.createContext("/api/generate", exchange -> respondJson(exchange, "[1, 1, 2, 2, 3]"));

        LlmListwiseReranker reranker = rerankerFor(props(15));
        List<Document> result = reranker.rerank("q", sixCandidates(), 3);

        assertEquals(3, result.size());
        assertEquals("Doc1", result.get(0).getMetadata().get("pageTitle"));
        assertEquals("Doc2", result.get(1).getMetadata().get("pageTitle"));
        assertEquals("Doc3", result.get(2).getMetadata().get("pageTitle"));
    }

    // -------- 6. Too few indices → padded --------

    @Test
    void rerank_padsWithOriginalOrderWhenTooFewIndices() {
        // LLM only returns one valid index, topK=4 → must pad with original-order candidates
        server.createContext("/api/generate", exchange -> respondJson(exchange, "[5]"));

        LlmListwiseReranker reranker = rerankerFor(props(15));
        List<Document> result = reranker.rerank("q", sixCandidates(), 4);

        assertEquals(4, result.size());
        // First should be the LLM-picked Doc5
        assertEquals("Doc5", result.get(0).getMetadata().get("pageTitle"));
        // Then padded with original order, skipping Doc5 which is already used
        assertEquals("Doc1", result.get(1).getMetadata().get("pageTitle"));
        assertEquals("Doc2", result.get(2).getMetadata().get("pageTitle"));
        assertEquals("Doc3", result.get(3).getMetadata().get("pageTitle"));
    }

    // -------- 7. HTTP 500 error --------

    @Test
    void rerank_fallsBackOnHttpError() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/api/generate", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        LlmListwiseReranker reranker = rerankerFor(props(15));
        List<Document> result = reranker.rerank("q", sixCandidates(), 3);

        assertEquals(1, calls.get());
        assertEquals(3, result.size());
        assertEquals("Doc1", result.get(0).getMetadata().get("pageTitle"));
        assertEquals("Doc2", result.get(1).getMetadata().get("pageTitle"));
        assertEquals("Doc3", result.get(2).getMetadata().get("pageTitle"));
    }

    // -------- 8. Timeout --------

    @Test
    void rerank_fallsBackOnTimeout() {
        server.createContext("/api/generate", exchange -> {
            try {
                Thread.sleep(2500); // longer than timeout
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respondJson(exchange, "[1, 2, 3]");
        });

        LlmListwiseReranker reranker = rerankerFor(props(15, 1)); // 1s timeout
        List<Document> result = reranker.rerank("q", sixCandidates(), 3);

        // Should fall back to original order
        assertEquals(3, result.size());
        assertEquals("Doc1", result.get(0).getMetadata().get("pageTitle"));
    }

    // -------- 9. Garbage response --------

    @Test
    void rerank_fallsBackOnGarbageResponse() {
        server.createContext("/api/generate", exchange ->
                respondJson(exchange, "lorem ipsum dolor sit amet"));

        LlmListwiseReranker reranker = rerankerFor(props(15));
        List<Document> result = reranker.rerank("q", sixCandidates(), 3);

        // No valid array → mapToDocuments pads with original order
        assertEquals(3, result.size());
        assertEquals("Doc1", result.get(0).getMetadata().get("pageTitle"));
        assertEquals("Doc2", result.get(1).getMetadata().get("pageTitle"));
        assertEquals("Doc3", result.get(2).getMetadata().get("pageTitle"));
    }

    // -------- 10. Empty input --------

    @Test
    void rerank_returnsEmptyForEmptyInput() {
        LlmListwiseReranker reranker = rerankerFor(props(15));
        List<Document> result = reranker.rerank("q", List.of(), 5);
        assertTrue(result.isEmpty());
    }

    // -------- 11. Fewer candidates than topK → no LLM call --------

    @Test
    void rerank_skipsLlmCallWhenCandidatesUnderTopK() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/api/generate", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        LlmListwiseReranker reranker = rerankerFor(props(15));
        List<Document> candidates = List.of(doc("A", "x"), doc("B", "y"));
        List<Document> result = reranker.rerank("q", candidates, 5);

        assertEquals(0, calls.get());
        assertEquals(2, result.size());
    }

    // -------- candidateCount accessor --------

    @Test
    void candidateCount_returnsConfiguredValue() {
        LlmListwiseReranker reranker = rerankerFor(props(42));
        assertEquals(42, reranker.candidateCount());
    }

    // -------- Static parser unit tests --------

    @Test
    void parseJsonArray_handlesPlainArray() {
        assertEquals(List.of(3, 1, 5), LlmListwiseReranker.parseJsonArray("[3, 1, 5]"));
    }

    @Test
    void parseJsonArray_handlesArrayInText() {
        assertEquals(List.of(2, 4), LlmListwiseReranker.parseJsonArray("answer: [2, 4] done"));
    }

    @Test
    void parseJsonArray_returnsEmptyForGarbage() {
        assertTrue(LlmListwiseReranker.parseJsonArray("nothing here").isEmpty());
        assertTrue(LlmListwiseReranker.parseJsonArray("").isEmpty());
        assertTrue(LlmListwiseReranker.parseJsonArray(null).isEmpty());
    }
}
