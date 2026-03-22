package at.openaustria.confluencerag.ingestion;

import at.openaustria.confluencerag.config.ConfluenceProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

@Component
public class SyncStateRepository {

    private static final Logger log = LoggerFactory.getLogger(SyncStateRepository.class);
    private final Path stateFile;
    private final ObjectMapper mapper;

    public SyncStateRepository(ConfluenceProperties properties) {
        String path = properties.sync() != null && properties.sync().stateFile() != null
                ? properties.sync().stateFile() : "./data/sync-state.json";
        this.stateFile = Path.of(path);
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public Optional<Instant> getLastSync(String spaceKey) {
        return loadState().map(state -> state.get(spaceKey))
                .map(SpaceState::lastSync);
    }

    public Set<String> getKnownPageIds(String spaceKey) {
        return loadState()
                .map(state -> state.get(spaceKey))
                .map(SpaceState::knownPageIds)
                .orElse(Set.of());
    }

    public void updateLastSync(String spaceKey, Instant timestamp) {
        Map<String, SpaceState> state = loadState().orElse(new HashMap<>());
        SpaceState current = state.getOrDefault(spaceKey, new SpaceState(null, Set.of()));
        state.put(spaceKey, new SpaceState(timestamp, current.knownPageIds()));
        saveState(state);
    }

    public void updateKnownPageIds(String spaceKey, Set<String> pageIds) {
        Map<String, SpaceState> state = loadState().orElse(new HashMap<>());
        SpaceState current = state.getOrDefault(spaceKey, new SpaceState(null, Set.of()));
        state.put(spaceKey, new SpaceState(current.lastSync(), pageIds));
        saveState(state);
    }

    public Map<String, SpaceState> getAllStates() {
        return loadState().orElse(Map.of());
    }

    private Optional<Map<String, SpaceState>> loadState() {
        if (!Files.exists(stateFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(stateFile.toFile(),
                    new TypeReference<Map<String, SpaceState>>() {}));
        } catch (IOException e) {
            log.error("Sync-State konnte nicht geladen werden: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void saveState(Map<String, SpaceState> state) {
        try {
            Files.createDirectories(stateFile.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(), state);
        } catch (IOException e) {
            log.error("Sync-State konnte nicht gespeichert werden: {}", e.getMessage());
        }
    }

    public record SpaceState(Instant lastSync, Set<String> knownPageIds) {}
}
