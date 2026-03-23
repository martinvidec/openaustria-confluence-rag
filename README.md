# Confluence RAG

KI-gestütztes Q&A über Confluence On-Premise Inhalte. Extrahiert Seiten, Kommentare und Attachments via REST API, verarbeitet sie in einer RAG-Pipeline und beantwortet Fragen über ein Chat-Interface mit Quellenangaben.

![Confluence RAG Chat](docs/screenshots/Screen_1.png)

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
- Maven 3.8+
- Docker + Docker Compose
- Confluence On-Premise (5.5+) mit PAT oder Basic Auth
- GPU empfohlen (für Ollama), CPU funktioniert auch
- Mind. 8 GB RAM (16 GB empfohlen für größere LLM-Modelle)

## Installation

### macOS

```bash
# Java 17
brew install openjdk@17
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

# Maven
brew install maven

# Docker Desktop
# Download: https://www.docker.com/products/docker-desktop/
# Oder: brew install --cask docker

# Ollama (optional, alternativ via Docker)
brew install ollama
```

Nach der Installation `JAVA_HOME` dauerhaft setzen:

```bash
echo 'export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home' >> ~/.zshrc
source ~/.zshrc
```

### Linux (Ubuntu/Debian)

```bash
# Java 17
sudo apt update
sudo apt install openjdk-17-jdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Maven
sudo apt install maven

# Docker
sudo apt install docker.io docker-compose-v2
sudo usermod -aG docker $USER
# Danach neu einloggen damit die Gruppenänderung greift

# Ollama (optional, alternativ via Docker)
curl -fsSL https://ollama.com/install.sh | sh
```

Nach der Installation `JAVA_HOME` dauerhaft setzen:

```bash
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64' >> ~/.bashrc
source ~/.bashrc
```

### Linux (RHEL/CentOS/Fedora)

```bash
# Java 17
sudo dnf install java-17-openjdk-devel
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk

# Maven
sudo dnf install maven

# Docker (RHEL/CentOS — Docker CE Repository)
sudo dnf install -y dnf-plugins-core
sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
sudo dnf install docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker $USER
# Danach neu einloggen damit die Gruppenänderung greift

# Docker (Fedora)
sudo dnf install docker docker-compose
sudo systemctl enable --now docker
sudo usermod -aG docker $USER

# Ollama (optional, alternativ via Docker)
curl -fsSL https://ollama.com/install.sh | sh
```

Nach der Installation `JAVA_HOME` dauerhaft setzen:

```bash
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk' >> ~/.bashrc
source ~/.bashrc
```

### Windows

```powershell
# Java 17 — Download und Installation:
# https://adoptium.net/de/temurin/releases/?version=17
# Oder via winget:
winget install EclipseAdoptium.Temurin.17.JDK

# Maven — Download und PATH setzen:
# https://maven.apache.org/download.cgi
# Oder via winget:
winget install Apache.Maven

# Docker Desktop:
# https://www.docker.com/products/docker-desktop/
winget install Docker.DockerDesktop

# Ollama (optional, alternativ via Docker):
# https://ollama.com/download/windows
winget install Ollama.Ollama
```

`JAVA_HOME` setzen (Systemumgebungsvariablen):

```powershell
setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17.x.x-hotspot"
# Pfad anpassen je nach installierter Version
```

**Hinweis Windows:** Umgebungsvariablen werden unter Windows anders übergeben. Statt Inline-Variablen eine `.env`-Datei nutzen oder die Variablen vorher setzen:

```powershell
$env:CONFLUENCE_USERNAME="admin"
$env:CONFLUENCE_PASSWORD="admin"
$env:CONFLUENCE_SPACES="DEV,OPS"
mvn spring-boot:run -DskipTests
```

### Installation verifizieren

```bash
java -version          # Sollte 17.x.x zeigen
mvn -version           # Sollte 3.8+ zeigen
docker --version       # Sollte 20+ zeigen
docker compose version # Sollte 2.x zeigen
```

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

Via Docker:

```bash
docker compose exec ollama ollama pull nomic-embed-text    # Embedding (erforderlich)
docker compose exec ollama ollama pull mistral              # Chat, 7B, empfohlen
```

Oder falls Ollama nativ installiert ist:

```bash
ollama pull nomic-embed-text
ollama pull mistral
```

### 4. Anwendung starten

**macOS / Linux:**

```bash
# Mit PAT (Confluence 7.9+):
CONFLUENCE_PAT=dein-token CONFLUENCE_SPACES=DEV,OPS mvn spring-boot:run -DskipTests

# Mit Basic Auth (ältere Versionen oder lokaler Test):
CONFLUENCE_USERNAME=admin CONFLUENCE_PASSWORD=admin CONFLUENCE_SPACES=DEV,OPS mvn spring-boot:run -DskipTests
```

**Windows (PowerShell):**

```powershell
$env:CONFLUENCE_USERNAME="admin"
$env:CONFLUENCE_PASSWORD="admin"
$env:CONFLUENCE_SPACES="DEV,OPS"
mvn spring-boot:run -DskipTests
```

### 5. Initialen Crawl starten

```bash
curl -X POST http://localhost:8080/api/admin/ingest
```

Unter Windows ohne curl: http://localhost:8080/api/admin/ingest im Browser als POST senden (z.B. mit Postman) oder PowerShell:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/admin/ingest
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
