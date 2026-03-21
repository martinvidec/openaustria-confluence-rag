# Confluence RAG

KI-gestütztes Q&A über Confluence On-Premise Inhalte. Extrahiert Seiten, Kommentare und Attachments via REST API, verarbeitet sie in einer RAG-Pipeline und beantwortet Fragen über ein Chat-Interface mit Quellenangaben.

## Features

- **Confluence Crawler** — Extraktion via REST API mit PAT-Auth, inkl. PlantUML-Makros, Kommentare und PDF-Attachments
- **Inkrementeller Sync** — Nur geänderte Seiten werden erneut verarbeitet (CQL-basiert)
- **RAG-Pipeline** — Chunking, Embedding und Similarity Search über Qdrant
- **Chat-Interface** — Streaming-Antworten mit Quellenangaben und Space-Filter
- **Vollständig On-Premise** — LLM und Embedding via Ollama, kein Cloud-Zwang

## Tech Stack

| Schicht | Technologie |
|---|---|
| Sprache | Java 17+ |
| Framework | Spring Boot 3.x + Spring AI 1.0+ |
| HTML-Parsing | Jsoup |
| PDF-Extraktion | Apache Tika |
| VectorStore | Qdrant |
| LLM & Embedding | Ollama (llama3, nomic-embed-text) |
| Frontend | React + Vite + TypeScript |
| Infrastruktur | Docker Compose |

## Voraussetzungen

- Java 17+
- Docker + Docker Compose
- Confluence On-Premise (5.5+) mit Personal Access Token (PAT)
- GPU empfohlen (für Ollama)

## Schnellstart

### 1. Repository klonen

```bash
git clone https://github.com/martinvidec/openaustria-confluence-rag.git
cd openaustria-confluence-rag
```

### 2. Umgebungsvariablen setzen

```bash
cp .env.example .env
# .env anpassen:
#   CONFLUENCE_BASE_URL=https://confluence.example.com
#   CONFLUENCE_PAT=dein-personal-access-token
#   CONFLUENCE_SPACES=DEV,OPS,TEAM
```

### 3. Infrastruktur starten

```bash
docker compose up -d
```

### 4. Ollama-Modelle laden

```bash
docker compose exec ollama ollama pull nomic-embed-text
docker compose exec ollama ollama pull llama3
```

### 5. Anwendung starten

```bash
./mvnw spring-boot:run
```

### 6. Initialen Crawl starten

```bash
curl -X POST http://localhost:8080/api/admin/ingest
```

### 7. Chat-UI öffnen

```
http://localhost:8080
```

## Konfiguration

| Variable | Beschreibung | Default |
|---|---|---|
| `CONFLUENCE_BASE_URL` | Confluence Server URL | `http://localhost:8090` |
| `CONFLUENCE_PAT` | Personal Access Token | — |
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
| `GET` | `/api/admin/sync/status` | Sync-Status pro Space |
| `GET` | `/actuator/health` | Health Check |

## Architektur

```
┌──────────────┐     ┌──────────────────┐     ┌─────────┐
│  Confluence   │────▶│  Crawler Service  │────▶│  Jsoup  │
│  REST API     │     │  (PAT Auth,       │     │  + Tika │
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
                      │  (React + SSE)    │
                      └──────────────────┘
```

## Projektstruktur

```
docs/
├── Confluence_RAG_Konzept.md          # Konzeptdokument (Deutsch)
├── MVP_Phasenplan.md                  # Phasenplan mit GitHub Issues
└── specs/                             # Detaillierte Spezifikationen
    ├── 01_projekt-setup-infrastruktur.md
    ├── 02_confluence-api-client.md
    ├── 03_html-konverter.md
    ├── 04_kommentare-attachments.md
    ├── 05_document-processing.md
    ├── 06_inkrementeller-sync.md
    ├── 07_rag-query-service.md
    ├── 08_chat-frontend.md
    └── 09_integration-deployment.md
```

## Status

🚧 **In Entwicklung** — Konzept- und Spezifikationsphase abgeschlossen. Implementierung folgt gemäß [Phasenplan](docs/MVP_Phasenplan.md).

## Lizenz

MIT
