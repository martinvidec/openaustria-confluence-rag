# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Requires Java 17+ (Homebrew: /opt/homebrew/opt/openjdk@17)
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

mvn clean compile                                    # Compile
mvn test                                             # Run all tests (43 tests)
mvn spring-boot:run -DskipTests                      # Start app (requires Qdrant + Ollama)
mvn test -pl . -Dtest=ConfluenceClientTest            # Run single test class
mvn test -pl . -Dtest=ConfluenceHtmlConverterTest     # Run converter tests
```

## Running Locally

```bash
# Infrastructure (Qdrant + Ollama + Reranker)
docker compose up -d qdrant ollama reranker

# First-time setup: pull embedding model (1.2 GB)
docker exec openaustria-confluence-rag-ollama-1 ollama pull bge-m3

# App with Basic Auth (local Confluence test, default chat model: gemma3:4b, embedding: bge-m3)
CONFLUENCE_USERNAME=admin CONFLUENCE_PASSWORD=admin CONFLUENCE_SPACES=ds,OP \
  mvn spring-boot:run -DskipTests

# Reranker can be disabled if container is not running
QUERY_RERANKER_ENABLED=false mvn spring-boot:run -DskipTests

# Local Confluence test instance
docker compose -f docker-compose.test.yml up -d      # Confluence 8.5 on port 8090
```

## Architecture

Single Spring Boot 3.4.3 application with Spring AI 1.0.0. Three logical layers:

1. **Crawler** (`crawler/`) — `ConfluenceClient` (PAT or Basic auth, pagination, retry) → `ConfluenceHtmlConverter` (Jsoup XML parser, MacroHandler strategy pattern for PlantUML etc.) → `AttachmentTextExtractor` (Tika) → `CrawlerService` orchestrates per-space crawl into `ConfluenceDocument` records.

2. **Ingestion** (`ingestion/`) — `ChunkingService` (TokenTextSplitter, 500 token/50 overlap) → `IngestionService` (parallel batch upsert to Qdrant, 50/batch, 2 threads, 1024-dim vectors for `bge-m3`) → `SyncService` (CQL delta queries, deleted page detection, JSON state file) → `SyncScheduler` (cron, disabled by default). Chunk deletion uses `QdrantClient.deleteAsync(filter)` directly (not `VectorStore.delete()` which only accepts IDs). Vector dimension is configurable via `ingestion.vector-dimension`.

3. **Query** (`query/`) — `QueryService` (similarity search → cross-encoder rerank via `RerankerService` → context build → Ollama ChatClient) with space filtering via Qdrant FilterExpression. Streaming via `Flux<String>`. `RerankerService` calls an external `michaelf34/infinity` container hosting `BAAI/bge-reranker-v2-m3`; falls back to vector order on errors and is toggleable via `query.reranker.enabled`.

**Web layer** (`web/`) — `ChatController` (POST /api/chat, POST /api/chat/stream SSE, GET /api/spaces), `AdminController` (ingest/sync triggers). Frontend is vanilla HTML/CSS/JS in `src/main/resources/static/`.

## Key Conventions

- Base package: `at.openaustria.confluencerag`
- Spring AI 1.0.0 artifact names: `spring-ai-starter-model-ollama`, `spring-ai-starter-vector-store-qdrant` (not the old `spring-ai-*-spring-boot-starter` names)
- Spring AI 1.0.0 API: `Document.getText()` (not `getContent()`), `SearchRequest.builder().query()` (not `SearchRequest.query()`)
- Spring AI 1.0.0 model config: `spring.ai.ollama.chat.model` (not under `options`)
- `ConfluenceHtmlConverter` uses Jsoup **XML parser** (`Parser.xmlParser()`) for `ac:structured-macro` namespace support
- All Confluence response DTOs use `@JsonIgnoreProperties(ignoreUnknown = true)` and `@JsonProperty("_links")` for links
- `ConfluenceVersion.by` is a nested `ConfluenceUser` object (not a String) — use `getAuthorName()` helper
- Auth supports both PAT (`Authorization: Bearer`) and Basic Auth (`username:password`) — configured via `ConfluenceProperties`
- Configuration via `ConfluenceProperties` record (`@ConfigurationProperties(prefix = "confluence")`)

## Diagnostic Scripts

Utility scripts under `scripts/` — see `scripts/README.md` for details.

- `retrieval-quality-check.py` — runs a set of test queries against the ingested Qdrant collection and prints per-query top matches with scores. Use when comparing embedding models, calibrating thresholds, or debugging "the sources are off" reports. No dependencies beyond Python 3 + running Ollama/Qdrant.

## Language Note

Documentation is in German. Code uses English identifiers.
