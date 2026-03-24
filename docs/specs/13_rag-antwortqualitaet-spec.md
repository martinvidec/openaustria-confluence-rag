# Spec: RAG-Antwortqualität optimieren

**Datum:** 2026-03-24
**Bezug:** [Ist-Analyse](13_rag-antwortqualitaet-ist-analyse.md) | [Requirements](13_rag-antwortqualitaet-requirements.md)
**Betroffene Dateien:**
- `ChunkingService.java` — Chunk-Anreicherung + Overlap
- `QueryService.java` — Top-K/Threshold konfigurierbar, Kontext-Aufbau, System Prompt
- `IngestionProperties.java` — (unverändert, `chunkOverlap` existiert bereits)
- `application.yml` — neue Query-Properties
- `ChunkingServiceTest.java` — Tests erweitern

---

## Änderung 1: Chunk-Text mit Titel, Pfad und Labels anreichern (REQ-01)

### Problem
Der Seitentitel ist nur in den Metadaten, nicht im Embedding-Text. Dadurch findet die Vektorsuche dokumentenbezogene Anfragen ("Spec 07") nicht.

### Lösung
In `ChunkingService.splitText()` wird jedem Chunk ein strukturierter Header vorangestellt, **bevor** er an den `TokenTextSplitter` übergeben wird.

**Nein** — der Header muss **nach** dem Splitting an jeden Chunk angefügt werden, da der Header sonst beim Splitting verloren gehen oder fragmentiert werden kann.

### Implementierung in `ChunkingService.java`

**Neue Methode `buildChunkHeader()`:**

```java
private String buildChunkHeader(Map<String, Object> metadata, String chunkType) {
    StringBuilder header = new StringBuilder();
    header.append("Titel: ").append(metadata.getOrDefault("pageTitle", "")).append("\n");

    String ancestors = (String) metadata.getOrDefault("ancestors", "");
    if (!ancestors.isEmpty()) {
        header.append("Pfad: ").append(ancestors).append("\n");
    }

    String labels = (String) metadata.getOrDefault("labels", "");
    if (!labels.isEmpty()) {
        header.append("Labels: ").append(labels).append("\n");
    }

    header.append("Typ: ").append(chunkType).append("\n\n");
    return header.toString();
}
```

**Änderung in `splitText()`** (Zeile 78-97):

```java
private List<Document> splitText(String text, Map<String, Object> baseMetadata, String chunkType) {
    Document sourceDoc = new Document(text);
    List<Document> split = textSplitter.apply(List.of(sourceDoc));

    String header = buildChunkHeader(baseMetadata, chunkType);

    List<Document> result = new ArrayList<>();
    for (Document chunk : split) {
        String chunkText = header + chunk.getText();
        // Hard limit: truncate chunks that are still too large
        if (chunkText.length() > MAX_CHUNK_CHARS) {
            log.warn("Chunk gekürzt: {} → {} Zeichen (Seite: {})",
                    chunkText.length(), MAX_CHUNK_CHARS,
                    baseMetadata.get("pageTitle"));
            chunkText = chunkText.substring(0, MAX_CHUNK_CHARS);
        }
        Map<String, Object> metadata = new HashMap<>(baseMetadata);
        metadata.put("chunkType", chunkType);
        result.add(new Document(chunkText, metadata));
    }
    return result;
}
```

### Auswirkung
- Jeder Chunk-Vektor enthält den Seitentitel → "Spec 07"-Anfragen matchen auf Chunks der Seite "Spec 07"
- Labels und Ancestors verbessern die Disambiguierung
- **Re-Ingest aller Dokumente erforderlich** nach Deployment

---

## Änderung 2: Chunk-Overlap implementieren (REQ-02)

### Problem
Spring AI 1.0.0 `TokenTextSplitter` unterstützt **keinen Overlap-Parameter** — weder im Konstruktor `(int, int, int, int, boolean)` noch im Builder. Der konfigurierte `chunkOverlap=50` ist ein toter Wert.

