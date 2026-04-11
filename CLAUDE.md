# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Requires Java 17+ (Homebrew: /opt/homebrew/opt/openjdk@17)
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

mvn clean compile                                    # Compile
mvn test                                             # Run all tests (67 tests)
mvn spring-boot:run -DskipTests                      # Start app (requires Qdrant + Ollama)
mvn test -pl . -Dtest=ConfluenceClientTest            # Run single test class
mvn test -pl . -Dtest=ConfluenceHtmlConverterTest     # Run converter tests
```

## Running Locally

```bash
# Infrastructure (Qdrant + Ollama)
docker compose up -d qdrant ollama

# First-time setup: pull embedding model (1.2 GB) and a small LLM for reranking
docker exec openaustria-confluence-rag-ollama-1 ollama pull bge-m3
docker exec openaustria-confluence-rag-ollama-1 ollama pull qwen3:0.6b   # default reranker model

# App with Basic Auth (local Confluence test, default chat model: gemma3:4b, embedding: bge-m3)
CONFLUENCE_USERNAME=admin CONFLUENCE_PASSWORD=admin CONFLUENCE_SPACES=ds,OP \
  mvn spring-boot:run -DskipTests

# Switch reranker mode (default is llm)
QUERY_RERANKER_TYPE=none mvn spring-boot:run -DskipTests        # disable reranking
QUERY_RERANKER_TYPE=infinity mvn spring-boot:run -DskipTests    # use external infinity container

# Local Confluence test instance
docker compose -f docker-compose.test.yml up -d      # Confluence 8.5 on port 8090
```

## Architecture

Single Spring Boot 3.4.3 application with Spring AI 1.0.0. Three logical layers:

1. **Crawler** (`crawler/`) — `ConfluenceClient` (PAT or Basic auth, pagination, retry) → `ConfluenceHtmlConverter` (Jsoup XML parser, MacroHandler strategy pattern for PlantUML etc.) → `AttachmentTextExtractor` (Tika) → `CrawlerService` orchestrates per-space crawl into `ConfluenceDocument` records.

2. **Ingestion** (`ingestion/`) — `ChunkingService` (TokenTextSplitter, 500 token/50 overlap) → `IngestionService` (parallel batch upsert to Qdrant, 50/batch, 2 threads, 1024-dim vectors for `bge-m3`) → `SyncService` (CQL delta queries, deleted page detection, JSON state file) → `SyncScheduler` (cron, disabled by default). Chunk deletion uses `QdrantClient.deleteAsync(filter)` directly (not `VectorStore.delete()` which only accepts IDs). Vector dimension is configurable via `ingestion.vector-dimension`.

3. **Query** (`query/`) — `QueryService` (similarity search → reranker → context build → Ollama ChatClient) with space filtering via Qdrant FilterExpression. Streaming via `Flux<String>`. Reranking is pluggable via the `Reranker` interface with three implementations selected at startup via `@ConditionalOnProperty`:
   - `LlmListwiseReranker` (default, `query.reranker.type=llm`) — sends top-N candidates to a small Ollama LLM (default `qwen3:0.6b`) in a single listwise prompt, parses JSON-array output. No extra container needed.
   - `InfinityCrossEncoderReranker` (`type=infinity`) — calls external `michaelf34/infinity` container hosting `BAAI/bge-reranker-v2-m3`. Higher precision, requires the ~4.5 GB image.
   - `NoOpReranker` (`type=none`) — pass-through, vector order only.

   All three fall back to vector order on errors. `QueryService` depends only on the `Reranker` interface.

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
- **Never create `.mvn/jvm.config`** — it is picked up by every `mvn` invocation including CI. An early version of this repo had one with a hardcoded macOS `-Djava.home=/opt/homebrew/opt/openjdk@17/...` that silently broke every Linux build with cryptic `Error loading java.security file` errors (fixed in da8de1b). Local dev on macOS uses `JAVA_HOME` env var; no `.mvn/` config needed.

## Release Process

Releases are cut by pushing a `v*` git tag. The `.github/workflows/release.yml` workflow then builds the fat JAR, runs `packaging/assemble.sh` to produce `confluence-rag-<version>-unix.tar.gz` and `-windows.zip`, and creates a GitHub Release with both artifacts. The public install scripts (`install.sh` / `install.ps1`) find the latest release via the GitHub API.

```bash
# 1. Set release version in pom.xml
mvn versions:set -DnewVersion=0.2.0 -DgenerateBackupPoms=false
git commit -am "release: v0.2.0"

# 2. Tag and push — this triggers the release workflow
git tag v0.2.0
git push origin main v0.2.0

# 3. After release, bump back to next SNAPSHOT
mvn versions:set -DnewVersion=0.3.0-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "chore: Bump version to 0.3.0-SNAPSHOT"
git push origin main
```

If CI is still failing before the workflow reaches `Build Fat JAR`, the tag can be safely force-moved (`git tag -d v0.X.Y && git push origin :refs/tags/v0.X.Y && git tag v0.X.Y && git push origin v0.X.Y`) as long as the release has not been published yet. Once a release is public, don't move the tag — cut a patch release instead.

## Diagnostic Scripts

Utility scripts under `scripts/` — see `scripts/README.md` for details.

- `retrieval-quality-check.py` — runs a set of test queries against the ingested Qdrant collection and prints per-query top matches with scores. Use when comparing embedding models, calibrating thresholds, or debugging "the sources are off" reports. No dependencies beyond Python 3 + running Ollama/Qdrant.

## Language Note

Documentation is in German. Code uses English identifiers.
