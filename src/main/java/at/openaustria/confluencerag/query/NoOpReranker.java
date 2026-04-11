package at.openaustria.confluencerag.query;

import at.openaustria.confluencerag.config.QueryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Pass-through reranker — keeps the original vector-search order.
 *
 * Activated explicitly via {@code query.reranker.type=none}. Useful for
 * disabling reranking without compiling out the rest of the pipeline.
 */
@Service
@ConditionalOnProperty(prefix = "query.reranker", name = "type", havingValue = "none")
public class NoOpReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(NoOpReranker.class);

    private final int candidateCount;

    public NoOpReranker(QueryProperties queryProperties) {
        // No reranking → fetching more than topK from Qdrant is pointless
        this.candidateCount = queryProperties.topK();
        log.info("NoOpReranker aktiv: kein Reranking, candidateCount={}", candidateCount);
    }

    @Override
    public List<Document> rerank(String query, List<Document> candidates, int topK) {
        return candidates.stream().limit(topK).toList();
    }

    @Override
    public int candidateCount() {
        return candidateCount;
    }
}