### Lösung
Eigene Overlap-Logik als Post-Processing nach dem `TokenTextSplitter`:

### Implementierung in `ChunkingService.java`

**Neue Methode `applyOverlap()`:**

```java
private List<String> applyOverlap(List<String> chunks, int overlapTokens) {
    if (chunks.size() <= 1 || overlapTokens <= 0) {
        return chunks;
    }

    // Einfache zeichenbasierte Overlap-Approximation:
    // 1 Token ≈ 4 Zeichen (englisch) / 3 Zeichen (deutsch)
    int overlapChars = overlapTokens * 3;

    List<String> overlapped = new ArrayList<>();
    overlapped.add(chunks.get(0)); // Erster Chunk bleibt unverändert

    for (int i = 1; i < chunks.size(); i++) {
        String prevChunk = chunks.get(i - 1);
        String currentChunk = chunks.get(i);

        // Suffix des vorherigen Chunks als Overlap-Prefix
        int overlapStart = Math.max(0, prevChunk.length() - overlapChars);
        String overlapText = prevChunk.substring(overlapStart);
        overlapped.add(overlapText + "\n" + currentChunk);
    }
    return overlapped;
}
```

**Integration in `splitText()`:**

```java
Document sourceDoc = new Document(text);
List<Document> split = textSplitter.apply(List.of(sourceDoc));

// Overlap anwenden
List<String> chunkTexts = split.stream().map(Document::getText).toList();
List<String> overlappedTexts = applyOverlap(chunkTexts, chunkOverlap);
```

**Konstruktor anpassen:**

```java
private final int chunkOverlap;

public ChunkingService(IngestionProperties properties) {
    int chunkSize = properties.chunkSize();
    this.chunkOverlap = properties.chunkOverlap();
    log.info("ChunkingService initialisiert: chunkSize={}, chunkOverlap={}, maxChunkChars={}",
            chunkSize, chunkOverlap, MAX_CHUNK_CHARS);
    // ... TokenTextSplitter wie bisher
}
```

---

## Änderung 3: Konfigurierbarer Top-K und Similarity Threshold (REQ-03)

### Implementierung

**Neue `QueryProperties.java`:**

```java
package at.openaustria.confluencerag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "query")
public record QueryProperties(
    int topK,
    double similarityThreshold
) {}
```

**`application.yml` ergänzen:**

```yaml
query:
  top-k: ${QUERY_TOP_K:10}
  similarity-threshold: ${QUERY_SIMILARITY_THRESHOLD:0.65}
```

**`QueryService.java` anpassen:**

```java
private final QueryProperties queryProperties;

public QueryService(VectorStore vectorStore, ChatClient.Builder chatClientBuilder,
                    QueryProperties queryProperties) {
    this.vectorStore = vectorStore;
    this.chatClient = chatClientBuilder.build();
    this.queryProperties = queryProperties;
}

private List<Document> searchRelevantDocs(QueryRequest request) {
    SearchRequest.Builder searchBuilder = SearchRequest.builder()
            .query(request.question())
            .topK(queryProperties.topK())
            .similarityThreshold(queryProperties.similarityThreshold());
    // ... Filter wie bisher
}
```

**`@EnableConfigurationProperties` erweitern** (in Main-Klasse oder Config):

`QueryProperties.class` hinzufügen.

---

## Änderung 4: Verbesserter System Prompt (REQ-04)

### Neuer System Prompt in `QueryService.java`

