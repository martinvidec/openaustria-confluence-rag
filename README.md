# Confluence RAG

KI-gestütztes Q&A über Confluence On-Premise Inhalte. Extrahiert Seiten, Kommentare und Attachments via REST API, verarbeitet sie in einer RAG-Pipeline und beantwortet Fragen über ein Chat-Interface mit Quellenangaben.

![Confluence RAG Chat](docs/screenshots/Screen_1.png)

## Features

- **Confluence Crawler** — Extraktion via REST API mit PAT- oder Basic-Auth, inkl. PlantUML-Makros, Kommentare und PDF-Attachments
- **Inkrementeller Sync** — Nur geänderte Seiten werden erneut verarbeitet (CQL-basiert)
- **RAG-Pipeline** — Chunking mit Titel-/Label-Anreicherung, Overlap, Embedding und Similarity Search über Qdrant
- **Keyword Re-Ranking** — Vektorsuche + Keyword-basierte Nachsortierung für präzise Ergebnisse in Single-Domain-Korpora
- **Chat-Interface** — Streaming-Antworten mit Quellenangaben, Space-Filter und Modell-Anzeige
- **Admin-Panel** — Ingest/Sync-Steuerung mit Live-Fortschrittsanzeige
- **Vollständig On-Premise** — LLM und Embedding via Ollama, kein Cloud-Zwang

## Tech Stack

| Schicht | Technologie |
|---|---|
| Sprache | Java 17+ |
| Framework | Spring Boot 3.4.3 + Spring AI 1.0.0 |
| HTML-Parsing | Jsoup |
| PDF-Extraktion | Apache Tika |
| VectorStore | Qdrant |
| LLM & Embedding | Ollama (z.B. gemma3:4b + nomic-embed-text) |
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

Es gibt zwei Varianten: **Mit Docker** (empfohlen, einfacher) oder **ohne Docker** (alle Dienste nativ). Die App selbst ist in beiden Fällen eine Java-Anwendung.

### macOS

<details>
<summary><strong>Mit Docker (empfohlen)</strong></summary>

```bash
# Java 17 + Maven
brew install openjdk@17 maven
echo 'export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home' >> ~/.zshrc
source ~/.zshrc

# Docker Desktop
brew install --cask docker
# Docker Desktop starten und warten bis es läuft

# Infrastruktur starten (Qdrant + Ollama)
docker compose up -d qdrant ollama

# Ollama-Modelle laden
docker compose exec ollama ollama pull nomic-embed-text
docker compose exec ollama ollama pull gemma3:4b
```

</details>

<details>
<summary><strong>Ohne Docker</strong></summary>

```bash
# Java 17 + Maven
brew install openjdk@17 maven
echo 'export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home' >> ~/.zshrc
source ~/.zshrc

# Qdrant nativ
brew install qdrant
qdrant &  # Startet auf Port 6333/6334

# Ollama nativ
brew install ollama
ollama serve &  # Startet auf Port 11434
ollama pull nomic-embed-text
ollama pull gemma3:4b
```

</details>

---

### Linux (Ubuntu/Debian)

<details>
<summary><strong>Mit Docker (empfohlen)</strong></summary>

```bash
# Java 17 + Maven
sudo apt update
sudo apt install -y openjdk-17-jdk maven
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64' >> ~/.bashrc
source ~/.bashrc

# Docker
sudo apt install -y docker.io docker-compose-v2
sudo usermod -aG docker $USER
# Neu einloggen damit die Gruppenänderung greift

# Infrastruktur starten (Qdrant + Ollama)
docker compose up -d qdrant ollama

# Ollama-Modelle laden
docker compose exec ollama ollama pull nomic-embed-text
docker compose exec ollama ollama pull gemma3:4b
```

</details>

<details>
<summary><strong>Ohne Docker</strong></summary>

```bash
# Java 17 + Maven
sudo apt update
sudo apt install -y openjdk-17-jdk maven
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64' >> ~/.bashrc
source ~/.bashrc

# Qdrant nativ
curl -L https://github.com/qdrant/qdrant/releases/latest/download/qdrant-x86_64-unknown-linux-musl.tar.gz | tar xz
./qdrant &  # Startet auf Port 6333/6334

# Ollama nativ
curl -fsSL https://ollama.com/install.sh | sh
ollama serve &  # Startet auf Port 11434
ollama pull nomic-embed-text
ollama pull gemma3:4b
```

</details>

---

### Linux (RHEL/CentOS/Fedora)

<details>
<summary><strong>Mit Docker (empfohlen)</strong></summary>

```bash
# Java 17 + Maven
sudo dnf install -y java-17-openjdk-devel maven
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk' >> ~/.bashrc
source ~/.bashrc

# Docker (RHEL/CentOS)
sudo dnf install -y dnf-plugins-core
sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker $USER
# Neu einloggen damit die Gruppenänderung greift

# Docker (Fedora alternativ)
# sudo dnf install -y docker docker-compose
# sudo systemctl enable --now docker
# sudo usermod -aG docker $USER

# Infrastruktur starten (Qdrant + Ollama)
docker compose up -d qdrant ollama

# Ollama-Modelle laden
docker compose exec ollama ollama pull nomic-embed-text
docker compose exec ollama ollama pull gemma3:4b
```

</details>

<details>
<summary><strong>Ohne Docker</strong></summary>

```bash
# Java 17 + Maven
sudo dnf install -y java-17-openjdk-devel maven
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk' >> ~/.bashrc
source ~/.bashrc

# Qdrant nativ
curl -L https://github.com/qdrant/qdrant/releases/latest/download/qdrant-x86_64-unknown-linux-musl.tar.gz | tar xz
./qdrant &  # Startet auf Port 6333/6334

# Ollama nativ
curl -fsSL https://ollama.com/install.sh | sh
ollama serve &  # Startet auf Port 11434
ollama pull nomic-embed-text
ollama pull gemma3:4b
```

