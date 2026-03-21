# Spec 01: Projekt-Setup & Infrastruktur

**Issues:** #1, #2
**Phase:** 1

---

## Issue #1 — Spring Boot Projekt & Dependencies

### Ziel

Lauffähiges Spring Boot 3.x Projekt mit allen benötigten Dependencies. Package-Struktur steht, Anwendung kompiliert und startet.

### Projektstruktur

```
confluence-rag/
├── src/main/java/at/openaustria/confluencerag/
│   ├── ConfluenceRagApplication.java
│   ├── config/                    # Spring Configuration Klassen
│   ├── crawler/                   # Phase 2: Confluence Crawler
│   │   ├── client/                # REST API Client
│   │   ├── converter/             # HTML → Plaintext
│   │   └── model/                 # Crawler DTOs
│   ├── ingestion/                 # Phase 3: Document Processing
│   ├── query/                     # Phase 4: RAG Query Service
│   └── web/                       # Phase 4+5: REST Controller
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   └── static/                    # Phase 5: Frontend
├── src/test/java/at/openaustria/confluencerag/
├── docker-compose.yml
├── pom.xml (oder build.gradle.kts)
└── README.md
```

### Base Package

`at.openaustria.confluencerag`

### Dependencies (Maven)

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.x</version>  <!-- aktuelle stabile 3.4.x Version -->
</parent>

<properties>
    <java.version>17</java.version>
    <spring-ai.version>1.0.x</spring-ai.version> <!-- aktuelle stabile 1.0.x -->
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- Spring Boot -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    <!-- Spring AI: Ollama (Embedding + Chat) -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-ollama-spring-boot-starter</artifactId>
    </dependency>

    <!-- Spring AI: Qdrant VectorStore -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-qdrant-store-spring-boot-starter</artifactId>
    </dependency>

    <!-- Spring AI: Tika Document Reader (PDF-Extraktion) -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-tika-document-reader</artifactId>
    </dependency>

    <!-- HTML Parsing -->
    <dependency>
        <groupId>org.jsoup</groupId>
        <artifactId>jsoup</artifactId>
        <version>1.18.x</version> <!-- aktuelle stabile Version -->
    </dependency>

    <!-- Test -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### application.yml (Grundgerüst)

```yaml
spring:
  application:
    name: confluence-rag

# Platzhalter — werden in Issue #2 befüllt
confluence:
  base-url: ${CONFLUENCE_BASE_URL:http://localhost:8090}
  pat: ${CONFLUENCE_PAT:}
  spaces: []
```

### Akzeptanzkriterien

- [ ] `mvn clean compile` läuft ohne Fehler
- [ ] `mvn spring-boot:run` startet die Anwendung (darf beim Verbindungsversuch zu Qdrant/Ollama scheitern — das ist OK)
- [ ] Package-Struktur ist angelegt (leere Packages mit `package-info.java` oder Platzhalter-Klassen)

---

## Issue #2 — Docker Compose & Konfiguration

### Ziel

Lokale Infrastruktur per Docker Compose. Qdrant und Ollama laufen mit vorgeladenen Modellen. Spring Boot Konfiguration ist vollständig.

### docker-compose.yml

```yaml
services:
  qdrant:
    image: qdrant/qdrant:latest
    ports:
      - "6333:6333"   # REST API
      - "6334:6334"   # gRPC
    volumes:
      - qdrant_data:/qdrant/storage
    environment:
      QDRANT__SERVICE__GRPC_PORT: 6334

  ollama:
    image: ollama/ollama:latest
    ports:
      - "11434:11434"
    volumes:
      - ollama_data:/root/.ollama
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia        # entfernen wenn keine GPU
              count: all
              capabilities: [gpu]

volumes:
  qdrant_data:
  ollama_data:
```

### Ollama Modelle

Nach `docker compose up` müssen die Modelle geladen werden:

```bash
# Embedding-Modell
docker compose exec ollama ollama pull nomic-embed-text

# Chat-Modell (Auswahl je nach verfügbarem RAM/VRAM)
docker compose exec ollama ollama pull llama3       # 8B, ~4.7GB, braucht ~8GB RAM
# ODER
docker compose exec ollama ollama pull mistral      # 7B, ~4.1GB
# ODER
docker compose exec ollama ollama pull gemma2:9b    # 9B, ~5.4GB
```

**Empfehlung MVP:** `nomic-embed-text` (Embedding, 768 Dimensionen) + `llama3` (Chat).

### application.yml (vollständig)

```yaml
spring:
  application:
    name: confluence-rag
  ai:
    ollama:
      base-url: ${OLLAMA_BASE_URL:http://localhost:11434}
      chat:
        options:
          model: ${OLLAMA_CHAT_MODEL:llama3}
          temperature: 0.3
      embedding:
        options:
          model: ${OLLAMA_EMBEDDING_MODEL:nomic-embed-text}
    vectorstore:
      qdrant:
        host: ${QDRANT_HOST:localhost}
        port: ${QDRANT_GRPC_PORT:6334}
        collection-name: confluence-chunks
        initialize-schema: true

confluence:
  base-url: ${CONFLUENCE_BASE_URL:http://localhost:8090}
  pat: ${CONFLUENCE_PAT:}
  spaces: ${CONFLUENCE_SPACES:}   # Komma-separiert: DEV,OPS,TEAM
  sync:
    cron: "0 */30 * * * *"        # alle 30 Minuten
    enabled: false                 # erst aktivieren wenn Crawler fertig

server:
  port: 8080
```

### application-dev.yml

```yaml
logging:
  level:
    at.openaustria.confluencerag: DEBUG
    org.springframework.ai: DEBUG
```

### Configuration Properties Klasse

```java
@ConfigurationProperties(prefix = "confluence")
public record ConfluenceProperties(
    String baseUrl,
    String pat,
    List<String> spaces,
    SyncProperties sync
) {
    public record SyncProperties(
        String cron,
        boolean enabled
    ) {}
}
```

### Akzeptanzkriterien

- [ ] `docker compose up -d` startet Qdrant und Ollama ohne Fehler
- [ ] Qdrant Dashboard erreichbar unter `http://localhost:6333/dashboard`
- [ ] `curl http://localhost:11434/api/tags` zeigt die geladenen Modelle
- [ ] Spring Boot Anwendung startet und verbindet sich mit Qdrant und Ollama
- [ ] Actuator Health-Endpunkt (`/actuator/health`) zeigt Status