```java
static final String SYSTEM_PROMPT = """
    Du bist ein hilfreicher Assistent der Fragen basierend auf der internen
    Confluence-Dokumentation beantwortet.

    Regeln:
    - Beantworte die Frage NUR basierend auf dem bereitgestellten Kontext.
    - Wenn der Kontext die Frage nicht beantwortet, sage das ehrlich.
    - Nenne KEINE Quellen in deiner Antwort. Die Quellen werden automatisch angezeigt.
    - Antworte in der Sprache der Frage.
    - Fasse den relevanten Kontext zusammen, zitiere nicht wörtlich.

    Kontext-Interpretation:
    - Jeder Kontext-Block beginnt mit Metadaten (Titel, Pfad, Labels, Typ).
    - Verwende den TITEL als primäres Zuordnungskriterium: Wenn nach einem
      bestimmten Dokument gefragt wird (z.B. "Spec 07"), beziehe dich nur auf
      Kontext-Blöcke, deren Titel dazu passt.
    - Unterscheide klar zwischen verschiedenen Dokumenttypen: Specs, Issues,
      Protokolle, etc. sind verschiedene Dokumente, auch wenn sie dieselbe
      Nummer tragen.
    - Typ "PAGE" ist der Hauptinhalt, "COMMENT" sind Diskussionen zur Seite,
      "ATTACHMENT" sind angehängte Dateien.

    Kontext:
    %s
    """;
```

---

## Änderung 5: Kontext-Aufbau verbessern (REQ-05)

### Implementierung in `QueryService.java`

```java
private String buildContext(List<Document> relevantDocs) {
    return relevantDocs.stream()
            .map(doc -> {
                String title = (String) doc.getMetadata().getOrDefault("pageTitle", "");
                String space = (String) doc.getMetadata().getOrDefault("spaceKey", "");
                String ancestors = (String) doc.getMetadata().getOrDefault("ancestors", "");
                String chunkType = (String) doc.getMetadata().getOrDefault("chunkType", "PAGE");
                String typLabel = switch (chunkType) {
                    case "COMMENT" -> "Kommentar";
                    case "ATTACHMENT" -> "Anhang";
                    default -> "Seite";
                };

                StringBuilder header = new StringBuilder();
                header.append("--- Quelle: ").append(title);
                header.append(" (Space: ").append(space);
                if (!ancestors.isEmpty()) {
                    header.append(", Pfad: ").append(ancestors);
                }
                header.append(", Typ: ").append(typLabel);
                header.append(") ---\n");
                header.append(doc.getText());
                return header.toString();
            })
            .collect(Collectors.joining("\n\n"));
}
```

---

## Zusammenfassung der Dateiänderungen

| Datei | Änderung |
|-------|----------|
| `ChunkingService.java` | `buildChunkHeader()` hinzufügen, `splitText()` anpassen, `applyOverlap()` hinzufügen, Overlap aus Properties nutzen |
| `QueryService.java` | `QueryProperties` injizieren, Top-K/Threshold konfigurierbar, System Prompt erweitern, `buildContext()` erweitern |
| `QueryProperties.java` | **Neu:** Record mit `topK` und `similarityThreshold` |
| `application.yml` | `query.top-k` und `query.similarity-threshold` hinzufügen |
| Main-Klasse / Config | `@EnableConfigurationProperties` um `QueryProperties.class` erweitern |
| `ChunkingServiceTest.java` | Tests für Header-Anreicherung und Overlap |

---

## Migrations-Hinweis

Nach Deployment muss ein **vollständiger Re-Ingest** durchgeführt werden (`POST /api/admin/ingest`), da:
1. Chunk-Texte sich durch den Header ändern → neue Embeddings
2. Overlap neue Chunk-Grenzen erzeugt
3. Alte Chunks ohne Header in Qdrant verbleiben würden

---

## Test-Plan

1. **Unit-Test: Chunk-Header** — Verifiziere, dass `buildChunkHeader()` den korrekten Header generiert
2. **Unit-Test: Chunk-Anreicherung** — Verifiziere, dass nach `splitText()` jeder Chunk mit dem Header beginnt
3. **Unit-Test: Overlap** — Verifiziere, dass `applyOverlap()` den Suffix des vorherigen Chunks als Prefix anfügt
4. **Unit-Test: QueryProperties** — Verifiziere, dass Top-K und Threshold aus Properties gelesen werden
5. **Integration-Test: End-to-End** — Ingest einer Seite "Spec 07", Abfrage "Was steht in Spec 07?" → Chunks der richtigen Seite in Ergebnissen