</details>

---

### Windows

<details>
<summary><strong>Mit Docker (empfohlen)</strong></summary>

```powershell
# Java 17 + Maven
winget install EclipseAdoptium.Temurin.17.JDK
winget install Apache.Maven
setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17.x.x-hotspot"
# Pfad anpassen je nach Version, neues Terminal öffnen

# Docker Desktop
winget install Docker.DockerDesktop
# Docker Desktop starten und warten bis es läuft

# Infrastruktur starten (Qdrant + Ollama)
docker compose up -d qdrant ollama

# Ollama-Modelle laden
docker compose exec ollama ollama pull nomic-embed-text
docker compose exec ollama ollama pull gemma3:4b
```

</details>

<details>
<summary><strong>Ohne Docker</strong></summary>

```powershell
# Java 17 + Maven
winget install EclipseAdoptium.Temurin.17.JDK
winget install Apache.Maven
setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17.x.x-hotspot"
# Pfad anpassen je nach Version, neues Terminal öffnen

# Qdrant nativ
# Download: https://github.com/qdrant/qdrant/releases (Windows Binary)
# Entpacken und starten:
.\qdrant.exe  # Startet auf Port 6333/6334

# Ollama nativ
winget install Ollama.Ollama
ollama serve  # In separatem Terminal, startet auf Port 11434
ollama pull nomic-embed-text
ollama pull gemma3:4b
```

</details>

---

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
```

Mit Docker zusätzlich:

```bash
docker --version       # Sollte 20+ zeigen
docker compose version # Sollte 2.x zeigen
```

Ohne Docker zusätzlich:

```bash
curl http://localhost:6333/healthz     # Qdrant: "ok"
curl http://localhost:11434/api/tags   # Ollama: Modell-Liste
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
docker compose exec ollama ollama pull gemma3:4b              # Chat (Default)
```

Oder falls Ollama nativ installiert ist:

```bash
ollama pull nomic-embed-text
ollama pull gemma3:4b
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

### Confluence

| Variable | Beschreibung | Default |
|---|---|---|
| `CONFLUENCE_BASE_URL` | Confluence Server URL | `http://localhost:8090` |
| `CONFLUENCE_PAT` | Personal Access Token (Confluence 7.9+) | — |
| `CONFLUENCE_USERNAME` | Basic Auth Username (Alternative zu PAT) | — |
| `CONFLUENCE_PASSWORD` | Basic Auth Passwort | — |
| `CONFLUENCE_SPACES` | Komma-separierte Space-Keys | — |

### LLM & Embedding

| Variable | Beschreibung | Default |
|---|---|---|
| `OLLAMA_BASE_URL` | Ollama API URL | `http://localhost:11434` |
| `OLLAMA_CHAT_MODEL` | Chat-Modell | `gemma3:4b` |
| `OLLAMA_EMBEDDING_MODEL` | Embedding-Modell | `nomic-embed-text` |

### Infrastruktur

| Variable | Beschreibung | Default |
|---|---|---|
| `QDRANT_HOST` | Qdrant Host | `localhost` |
| `QDRANT_GRPC_PORT` | Qdrant gRPC Port | `6334` |

### Ingestion & Query Tuning

| Variable | Beschreibung | Default |
|---|---|---|
| `CHUNK_SIZE` | Chunk-Größe in Tokens | `500` |
| `CHUNK_OVERLAP` | Overlap zwischen Chunks in Tokens | `50` |
| `BATCH_SIZE` | Batch-Größe für Qdrant-Upserts | `20` |
| `QUERY_TOP_K` | Anzahl Ergebnisse nach Re-Ranking | `10` |
| `QUERY_SIMILARITY_THRESHOLD` | Min. Cosine-Similarity für Kandidaten | `0.45` |

## API-Endpunkte

| Methode | Pfad | Beschreibung |
|---|---|---|
| `POST` | `/api/chat` | Synchrone Frage-Antwort |
| `POST` | `/api/chat/stream` | Streaming via SSE |
| `GET` | `/api/spaces` | Verfügbare Spaces |
| `POST` | `/api/admin/ingest` | Vollständigen Crawl + Ingestion starten (löscht alte Chunks) |
| `POST` | `/api/admin/ingest/{spaceKey}` | Einzelnen Space ingesten |
| `POST` | `/api/admin/sync` | Inkrementellen Sync starten |
| `POST` | `/api/admin/sync/{spaceKey}` | Space-Sync |
| `GET` | `/api/admin/job/status` | Aktueller Job-Status mit Fortschritt |
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
┌──────────────┐     ┌──────────────────┐     ┌──────────────────┐
│   Ollama      │◀───│  Ingestion        │◀───│ Chunking Pipeline │
│  (Embedding)  │───▶│  Service          │───▶│ (Titel/Label/Pfad │
└──────────────┘     └──────────────────┘     │  Anreicherung +   │
                              │                │  Overlap)          │
                              ▼                └──────────────────┘
┌──────────────┐     ┌──────────────────┐     ┌──────────┐
│   Ollama      │◀───│  Query Service    │◀───│  Qdrant  │
│  (Chat LLM)  │───▶│  (Vektor-Suche +  │    │ VectorDB │
└──────────────┘     │   Keyword Re-Rank)│    └──────────┘
                      └───────┬──────────┘
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
├── config/          # ConfluenceProperties, QueryProperties, Health Indicators, CORS
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
└── specs/           # 13 Implementierungsspezifikationen + Analysen
```

## Status

MVP implementiert und funktionsfähig. Getestet mit Confluence 8.5 Data Center (Docker). 43 Unit-Tests.

## Lizenz

MIT
