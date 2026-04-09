# Spec: Cross-Encoder Reranker

**Datum:** 2026-04-09
**Status:** Spec
**Betroffene Dateien:**
- `src/main/java/at/openaustria/confluencerag/query/QueryService.java`
- `src/main/java/at/openaustria/confluencerag/query/RerankerService.java` (**neu**)
- `src/main/java/at/openaustria/confluencerag/config/QueryProperties.java`
- `src/main/resources/application.yml`
- `docker-compose.yml`
- `src/test/java/at/openaustria/confluencerag/query/RerankerServiceTest.java` (**neu**)

---

## 1. Ziel

Der aktuelle `rerankByKeywords()` in `QueryService` ist eine simple Heuristik (Keyword-Overlap mit 3x Gewichtung für Titel-Treffer). Das hilft bei exakten Begriffs-Fragen, versagt aber bei semantischen Anfragen.

**Ziel:** Einen echten Cross-Encoder-Reranker vor die Kontext-Erstellung schalten, der den Top-K-Kandidaten aus der Vektorsuche einen präzisen Relevance-Score zuweist und die wirklich relevanten Chunks nach oben sortiert.

### Warum Cross-Encoder?

Ein Bi-Encoder (= klassisches Embedding) codiert Query und Dokument **getrennt** und vergleicht die resultierenden Vektoren. Das ist schnell, aber unpräzise, weil das Modell die Interaktion zwischen Query und Dokument nicht sieht.

Ein **Cross-Encoder** nimmt `(query, document)` **gemeinsam** als Input und gibt einen Relevance-Score aus. Das ist deutlich präziser, aber langsamer — daher wird er nur auf einer kleinen Kandidatenmenge (z.B. Top-30 aus der Vektorsuche) angewendet, nicht auf der gesamten Collection.

Das ist der Industrie-Standard für RAG-Pipelines und bringt in Benchmarks typischerweise +10-25 Prozentpunkte Precision@5.

---

## 2. Modell-Auswahl: `bge-reranker-v2-m3`

| Kriterium | Wahl |
|-----------|------|
| Modell | **`bge-reranker-v2-m3`** |
| Größe | ~568 MB |
| Sprachen | multilingual (inkl. DE) |
| Lizenz | Apache 2.0 |
| API | HTTP/REST via eigenen Container |

### Problem: Ollama unterstützt keine Reranker-Modelle

Ollama kann aktuell nur Embedding- und Chat-Modelle hosten, keine Cross-Encoder. Optionen:

