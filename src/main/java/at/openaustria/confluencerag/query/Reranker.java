package at.openaustria.confluencerag.query;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Reordering strategy applied to vector-search candidates before they
 * are passed to the LLM as context. Implementations choose how to
 * compute relevance scores (LLM listwise, cross-encoder, no-op, ...).
 *
 * Spring activates exactly one bean implementing this interface based
 * on {@code query.reranker.type} via {@code @ConditionalOnProperty}.
 */
public interface Reranker {

    /**
     * Re-rank the given candidates and return the top {@code topK}
     * documents in new order. Implementations must fall back to the
     * original order on any error to keep the query path safe.
     */
    List<Document> rerank(String query, List<Document> candidates, int topK);

    /**
     * Number of candidates the QueryService should fetch from Qdrant
     * before passing them to this reranker. Implementations have
     * different sweet spots: LLMs are limited by context window,
     * cross-encoders can handle more, no-op needs only top-K.
     */
    int candidateCount();
}
