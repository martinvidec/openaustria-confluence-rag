package at.openaustria.confluencerag.web;

import at.openaustria.confluencerag.config.ConfluenceProperties;
import at.openaustria.confluencerag.query.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    private final QueryService queryService;
    private final ConfluenceProperties properties;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.ollama.chat.model:gemma3:4b}")
    private String chatModel;

    @Value("${spring.ai.ollama.embedding.model:nomic-embed-text}")
    private String embeddingModel;

    public ChatController(QueryService queryService,
                          ConfluenceProperties properties,
                          ObjectMapper objectMapper) {
        this.queryService = queryService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/chat")
    public ResponseEntity<QueryResponse> chat(@RequestBody QueryRequest request) {
        QueryResponse response = queryService.query(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody QueryRequest request) {
        List<Source> sources = queryService.getSourcesForQuery(request);

        Flux<ServerSentEvent<String>> tokens = queryService.queryStream(request)
                .map(token -> ServerSentEvent.<String>builder()
                        .event("token")
                        .data(toJson(Map.of("token", token)))
                        .build());

        Flux<ServerSentEvent<String>> footer = Flux.just(
                ServerSentEvent.<String>builder()
                        .event("sources")
                        .data(toJson(Map.of("sources", sources)))
                        .build(),
                ServerSentEvent.<String>builder()
                        .event("done")
                        .data("{}")
                        .build()
        );

        return Flux.concat(tokens, footer);
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, String>> getConfig() {
        return ResponseEntity.ok(Map.of(
                "chatModel", chatModel,
                "embeddingModel", embeddingModel
        ));
    }

    @GetMapping("/spaces")
    public ResponseEntity<List<SpaceInfo>> getSpaces() {
        List<String> spaceKeys = properties.spaces();
        if (spaceKeys == null) {
            return ResponseEntity.ok(List.of());
        }
        List<SpaceInfo> spaces = spaceKeys.stream()
                .map(key -> new SpaceInfo(key, key))
                .toList();
        return ResponseEntity.ok(spaces);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
