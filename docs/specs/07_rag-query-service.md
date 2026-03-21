# Spec 07: RAG Query Service

**Issues:** #11, #12
**Phase:** 4
**Abhängigkeiten:** #9 (Qdrant muss befüllt sein)

---

## Ziel

REST-API die Benutzerfragen mit Kontext aus dem Qdrant VectorStore beantwortet. Synchrone und Streaming-Variante. Quellenangaben in der Response.

## Package

`at.openaustria.confluencerag.query`

---

## Issue #11 — RAG Query Pipeline

### Ablauf

```
1. User-Frage empfangen (+ optionaler Space-Filter)
2. Frage → Embedding via Ollama
3. Similarity Search in Qdrant (Top-K=5, mit optionalem Metadata-Filter)
4. Kontext-Prompt bauen (System-Prompt + gefundene Chunks)
5. LLM-Aufruf via Ollama ChatClient
6. Quellen aus den Chunks extrahieren (dedupliziert nach pageId)
7. Antwort + Quellen zurückgeben
```

### QueryService

```java
@Service
public class QueryService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    /**
     * Synchrone RAG-Abfrage.
     */
    public QueryResponse query(QueryRequest request);

    /**
     * Streaming RAG-Abfrage — gibt Flux<String> für SSE zurück.
     * Quellen werden am Ende als separates Event gesendet.
     */
    public Flux<ServerSentEvent<String>> queryStream(QueryRequest request);
}
```

### Similarity Search

```java
SearchRequest searchRequest = SearchRequest.builder()
    .query(question)
    .topK(5)
    .similarityThreshold(0.5)  // Mindest-Relevanz
    .filterExpression(buildFilter(spaceFilter))
    .build();

List<Document> relevantDocs = vectorStore.similaritySearch(searchRequest);
```

### Filter-Expression

```java
private FilterExpression buildFilter(List<String> spaceKeys) {
    if (spaceKeys == null || spaceKeys.isEmpty()) {
        return null;  // kein Filter → alle Spaces
    }
    if (spaceKeys.size() == 1) {
        return new FilterExpressionBuilder()
            .eq("spaceKey", spaceKeys.get(0))
            .build();
    }
    return new FilterExpressionBuilder()
        .in("spaceKey", spaceKeys)
        .build();
}
```

### System-Prompt

```java
private static final String SYSTEM_PROMPT = """
    Du bist ein hilfreicher Assistent der Fragen basierend auf der internen
    Confluence-Dokumentation beantwortet.

    Regeln:
    - Beantworte die Frage NUR basierend auf dem bereitgestellten Kontext.
    - Wenn der Kontext die Frage nicht beantwortet, sage das ehrlich.
    - Nenne am Ende deiner Antwort die relevanten Quellen mit Seitentitel.
    - Antworte in der Sprache der Frage.
    - Fasse den relevanten Kontext zusammen, zitiere nicht wörtlich.

    Kontext:
    {context}
    """;
```

### Kontext-Aufbau

```java
private String buildContext(List<Document> relevantDocs) {
    return relevantDocs.stream()
        .map(doc -> String.format(
            "--- Quelle: %s (Space: %s) ---\n%s",
            doc.getMetadata().get("pageTitle"),
            doc.getMetadata().get("spaceKey"),
            doc.getContent()
        ))
        .collect(Collectors.joining("\n\n"));
}
```

### Quellenextraktion

Quellen werden aus den Metadaten der gefundenen Chunks extrahiert und nach `pageId` dedupliziert:

```java
private List<Source> extractSources(List<Document> relevantDocs) {
    return relevantDocs.stream()
        .map(doc -> new Source(
            (String) doc.getMetadata().get("pageTitle"),
            (String) doc.getMetadata().get("pageUrl"),
            (String) doc.getMetadata().get("spaceKey"),
            // Relevanz-Score ist im SearchResult enthalten
        ))
        .distinct()  // nach pageUrl deduplizieren
        .toList();
}
```

---

## Issue #12 — REST API & SSE Streaming

### DTOs

