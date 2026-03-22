# Confluence RAG

KI-gestütztes Q&A über Confluence On-Premise Inhalte. Extrahiert Seiten, Kommentare und Attachments via REST API, verarbeitet sie in einer RAG-Pipeline und beantwortet Fragen über ein Chat-Interface mit Quellenangaben.

## Features

- **Confluence Crawler** — Extraktion via REST API mit PAT- oder Basic-Auth, inkl. PlantUML-Makros, Kommentare und PDF-Attachments
- **Inkrementeller Sync** — Nur geänderte Seiten werden erneut verarbeitet (CQL-basiert)
- **RAG-Pipeline** — Chunking, Embedding und Similarity Search über Qdrant
- **Chat-Interface** — Streaming-Antworten mit Quellenangaben und Space-Filter
- **Vollständig On-Premise** — LLM und Embedding via Ollama, kein Cloud-Zwang

## Tech Stack

| Schicht | Technologie |
|---|---|
| Sprache | Java 17+ |
| Framework | Spring Boot 3.4.3 + Spring AI 1.0.0 |
| HTML-Parsing | Jsoup |
| PDF-Extraktion | Apache Tika |
| VectorStore | Qdrant |
| LLM & Embedding | Ollama (z.B. mistral + nomic-embed-text) |
| Frontend | Vanilla HTML/CSS/JS (kein Node.js nötig) |
| Infrastruktur | Docker Compose |

## Voraussetzungen

- Java 17+
- Docker + Docker Compose
- Confluence On-Premise (5.5+) mit PAT oder Basic Auth
- GPU empfohlen (für Ollama), CPU funktioniert auch

## Schnellstart

### 1. Repository klonen

```bash
git clone https://github.com/martinvidec/openaustria-confluence-rag.git
cd openaustria-confluence-rag
```

### 2. Infrastruktur starten

```bash
docker compose up -d qdrant ollama
```

### 3. Ollama-Modelle laden

```bash
# Embedding-Modell (erforderlich)
docker compose exec ollama ollama pull nomic-embed-text

# Chat-Modell (eines davon)
docker compose exec ollama ollama pull mistral        # 7B, empfohlen
# oder: ollama pull llama3                            # 8B, braucht mehr RAM
```

Falls Ollama nativ installiert ist, können die Modelle auch direkt mit `ollama pull` geladen werden.

### 4. Anwendung starten

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home  # macOS

# Mit PAT (Confluence 7.9+):
CONFLUENCE_PAT=dein-token CONFLUENCE_SPACES=DEV,OPS mvn spring-boot:run -DskipTests

# Mit Basic Auth (ältere Versionen oder lokaler Test):
CONFLUENCE_USERNAME=admin CONFLUENCE_PASSWORD=admin CONFLUENCE_SPACES=DEV,OPS mvn spring-boot:run -DskipTests
```

### 5. Initialen Crawl starten

```bash
curl -X POST http://localhost:8080/api/admin/ingest
```

### 6. Chat-UI öffnen

```
http://localhost:8080
```

## Lokales Test-Setup (Confluence On-Premise)

Für ein realistisches Testszenario kann Confluence 8.5 lokal per Docker gestartet werden:

```bash
docker compose -f docker-compose.test.yml up -d
```

Dann unter http://localhost:8090 den Setup-Wizard durchlaufen (Evaluierungs-Lizenz über my.atlassian.com).

## Konfiguration

| Variable | Beschreibung | Default |
|---|---|---|
| `CONFLUENCE_BASE_URL` | Confluence Server URL | `http://localhost:8090` |
| `CONFLUENCE_PAT` | Personal Access Token (Confluence 7.9+) | — |
| `CONFLUENCE_USERNAME` | Basic Auth Username (Alternative zu PAT) | — |
| `CONFLUENCE_PASSWORD` | Basic Auth Passwort | — |
| `CONFLUENCE_SPACES` | Komma-separierte Space-Keys | — |
| `OLLAMA_BASE_URL` | Ollama API URL | `http://localhost:11434` |
| `OLLAMA_CHAT_MODEL` | Chat-Modell | `llama3` |
| `OLLAMA_EMBEDDING_MODEL` | Embedding-Modell | `nomic-embed-text` |
| `QDRANT_HOST` | Qdrant Host | `localhost` |
| `QDRANT_GRPC_PORT` | Qdrant gRPC Port | `6334` |

## API-Endpunkte

| Methode | Pfad | Beschreibung |
|---|---|---|
| `POST` | `/api/chat` | Synchrone Frage-Antwort |
| `POST` | `/api/chat/stream` | Streaming via SSE |
| `GET` | `/api/spaces` | Verfügbare Spaces |
| `POST` | `/api/admin/ingest` | Vollständigen Crawl + Ingestion starten |
| `POST` | `/api/admin/ingest/{spaceKey}` | Einzelnen Space ingesten |
| `POST` | `/api/admin/sync` | Inkrementellen Sync starten |
| `POST` | `/api/admin/sync/{spaceKey}` | Space-Sync |
| `GET` | `/api/admin/sync/status` | Sync-Status pro Space |
| `GET` | `/actuator/health` | Health Check (Qdrant, Ollama, Confluence) |

## Architektur

```
┌──────────────┐     ┌──────────────────┐     ┌─────────┐
│  Confluence   │────▶│  Crawler Service  │────▶│  Jsoup  │
│  REST API     │     │  (PAT/Basic Auth, │     │  + Tika │
└──────────────┘     │   Pagination)     │     └────┬────┘
                      └──────────────────┘          │
                                                     ▼
┌──────────────┐     ┌──────────────────┐     ┌──────────┐
│   Ollama      │◀───│  Ingestion        │◀───│ Chunking │
│  (Embedding)  │───▶│  Service          │───▶│ Pipeline │
└──────────────┘     └──────────────────┘     └──────────┘
                              │
                              ▼
┌──────────────┐     ┌──────────────────┐     ┌──────────┐
│   Ollama      │◀───│  Query Service    │◀───│  Qdrant  │
│  (Chat LLM)  │───▶│  (RAG Pipeline)   │    │ VectorDB │
└──────────────┘     └───────┬──────────┘     └──────────┘
                              │
                              ▼
                      ┌──────────────────┐
                      │   Chat Frontend   │
                      │  (HTML/JS + SSE)  │
                      └──────────────────┘
```

## Projektstruktur

```
src/main/java/at/openaustria/confluencerag/
├── config/          # ConfluenceProperties, Health Indicators, CORS
├── crawler/         # CrawlerService, AttachmentTextExtractor
│   ├── client/      # ConfluenceClient (REST API, Pagination, Retry)
│   ├── converter/   # ConfluenceHtmlConverter, MacroHandlers (PlantUML etc.)
│   └── model/       # DTOs (ConfluencePageResponse, ConfluenceDocument etc.)
├── ingestion/       # ChunkingService, IngestionService, SyncService
├── query/           # QueryService, DTOs (QueryRequest/Response, Source)
└── web/             # ChatController, AdminController, GlobalExceptionHandler

src/main/resources/
├── application.yml
├── application-dev.yml
└── static/          # Chat-UI (index.html, CSS, JS)

docs/
├── Confluence_RAG_Konzept.md
├── MVP_Phasenplan.md
└── specs/           # 9 Implementierungsspezifikationen
```

## Status

MVP implementiert und funktionsfähig. Getestet mit Confluence 8.5 Data Center (Docker).

## Lizenz

MIT
