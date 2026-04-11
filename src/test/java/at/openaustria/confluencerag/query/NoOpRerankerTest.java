package at.openaustria.confluencerag.query;

import at.openaustria.confluencerag.config.QueryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NoOpRerankerTest {

    private QueryProperties props(int topK) {
        return new QueryProperties(topK, 0.45,
                new QueryProperties.RerankerProperties(
                        "none",
                        new QueryProperties.LlmRerankerProperties(
                                "http://localhost:11434", "qwen3:0.6b", 15, 60, 500),
                        new QueryProperties.InfinityRerankerProperties(
                                "http://localhost:7997", "BAAI/bge-reranker-v2-m3", 30, 10)));
    }

    private Document doc(String title) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("pageTitle", title);
        return new Document(title + " text", meta);
    }

    @Test
    void rerank_returnsFirstTopKInOriginalOrder() {
        NoOpReranker reranker = new NoOpReranker(props(5));
        List<Document> candidates = List.of(
                doc("A"), doc("B"), doc("C"), doc("D"), doc("E"), doc("F"));

        List<Document> result = reranker.rerank("q", candidates, 3);

        assertEquals(3, result.size());
        assertEquals("A", result.get(0).getMetadata().get("pageTitle"));
        assertEquals("B", result.get(1).getMetadata().get("pageTitle"));
        assertEquals("C", result.get(2).getMetadata().get("pageTitle"));
    }

    @Test
    void rerank_returnsEmptyForEmptyInput() {
        NoOpReranker reranker = new NoOpReranker(props(5));
        assertTrue(reranker.rerank("q", List.of(), 5).isEmpty());
    }

    @Test
    void candidateCount_equalsTopK() {
        NoOpReranker reranker = new NoOpReranker(props(7));
        assertEquals(7, reranker.candidateCount());
    }
}