```java
public record QueryRequest(
    String question,
    List<String> spaceFilter    // optional, null = alle Spaces
) {}

public record QueryResponse(
    String answer,
    List<Source> sources,
    long durationMs
) {}

public record Source(
    String title,
    String url,
    String spaceKey
) {}
```

### ChatController

```java
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")  // MVP: CORS offen; Produktion einschränken
public class ChatController {

    private final QueryService queryService;

    /**
     * Synchrone Abfrage — wartet auf vollständige Antwort.
     */
    @PostMapping("/chat")
    public ResponseEntity<QueryResponse> chat(@RequestBody QueryRequest request);

    /**
     * Streaming-Abfrage via Server-Sent Events.
     *
     * Events:
     * - type "token":   data: {"token": "..."}       (einzelne Token)
     * - type "sources": data: {"sources": [...]}     (am Ende, einmalig)
     * - type "done":    data: {}                      (Stream beendet)
     * - type "error":   data: {"message": "..."}     (bei Fehler)
     */
    @PostMapping("/chat/stream")
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody QueryRequest request);

    /**
     * Verfügbare Spaces für den Filter.
     */
    @GetMapping("/spaces")
    public ResponseEntity<List<SpaceInfo>> getSpaces();
}
```

### SSE Event-Struktur

```
event: token
data: {"token": "Die"}

event: token
data: {"token": " API"}

event: token
data: {"token": "-Dokumentation"}

...

event: sources
data: {"sources": [{"title": "API-Doku", "url": "https://...", "spaceKey": "DEV"}]}

event: done
data: {}
```

### SpaceInfo

```java
public record SpaceInfo(
    String key,
    String name
) {}
```

Die Space-Liste kommt aus der Konfiguration (`confluence.spaces`). Optional: aus Qdrant die tatsächlich vorhandenen `spaceKey`-Werte aggregieren.

### Streaming-Implementierung

```java
public Flux<ServerSentEvent<String>> queryStream(QueryRequest request) {
    // 1. Similarity Search (synchron, schnell)
    List<Document> relevantDocs = searchRelevantDocs(request);
    List<Source> sources = extractSources(relevantDocs);
    String context = buildContext(relevantDocs);

    // 2. LLM Streaming-Aufruf
    Flux<String> tokenStream = chatClient.prompt()
        .system(SYSTEM_PROMPT.replace("{context}", context))
        .user(request.question())
        .stream()
        .content();

    // 3. Token-Events + Sources-Event + Done-Event
    Flux<ServerSentEvent<String>> tokens = tokenStream
        .map(token -> ServerSentEvent.<String>builder()
            .event("token")
            .data("{\"token\": \"" + escapeJson(token) + "\"}")
            .build());

    Flux<ServerSentEvent<String>> footer = Flux.just(
        ServerSentEvent.<String>builder()
            .event("sources")
            .data(objectMapper.writeValueAsString(Map.of("sources", sources)))
            .build(),
        ServerSentEvent.<String>builder()
            .event("done")
            .data("{}")
            .build()
    );

    return Flux.concat(tokens, footer);
}
```

### CORS

Für das MVP CORS global öffnen. In Produktion auf die Frontend-URL einschränken:

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins("*")
            .allowedMethods("GET", "POST");
    }
}
```

---

## Akzeptanzkriterien

### Issue #11

- [ ] Frage wird mit Kontext aus Qdrant beantwortet
- [ ] Antwort enthält relevante Informationen aus den Chunks
- [ ] Space-Filter schränkt die Suche korrekt ein
- [ ] Quellen werden dedupliziert und korrekt extrahiert
- [ ] Bei keinen relevanten Ergebnissen wird das kommuniziert

### Issue #12

- [ ] `POST /api/chat` liefert JSON mit answer + sources + durationMs
- [ ] `POST /api/chat/stream` liefert SSE mit token/sources/done Events
- [ ] `GET /api/spaces` liefert die verfügbaren Spaces
- [ ] CORS ist konfiguriert
- [ ] Fehler werden als Error-Event (SSE) bzw. HTTP 500 mit Message zurückgegeben
- [ ] Manuelle Tests mit curl/Postman bestätigen korrekte Funktion
