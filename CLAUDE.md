# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Requires Java 17+ (Homebrew: /opt/homebrew/opt/openjdk@17)
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

mvn clean compile          # Compile
mvn test                   # Run all tests
mvn spring-boot:run        # Start app (requires Qdrant + Ollama running)
mvn test -pl . -Dtest=ConfluenceClientTest    # Run single test class
```

Infrastructure: `docker compose up -d` starts Qdrant (6333) and Ollama (11434).

## Architecture

Single Spring Boot 3.4.3 application with Spring AI 1.0.0. Three logical layers:

1. **Crawler** (`crawler/`) — `ConfluenceClient` (PAT auth, pagination, retry) → `ConfluenceHtmlConverter` (Jsoup XML parser, MacroHandler strategy pattern for PlantUML etc.) → `AttachmentTextExtractor` (Tika) → `CrawlerService` orchestrates per-space crawl into `ConfluenceDocument` records.

2. **Ingestion** (`ingestion/`) — `ChunkingService` (TokenTextSplitter, 800 token/100 overlap) → `IngestionService` (batch upsert to Qdrant, 50/batch) → `SyncService` (CQL delta queries, deleted page detection, JSON state file) → `SyncScheduler` (cron, disabled by default).

3. **Query** (`query/`) — `QueryService` (similarity search → context build → Ollama ChatClient) with space filtering via Qdrant FilterExpression. Streaming via `Flux<String>`.

**Web layer** (`web/`) — `ChatController` (POST /api/chat, POST /api/chat/stream SSE, GET /api/spaces), `AdminController` (ingest/sync triggers). Frontend is vanilla HTML/CSS/JS in `src/main/resources/static/`.

## Key Conventions

- Base package: `at.openaustria.confluencerag`
- Spring AI 1.0.0 artifact names: `spring-ai-starter-model-ollama`, `spring-ai-starter-vector-store-qdrant` (not the old `spring-ai-*-spring-boot-starter` names)
- Spring AI 1.0.0 API: `Document.getText()` (not `getContent()`), `SearchRequest.builder().query()` (not `SearchRequest.query()`)
- `ConfluenceHtmlConverter` uses Jsoup **XML parser** (`Parser.xmlParser()`) for `ac:structured-macro` namespace support
- All Confluence response DTOs use `@JsonIgnoreProperties(ignoreUnknown = true)` and `@JsonProperty("_links")` for links
- Configuration via `ConfluenceProperties` record (`@ConfigurationProperties(prefix = "confluence")`)

## Language Note

Documentation is in German. Code uses English identifiers.