**Option A — Eigener Reranker-Container (präferiert)**
Wir hosten `bge-reranker-v2-m3` in einem eigenen leichten Container:
- [`infiniflow/infinity`](https://github.com/infiniflow/infinity) — sehr schnell, dediziert für Reranking
- [`michaelfeil/infinity`](https://github.com/michaelf/infinity) — HTTP API kompatibel zum OpenAI rerank-Format
- Eigener Python-Wrapper mit `sentence-transformers` + FastAPI

**Empfehlung:** `michaelfeil/infinity` via Docker, da bereits fertig, HTTP-API, läuft auch auf CPU.

```yaml
# docker-compose.yml
services:
  reranker:
    image: michaelfeil/infinity:latest
    ports:
      - "7997:7997"
    command: ["v2", "--model-id", "BAAI/bge-reranker-v2-m3", "--port", "7997"]
    volumes:
      - reranker_cache:/app/.cache
```

**Option B — Keine externe Dienste, Reranker als Java-Library**
Es existieren keine reifen Java-Ports für Cross-Encoder. DJL könnte theoretisch BGE-Modelle laden, aber der Aufwand ist hoch. → Verworfen.

**Option C — LLM-basierter Rerank (qwen3 oder gemma3)**
Ein kleines LLM wird gefragt: „Bewerte auf Skala 1-10 wie relevant dieser Chunk für die Frage ist". Funktioniert, ist aber deutlich langsamer als ein dedizierter Cross-Encoder. → Fallback, nicht präferiert.

**Entscheidung:** Option A mit `michaelfeil/infinity`.

---

## 3. Architektur

### 3.1 Flow

```
Frage
  │
  ▼
Vektorsuche (Qdrant) ──► Top-30 Kandidaten
                          │
                          ▼
                    Reranker Service ──HTTP──► infinity-container
                          │            (bge-reranker-v2-m3)
                          ▼
                    Top-5 Chunks (neu sortiert)
                          │
                          ▼
                    Kontext-Aufbau → LLM
```

### 3.2 Neue Komponente: `RerankerService`

**Datei:** `src/main/java/at/openaustria/confluencerag/query/RerankerService.java`

Verantwortlichkeiten:
- Sendet HTTP POST an `http://reranker:7997/rerank` mit `{query, documents[], top_n}`
- Parst die Antwort (`{results: [{index, relevance_score}]}`)
- Sortiert die Input-Dokumente entsprechend und gibt Top-K zurück
- **Fallback:** Bei HTTP-Fehlern / Timeout fällt auf die ursprüngliche Reihenfolge zurück (ohne Reranking), damit die App nicht bricht
- **Toggle:** Konfigurierbar via `query.reranker.enabled` (default: `true`)

```java
@Service
public class RerankerService {

    private static final Logger log = LoggerFactory.getLogger(RerankerService.class);
    private final RestClient restClient;
    private final QueryProperties queryProperties;

    public RerankerService(QueryProperties queryProperties) {
        this.queryProperties = queryProperties;
        this.restClient = RestClient.builder()
                .baseUrl(queryProperties.reranker().baseUrl())
                .build();
    }

    public List<Document> rerank(String query, List<Document> candidates, int topK) {
        if (!queryProperties.reranker().enabled() || candidates.isEmpty()) {
            return candidates.stream().limit(topK).toList();
        }

        try {
            List<String> texts = candidates.stream()
                    .map(this::buildRerankText)
                    .toList();

            RerankRequest req = new RerankRequest(
                    queryProperties.reranker().model(),
                    query,
                    texts,
                    topK
            );

            RerankResponse resp = restClient.post()
                    .uri("/rerank")
                    .body(req)
                    .retrieve()
                    .body(RerankResponse.class);

            return resp.results().stream()
                    .map(r -> candidates.get(r.index()))
                    .toList();
        } catch (Exception e) {
            log.warn("Reranker-Call fehlgeschlagen, fallback auf Vektor-Reihenfolge: {}", e.getMessage());
            return candidates.stream().limit(topK).toList();
        }
    }

    private String buildRerankText(Document doc) {
        // Titel + Text kombinieren, damit der Cross-Encoder den Titel sieht
        String title = (String) doc.getMetadata().getOrDefault("pageTitle", "");
        return title.isEmpty() ? doc.getText() : title + "\n" + doc.getText();
    }

    record RerankRequest(String model, String query, List<String> documents, Integer top_n) {}
    record RerankResponse(List<RerankResult> results) {}
    record RerankResult(int index, double relevance_score) {}
}
```

### 3.3 Integration in `QueryService`

Das bisherige `rerankByKeywords()` wird durch den Aufruf des `RerankerService` ersetzt.

```java
private final RerankerService rerankerService;

public QueryService(VectorStore vectorStore, ChatClient.Builder chatClientBuilder,
                    QueryProperties queryProperties, RerankerService rerankerService) {
    // ...
    this.rerankerService = rerankerService;
}

private List<Document> searchRelevantDocs(QueryRequest request) {
    int fetchK = Math.max(queryProperties.topK() * 3, 30);  // war: * 10, 100
    SearchRequest.Builder searchBuilder = SearchRequest.builder()
            .query(request.question())
            .topK(fetchK)
            .similarityThreshold(queryProperties.similarityThreshold());
    // ... Filter wie bisher ...

    List<Document> candidates = vectorStore.similaritySearch(searchBuilder.build());
    return rerankerService.rerank(request.question(), candidates, queryProperties.topK());
}
```

`rerankByKeywords()`, `extractKeywords()` und `STOP_WORDS` werden entfernt.

**Hinweis:** `fetchK` wird von 100 auf 30 reduziert. Grund: Der Cross-Encoder ist langsamer als die Keyword-Heuristik. 30 Kandidaten für bge-reranker-v2-m3 dauern CPU-seitig ca. 300-800ms, 100 wären 1-3 Sekunden. Die Präzision des Cross-Encoders macht die kleinere Kandidatenmenge wett.

### 3.4 `QueryProperties` erweitern

```java
@ConfigurationProperties(prefix = "query")
public record QueryProperties(
    int topK,
    double similarityThreshold,
    RerankerProperties reranker
) {
    public record RerankerProperties(
        boolean enabled,
        String baseUrl,
        String model,
        int candidateCount
    ) {}
}
```

### 3.5 `application.yml`

```yaml
query:
  top-k: ${QUERY_TOP_K:5}                        # reduziert von 10 → 5 (präziser nach Rerank)
  similarity-threshold: ${QUERY_SIMILARITY_THRESHOLD:0.45}
  reranker:
    enabled: ${QUERY_RERANKER_ENABLED:true}
    base-url: ${QUERY_RERANKER_URL:http://localhost:7997}
    model: ${QUERY_RERANKER_MODEL:BAAI/bge-reranker-v2-m3}
    candidate-count: ${QUERY_RERANKER_CANDIDATES:30}
```

### 3.6 `docker-compose.yml`

```yaml
services:
  reranker:
    image: michaelfeil/infinity:latest
    ports:
      - "7997:7997"
    environment:
      - INFINITY_MODEL_ID=BAAI/bge-reranker-v2-m3
    volumes:
      - reranker_cache:/app/.cache
    restart: unless-stopped

volumes:
  qdrant_data:
  ollama_data:
  reranker_cache:
```

---

## 4. Akzeptanzkriterien

- [ ] `RerankerService` existiert und ruft HTTP-API auf
- [ ] Fehler im Reranker-Call → Fallback auf Vektor-Reihenfolge, kein App-Crash
- [ ] Toggle über `query.reranker.enabled` funktioniert
- [ ] `rerankByKeywords()` entfernt aus `QueryService`
- [ ] Docker-Compose enthält Reranker-Service
- [ ] `top-k` auf 5 reduziert (Default)
- [ ] Unit-Tests für `RerankerService` (Mock HTTP-Client): Success-Path, Fehler-Fallback, disabled-Toggle
- [ ] Alle bestehenden Tests grün
- [ ] README/CLAUDE.md aktualisiert (Setup-Hinweis auf Reranker-Container)

---

## 5. Test-Plan

### 5.1 Unit-Tests — `RerankerServiceTest`

1. **Success-Path:** Mock HTTP-Response mit 3 Ergebnissen in neuer Reihenfolge → RerankerService liefert Dokumente in korrekter Reihenfolge
2. **HTTP-Error:** Mock 500 → Fallback auf originale Reihenfolge
3. **Timeout:** Mock langsame Antwort → Fallback auf originale Reihenfolge
4. **Disabled:** `enabled=false` → keine HTTP-Anfrage, einfach Top-K der Eingabe
5. **Leere Kandidaten:** gibt leere Liste zurück

### 5.2 Integration-Tests (manuell)

1. Reranker-Container starten: `docker compose up -d reranker`
2. App starten
3. Bekannte problematische Query testen (aus Erfahrungsschatz)
4. Vergleich: Ergebnisse mit `enabled=true` vs `enabled=false`

### 5.3 Performance-Messung

- Query-Latenz mit und ohne Reranker messen
- Erwartung: +200-800ms (je nach CPU-Leistung und Kandidaten-Anzahl)
- Akzeptanz-Kriterium: <1.5s Query-Latenz Gesamt (inkl. LLM-Streaming-Start)

---

## 6. Nicht im Scope

- Eigener Reranker-Container-Build (wir nutzen fertiges `michaelfeil/infinity` Image)
- GPU-Support für Reranker (CPU reicht für unsere Last)
- Caching von Rerank-Ergebnissen
- A/B-Test-Framework zum Vergleich Reranker an/aus

---

## 7. Abhängigkeit zu Issue #29 (Embedding-Upgrade)

Dieses Issue ist **unabhängig** von Issue bge-m3 lauffähig. Reihenfolge egal, aber sinnvoll ist:
1. Erst `bge-m3` einziehen (Basis verbessern)
2. Dann Reranker drauflegen (Feinschliff)

So ist der Effekt jedes einzelnen Schritts separat messbar.

---

## 8. Risiken

| Risiko | Mitigation |
|--------|-----------|
| Reranker-Container RAM-Bedarf (~2-3 GB) | In Setup-Doku festhalten, `restart: unless-stopped` |
| HTTP-Latenz schlechter als erwartet | Via `enabled=false` deaktivierbar; Fallback eingebaut |
| Infinity-Image ändert API-Format | Feste Image-Version pinnen (`michaelfeil/infinity:0.0.70`) |
| Bei sehr kurzen Chunks ist Reranker-Overhead > Benefit | `candidate-count` konfigurierbar, kann auf 10-20 reduziert werden |
