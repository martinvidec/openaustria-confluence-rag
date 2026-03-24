#!/usr/bin/env python3
"""Generate ~100 test pages in Confluence OP space from repo docs/specs."""

import json
import re
import sys
import time
import urllib.request
import urllib.error
import base64
import os
import html

BASE_URL = "http://localhost:8090"
SPACE_KEY = "OP"
AUTH = base64.b64encode(b"admin:admin").decode()
SPECS_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "docs", "specs")

created = 0


def api(method, path, data=None):
    url = f"{BASE_URL}/rest/api{path}"
    req = urllib.request.Request(url, method=method)
    req.add_header("Authorization", f"Basic {AUTH}")
    req.add_header("Content-Type", "application/json")
    if data:
        req.data = json.dumps(data).encode()
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        print(f"  ERROR {e.code}: {body[:200]}")
        return None


def create_page(title, body_html, parent_id=None):
    global created
    data = {
        "type": "page",
        "title": title,
        "space": {"key": SPACE_KEY},
        "body": {
            "storage": {
                "value": body_html,
                "representation": "storage"
            }
        }
    }
    if parent_id:
        data["ancestors"] = [{"id": parent_id}]

    result = api("POST", "/content", data)
    if result and "id" in result:
        created += 1
        print(f"  [{created:3d}] {title}")
        return int(result["id"])
    return None


def h(text):
    """HTML-escape text."""
    return html.escape(text)


def md_to_storage(md_text):
    """Convert markdown-ish text to Confluence storage format (simple)."""
    lines = md_text.strip().split("\n")
    out = []
    in_code = False
    in_list = False
    in_table = False

    code_lines = []

    for line in lines:
        # Code blocks — use simple <pre> to avoid CDATA issues
        if line.strip().startswith("```"):
            if in_code:
                out.append(f"<pre>{h(chr(10).join(code_lines))}</pre>")
                code_lines = []
                in_code = False
            else:
                in_code = True
            continue

        if in_code:
            code_lines.append(line)
            continue

        # Headings
        m = re.match(r'^(#{1,6})\s+(.*)', line)
        if m:
            if in_list:
                out.append("</ul>")
                in_list = False
            level = len(m.group(1))
            out.append(f"<h{level}>{h(m.group(2))}</h{level}>")
            continue

        # Table rows
        if "|" in line and line.strip().startswith("|"):
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if all(re.match(r'^[-:]+$', c) for c in cells):
                continue  # separator row
            if not in_table:
                out.append("<table><tbody>")
                in_table = True
            out.append("<tr>" + "".join(f"<td>{h(c)}</td>" for c in cells) + "</tr>")
            continue
        elif in_table:
            out.append("</tbody></table>")
            in_table = False

        # List items
        if re.match(r'^[-*]\s', line.strip()):
            if not in_list:
                out.append("<ul>")
                in_list = True
            item = re.sub(r'^[-*]\s+', '', line.strip())
            # Handle checkbox items
            item = re.sub(r'^\[[ x]\]\s*', '', item)
            out.append(f"<li>{h(item)}</li>")
            continue
        elif in_list and line.strip() == "":
            out.append("</ul>")
            in_list = False

        # Bold/italic
        text = line
        text = re.sub(r'\*\*(.+?)\*\*', r'<strong>\1</strong>', text)
        text = re.sub(r'\*(.+?)\*', r'<em>\1</em>', text)
        text = re.sub(r'`(.+?)`', r'<code>\1</code>', text)

        # Empty line
        if line.strip() == "":
            continue

        # Paragraph
        if not line.startswith("<"):
            out.append(f"<p>{text}</p>")
        else:
            out.append(text)

    if in_list:
        out.append("</ul>")
    if in_table:
        out.append("</tbody></table>")
    if in_code:
        out.append(f"<pre>{h(chr(10).join(code_lines))}</pre>")

    return "\n".join(out)


def split_sections(md_text, level=2):
    """Split markdown by heading level into (title, body) pairs."""
    pattern = rf'^({"#" * level})\s+(.+)'
    sections = []
    current_title = None
    current_lines = []

    for line in md_text.split("\n"):
        m = re.match(pattern, line)
        if m:
            if current_title:
                sections.append((current_title, "\n".join(current_lines)))
            current_title = m.group(2)
            current_lines = []
        else:
            current_lines.append(line)

    if current_title:
        sections.append((current_title, "\n".join(current_lines)))

    return sections


def generate_additional_pages(parent_id, topic, base_content, start_idx, count):
    """Generate additional pages by creating variations/deep-dives of content."""
    pages_created = 0
    sections = split_sections(base_content, level=3)

    for i, (title, body) in enumerate(sections):
        if pages_created >= count:
            break
        sub_sections = split_sections(body, level=4)
        if sub_sections:
            sub_parent = create_page(
                f"{topic} — {title}",
                md_to_storage(body),
                parent_id
            )
            if sub_parent:
                pages_created += 1
                for st, sb in sub_sections:
                    if pages_created >= count:
                        break
                    create_page(
                        f"{topic} — {st}",
                        md_to_storage(sb),
                        sub_parent
                    )
                    pages_created += 1
        else:
            create_page(
                f"{topic} — {title}",
                md_to_storage(body),
                parent_id
            )
            pages_created += 1

    return pages_created


# Additional content templates for padding to 100 pages
EXTRA_TOPICS = [
    ("Architektur-Entscheidungen", [
        ("ADR-001: Spring AI statt LangChain", """
## Kontext
Fuer die RAG-Pipeline wurde ein Framework zur Integration von LLM und Vektordatenbank benoetigt.

## Entscheidung
Spring AI 1.0.0 wurde gewaehlt, da es nativ in das Spring-Oekosystem integriert ist.

## Gruende
- Nahtlose Integration mit Spring Boot Auto-Configuration
- Einheitliches Abstraktionsmodell fuer verschiedene LLM-Provider
- Native VectorStore-Abstraktion mit Qdrant-Support
- Aktive Weiterentwicklung durch VMware/Broadcom

## Konsequenzen
- Abhaengigkeit von Spring AI Release-Zyklus
- API-Aenderungen zwischen Minor-Versionen moeglich
- Dokumentation teilweise noch lueckenhaft
"""),
        ("ADR-002: Qdrant als Vektordatenbank", """
## Kontext
Fuer die Speicherung der Embedding-Vektoren wurde eine performante Vektordatenbank benoetigt.

## Optionen
| Option | Pros | Cons |
| Qdrant | Schnell, Filter-Support, gRPC | Weniger verbreitet |
| Chroma | Einfach, Python-native | Kein gRPC, langsamer |
| Pinecone | Managed, skalierbar | Cloud-only, Kosten |
| Weaviate | GraphQL, Hybrid-Search | Komplex, Overhead |

## Entscheidung
Qdrant wurde gewaehlt wegen:
- Hohe Performance bei Similarity Search
- Native Filter-Expressions (Space-Filter)
- gRPC-Support fuer schnelle Batch-Operationen
- Einfaches Docker-Setup
- Spring AI VectorStore Integration vorhanden

## Konsequenzen
- Self-hosted Betrieb noetig
- Backup-Strategie muss implementiert werden
"""),
        ("ADR-003: Ollama fuer lokale LLM-Inferenz", """
## Kontext
Das Projekt soll ohne Cloud-Abhaengigkeiten lokal betrieben werden koennen.

## Entscheidung
Ollama als lokaler LLM-Runner fuer Chat und Embedding.

## Modellauswahl
- Embedding: nomic-embed-text (768 Dimensionen, mehrsprachig)
- Chat: mistral oder llama3 (je nach verfuegbarem RAM)

## Vorteile
- Keine API-Kosten
- Datenschutz: Alle Daten bleiben lokal
- Einfache Installation und Modellverwaltung
- GPU-Beschleunigung wenn verfuegbar

## Nachteile
- Hardware-Anforderungen (min. 8GB RAM fuer 7B-Modelle)
- Langsamere Inferenz als Cloud-APIs
- Modellqualitaet unter GPT-4/Claude
"""),
        ("ADR-004: Jsoup XML Parser fuer Confluence Storage Format", """
## Kontext
Confluence speichert Seiteninhalte im XHTML Storage Format mit proprietaeren Namespaces (ac:, ri:).

## Problem
Standard HTML-Parser (auch Jsoup im HTML-Modus) verwerfen unbekannte Namespaces.

## Entscheidung
Jsoup im XML-Parser-Modus verwenden: `Parser.xmlParser()`

## Gruende
- Erhalt der ac:structured-macro Elemente
- Korrekte Verarbeitung von ri:attachment, ri:page etc.
- Keine zusaetzliche Abhaengigkeit noetig
- Robuste Fehlerbehandlung bei malformed XML

## Konsequenzen
- XML-Parser ist strenger als HTML-Parser
- Einige HTML-Entities muessen escaped werden
- MacroHandler-Pattern fuer verschiedene Makro-Typen
"""),
    ]),
    ("Betriebshandbuch", [
        ("Installation und Ersteinrichtung", """
## Voraussetzungen
- Java 17 oder hoeher
- Docker und Docker Compose
- Min. 8 GB RAM (16 GB empfohlen fuer LLM)
- Netzwerkzugang zum Confluence-Server

## Installationsschritte

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
ollama pull nomic-embed-text
ollama pull mistral
```

### 4. Applikation starten
```bash
export CONFLUENCE_BASE_URL=https://confluence.example.com
export CONFLUENCE_PAT=your-token-here
export CONFLUENCE_SPACES=DEV,OPS
mvn spring-boot:run
```

## Erster Ingest
Nach dem Start die Web-UI oeffnen unter http://localhost:8080, Admin-Panel oeffnen und "Alle Spaces neu ingesten" klicken.
"""),
        ("Monitoring und Logging", """
## Log-Konfiguration
Die Applikation verwendet SLF4J mit Logback.

### Log-Level pro Komponente
| Komponente | Default Level | Beschreibung |
| CrawlerService | INFO | Seitenfortschritt, Fehler |
| IngestionService | INFO | Chunk/Batch-Fortschritt |
| SyncService | INFO | Sync-Ergebnisse |
| QueryService | INFO | Anfragen und Antwortzeiten |
| ConfluenceClient | WARN | Nur Retries und Fehler |

### Debug-Logging aktivieren
```yaml
logging:
  level:
    at.openaustria.confluencerag: DEBUG
```

## Health Checks
- Spring Actuator: `GET /actuator/health`
- Qdrant Dashboard: http://localhost:6333/dashboard
- Ollama API: `curl http://localhost:11434/api/tags`

## Metriken
- Ingest-Dauer pro Space
- Chunk-Anzahl pro Space
- Fehlerrate bei Embedding-Requests
- Antwortzeit der Chat-API
"""),
        ("Backup und Recovery", """
## Qdrant Backup
Qdrant-Daten liegen im Docker Volume `qdrant_data`.

### Snapshot erstellen
```bash
curl -X POST http://localhost:6333/collections/confluence-chunks/snapshots
```

### Snapshot wiederherstellen
```bash
curl -X PUT http://localhost:6333/collections/confluence-chunks/snapshots/recover \\
  -H "Content-Type: application/json" \\
  -d '{"location": "file:///qdrant/snapshots/snapshot-name.snapshot"}'
```

## Sync-State Backup
Die Datei `./data/sync-state.json` enthaelt den letzten Sync-Zeitpunkt und bekannte Seiten-IDs pro Space. Bei Verlust wird beim naechsten Sync ein Voll-Crawl durchgefuehrt.

## Disaster Recovery
1. Docker Volumes wiederherstellen oder neu erstellen
2. Qdrant Collection wird automatisch angelegt (initialize-schema: true)
3. Voll-Ingest aller Spaces durchfuehren
4. Dauer: abhaengig von Confluence-Groesse (ca. 1 Seite/Sekunde)
"""),
        ("Fehlerbehebung und Troubleshooting", """
## Haeufige Probleme

### Embedding-Timeout
**Symptom:** Batch-Timeout nach 120 Sekunden beim Storing
**Ursache:** Ollama-Server ueberlastet oder Modell nicht geladen
**Loesung:**
```bash
# Pruefen ob Modell geladen ist
ollama list
# Modell neu laden
ollama pull nomic-embed-text
```

### Confluence API 401 Unauthorized
**Symptom:** CrawlerService meldet HTTP 401
**Ursache:** PAT abgelaufen oder falsche Credentials
**Loesung:** Neuen PAT in Confluence generieren und Environment-Variable aktualisieren

### Qdrant Connection Refused
**Symptom:** Applikation startet nicht, Connection refused auf Port 6334
**Ursache:** Qdrant-Container nicht gestartet
**Loesung:**
```bash
docker compose up -d qdrant
# Warten bis Container healthy ist
docker compose ps
```

### Out of Memory bei grossen Spaces
**Symptom:** Java OutOfMemoryError bei Ingest
**Ursache:** Zu viele Seiten/Chunks gleichzeitig im Speicher
**Loesung:** JVM Heap erhoehen:
```bash
JAVA_OPTS="-Xmx4g" mvn spring-boot:run
```
"""),
        ("Performance-Tuning", """
## Chunk-Konfiguration
Die Chunk-Groesse beeinflusst Qualitaet und Performance:

| Parameter | Default | Beschreibung |
| chunkSize | 800 | Token pro Chunk |
| chunkOverlap | 100 | Ueberlappung zwischen Chunks |
| batchSize | 50 | Chunks pro Qdrant-Batch |

### Groessere Chunks
- Mehr Kontext pro Chunk = bessere Antworten
- Aber: Weniger praezise Suche, mehr Token-Verbrauch

### Kleinere Chunks
- Praezisere Similarity Search
- Aber: Kontext kann verloren gehen

## Empfehlung
- Fuer technische Dokumentation: 800 Token (Default)
- Fuer kurze FAQ-Seiten: 400 Token
- Fuer lange Berichte: 1200 Token

## Qdrant-Optimierung
- Collection mit HNSW Index (Default)
- ef_construct: 128 (Default, guter Kompromiss)
- Fuer groessere Datenmengen (>100k Chunks): ef_construct auf 256 erhoehen
"""),
    ]),
    ("API-Dokumentation", [
        ("Chat API Endpunkte", """
## POST /api/chat
Synchrone Chat-Anfrage.

### Request
```json
{
  "question": "Wie funktioniert der Sync?",
  "spaceFilter": ["DS", "OP"]
}
```

### Response
```json
{
  "answer": "Der Sync-Prozess...",
  "sources": [
    {
      "title": "Sync-Dokumentation",
      "url": "https://confluence.example.com/...",
      "spaceKey": "DS"
    }
  ]
}
```

## POST /api/chat/stream
Server-Sent Events fuer Streaming-Antworten.

### SSE Events
| Event | Data | Beschreibung |
| token | {"token": "..."} | Einzelnes Token der Antwort |
| sources | {"sources": [...]} | Quellenangaben |
| done | {} | Stream beendet |
| error | {"message": "..."} | Fehler aufgetreten |

### Beispiel mit curl
```bash
curl -N -X POST http://localhost:8080/api/chat/stream \\
  -H "Content-Type: application/json" \\
  -d '{"question": "Was ist Spring AI?"}'
```
"""),
        ("Admin API Endpunkte", """
## POST /api/admin/ingest
Startet Voll-Ingest aller konfigurierten Spaces.

### Response (202 Accepted)
```json
{
  "status": "running",
  "operation": "ingest",
  "result": null,
  "error": null,
  "progress": null
}
```

## POST /api/admin/ingest/{spaceKey}
Startet Voll-Ingest eines einzelnen Space.

## POST /api/admin/sync
Startet inkrementellen Sync aller Spaces.

## POST /api/admin/sync/{spaceKey}
Startet inkrementellen Sync eines einzelnen Space.

## GET /api/admin/job/status
Gibt den aktuellen Job-Status zurueck. Waehrend ein Job laeuft, enthaelt das progress-Feld Live-Fortschrittsdaten.

### Response (waehrend Job)
```json
{
  "status": "running",
  "operation": "ingest",
  "progress": {
    "phase": "CHUNKING",
    "detail": "Seite 5/42 - 'API Docs'",
    "currentItem": 5,
    "totalItems": 42,
    "chunksProcessed": 28,
    "errors": 0,
    "currentSpace": "DS",
    "percentComplete": 11
  }
}
```

## GET /api/admin/sync/status
Gibt den Sync-Status aller Spaces zurueck.

### Response
```json
{
  "DS": {
    "lastSync": "2026-03-24T15:30:00Z",
    "knownPageIds": ["12345", "67890"]
  }
}
```

## GET /api/spaces
Gibt alle konfigurierten Spaces zurueck.
"""),
        ("Datenmodell und DTOs", """
## ConfluenceDocument
Das zentrale Datenmodell fuer gecrawlte Seiten.

### Felder
| Feld | Typ | Beschreibung |
| pageId | long | Confluence Page ID |
| spaceKey | String | Space-Schluessel |
| spaceName | String | Space-Name |
| title | String | Seitentitel |
| url | String | Web-URL der Seite |
| bodyText | String | Konvertierter Plaintext |
| labels | List of String | Seiten-Labels |
| comments | List of CommentDocument | Kommentare |
| attachments | List of AttachmentDocument | Attachments |
| author | String | Letzter Autor |
| lastModified | Instant | Letzte Aenderung |
| ancestors | List of String | Breadcrumb-Titel |

## CommentDocument
| Feld | Typ | Beschreibung |
| id | long | Comment ID |
| bodyText | String | Kommentar-Text |
| author | String | Autor |
| created | Instant | Erstellungszeitpunkt |

## AttachmentDocument
| Feld | Typ | Beschreibung |
| id | long | Attachment ID |
| title | String | Dateiname |
| mediaType | String | MIME-Typ |
| extractedText | String | Extrahierter Text |

## IngestionResult
| Feld | Typ | Beschreibung |
| spacesProcessed | int | Anzahl Spaces |
| pagesProcessed | int | Anzahl Seiten |
| chunksCreated | int | Erzeugte Chunks |
| chunksStored | int | Gespeicherte Chunks |
| errors | int | Fehler |
| duration | Duration | Dauer |

## SyncResult
| Feld | Typ | Beschreibung |
| pagesUpdated | int | Aktualisierte Seiten |
| pagesDeleted | int | Geloeschte Seiten |
| pagesNew | int | Neue Seiten |
| chunksCreated | int | Neue Chunks |
| errors | int | Fehler |
| duration | Duration | Dauer |
"""),
    ]),
    ("Confluence-Integration", [
        ("Authentifizierung und Zugriffskontrolle", """
## Authentifizierungsmethoden

### Personal Access Token (PAT) — Empfohlen
PATs sind die bevorzugte Methode fuer API-Zugriffe:

1. In Confluence: Profil > Einstellungen > Persoenliche Zugriffstoken
2. Token erstellen mit Leseberechtigung
3. Token als Umgebungsvariable setzen:
```bash
export CONFLUENCE_PAT=your-token-here
```

### Basic Authentication
Alternativ fuer lokale Testinstanzen:
```bash
export CONFLUENCE_USERNAME=admin
export CONFLUENCE_PASSWORD=admin
```

## Berechtigungen
Der API-Benutzer benoetigt:
- Lesezugriff auf alle zu crawlenden Spaces
- Lesezugriff auf Seiteninhalte (body.storage)
- Lesezugriff auf Kommentare und Attachments
- Lesezugriff auf Metadaten (Labels, Versionen)

## Rate Limiting
Die Confluence REST API hat standardmaessig keine Rate Limits. Bei gehosteten Instanzen (Confluence Cloud) gelten folgende Limits:
- 100 Requests pro Minute pro Benutzer
- Retry mit exponentiellem Backoff implementiert (1s, 2s, 4s)
- Maximal 3 Retries pro Request
"""),
        ("CQL-Abfragen fuer Delta-Sync", """
## Confluence Query Language (CQL)

### Grundlagen
CQL ist die Abfragesprache fuer Confluence-Inhalte. Sie wird fuer den inkrementellen Sync verwendet.

### Delta-Query
```
type = page AND space = "DS" AND lastModified >= "2026-03-20"
```

### Operatoren
| Operator | Beschreibung | Beispiel |
| = | Gleich | type = page |
| != | Ungleich | type != blogpost |
| >= | Groesser/gleich | lastModified >= "2026-01-01" |
| IN | In Liste | space IN ("DS", "OP") |
| AND | Logisches UND | type = page AND space = "DS" |
| OR | Logisches ODER | title = "A" OR title = "B" |
| ORDER BY | Sortierung | ORDER BY lastModified DESC |

### Verwendung im SyncService
1. Letzten Sync-Zeitpunkt aus State-File lesen
2. CQL-Query mit lastModified-Filter ausfuehren
3. Nur geaenderte Seiten zurueckbekommen
4. Re-Chunking und Re-Embedding dieser Seiten
5. Geloeschte Seiten durch ID-Vergleich erkennen
"""),
        ("Confluence Storage Format", """
## XHTML Storage Format

Confluence speichert Seiteninhalte im sogenannten "Storage Format" — einem erweiterten XHTML mit proprietaeren Namespaces.

### Standard-Elemente
| HTML | Confluence Storage |
| Absatz | `<p>Text</p>` |
| Ueberschrift | `<h1>` bis `<h6>` |
| Fett | `<strong>` oder `<b>` |
| Kursiv | `<em>` oder `<i>` |
| Liste | `<ul>/<ol>` mit `<li>` |
| Tabelle | `<table>` mit `<tr>`, `<th>`, `<td>` |
| Link | `<a href="...">` |
| Bild | `<ac:image>` mit `<ri:attachment>` |

### Confluence-spezifische Elemente
| Element | Namespace | Beschreibung |
| structured-macro | ac: | Makros (Code, Info, Warning) |
| rich-text-body | ac: | Makro-Body mit Formatierung |
| plain-text-body | ac: | Makro-Body ohne Formatierung |
| attachment | ri: | Referenz auf Attachment |
| page | ri: | Referenz auf andere Seite |
| user | ri: | Referenz auf Benutzer |

### Makro-Beispiele
Code-Makro:
```xml
<ac:structured-macro ac:name="code">
  <ac:parameter ac:name="language">java</ac:parameter>
  <ac:plain-text-body><![CDATA[
    public class Hello { }
  ]]></ac:plain-text-body>
</ac:structured-macro>
```

Info-Makro:
```xml
<ac:structured-macro ac:name="info">
  <ac:rich-text-body>
    <p>Dies ist ein Hinweis.</p>
  </ac:rich-text-body>
</ac:structured-macro>
```
"""),
        ("Attachment-Verarbeitung", """
## Unterstuetzte Dateiformate

| Format | MIME-Type | Extraktion |
| PDF | application/pdf | Apache Tika |
| Word (DOC) | application/msword | Apache Tika |
| Word (DOCX) | application/vnd.openxmlformats-officedocument.wordprocessingml.document | Apache Tika |
| Excel (XLSX) | application/vnd.openxmlformats-officedocument.spreadsheetml.sheet | Apache Tika |
| PowerPoint | application/vnd.openxmlformats-officedocument.presentationml.presentation | Apache Tika |
| Text | text/plain | Direktes Lesen |

## Verarbeitungsprozess
1. Attachments der Seite ueber REST API abrufen
2. MIME-Type pruefen (nur unterstuetzte Formate)
3. Dateigroesse pruefen (max. 50 MB)
4. Datei herunterladen
5. Text mit Apache Tika extrahieren
6. Extrahierten Text als AttachmentDocument speichern
7. Beim Chunking als eigener Chunk-Typ "ATTACHMENT" verarbeiten

## Fehlerbehandlung
- Einzelne Attachment-Fehler brechen nicht den gesamten Crawl ab
- Nicht-unterstuetzte Formate werden uebersprungen (DEBUG-Log)
- Grosse Dateien werden uebersprungen (WARN-Log)
- Tika-Extraktionsfehler werden geloggt, Seite wird trotzdem verarbeitet

## Performance-Ueberlegungen
- Grosse PDF-Dateien koennen die Crawl-Dauer signifikant verlaengern
- Parallelisierung der Attachment-Downloads moeglich (aktuell sequenziell)
- Tika-Extraktion ist CPU-intensiv bei grossen Dokumenten
"""),
    ]),
    ("Entwickler-Dokumentation", [
        ("Lokale Entwicklungsumgebung", """
## Voraussetzungen
- JDK 17 (empfohlen: Homebrew openjdk@17 auf macOS)
- Maven 3.9+
- Docker Desktop
- IDE: IntelliJ IDEA (empfohlen) oder VS Code mit Java Extension

## Setup
```bash
# Java konfigurieren
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

# Projekt kompilieren
mvn clean compile

# Tests ausfuehren
mvn test

# Infrastruktur starten
docker compose up -d qdrant ollama

# Applikation starten
CONFLUENCE_USERNAME=admin CONFLUENCE_PASSWORD=admin \\
CONFLUENCE_SPACES=ds,OP OLLAMA_CHAT_MODEL=mistral \\
mvn spring-boot:run -DskipTests
```

## Test-Confluence
Fuer lokale Tests steht eine Confluence-Instanz zur Verfuegung:
```bash
docker compose -f docker-compose.test.yml up -d
# Confluence UI: http://localhost:8090
# Login: admin / admin
```

## IDE-Konfiguration
### IntelliJ IDEA
- Import als Maven-Projekt
- SDK auf Java 17 setzen
- Spring Boot Run Configuration mit Environment Variables
"""),
        ("Coding Conventions", """
## Allgemein
- Java 17 Features nutzen (Records, Pattern Matching, Text Blocks)
- Sprache im Code: Englisch (Variablen, Methoden, Klassen)
- Sprache in Dokumentation: Deutsch
- Spring AI 1.0.0 API verwenden (nicht die alten Starter-Namen)

## Package-Struktur
```
at.openaustria.confluencerag
├── config/      # @Configuration, @ConfigurationProperties
├── crawler/     # Confluence API Client, HTML Converter
│   ├── client/
│   ├── converter/
│   └── model/
├── ingestion/   # Chunking, Embedding, Sync
├── query/       # RAG Query Service
└── web/         # REST Controller, DTOs
```

## Naming Conventions
| Element | Convention | Beispiel |
| Klasse | PascalCase | ConfluenceClient |
| Methode | camelCase | crawlSpace |
| Konstante | SCREAMING_SNAKE | MAX_ATTACHMENT_SIZE |
| Record | PascalCase | IngestionResult |
| Property | kebab-case | confluence.base-url |

## Spring AI Spezifika
- `Document.getText()` statt `getContent()` (seit 1.0.0)
- `SearchRequest.builder().query()` statt `SearchRequest.query()`
- `spring.ai.ollama.chat.model` statt `spring.ai.ollama.chat.options.model`

## Error Handling
- Runtime Exceptions statt Checked Exceptions
- Fehler loggen und weiterverarbeiten (keine Silent Failures)
- @JsonIgnoreProperties(ignoreUnknown = true) auf alle DTOs
"""),
        ("Testkonzept", """
## Teststrategie

### Unit Tests
- JUnit 5 + Mockito
- Fokus auf Converter, Chunking, Hilfsklassen
- Keine Spring-Context-Tests wo moeglich

### Integration Tests
- ConfluenceClientTest mit eingebettetem HttpServer
- Mock-Server fuer Confluence REST API
- Tests fuer Pagination, Retry, Authentication

### Testabdeckung
| Komponente | Tests | Abdeckung |
| ConfluenceHtmlConverter | 24 | Alle HTML-Elemente, Makros |
| ConfluenceClient | 5 | Pagination, Retry, Auth |
| ChunkingService | 6 | Chunking, Metadata, Limits |
| ConfluenceRagApplication | 1 | Context-Load |

### Testausfuehrung
```bash
# Alle Tests
mvn test

# Einzelne Testklasse
mvn test -Dtest=ConfluenceHtmlConverterTest

# Mit Coverage
mvn test jacoco:report
```

## Test-Fixtures
- HTML-Snippets fuer Converter-Tests
- Mock-JSON-Responses fuer Client-Tests
- ConfluenceDocument-Instanzen fuer Chunking-Tests
"""),
        ("Build und Deployment Pipeline", """
## Build-Prozess

### Lokaler Build
```bash
mvn clean package -DskipTests
```

### Docker Image erstellen
```bash
docker build -t confluence-rag:latest .
```

### Dockerfile
```dockerfile
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

## Deployment-Optionen

### Docker Compose (Empfohlen)
Alle Services in einem docker-compose.yml:
- confluence-rag (Applikation)
- qdrant (Vektordatenbank)
- ollama (LLM-Server)

### Standalone
```bash
java -jar target/confluence-rag-0.0.1-SNAPSHOT.jar \\
  --confluence.base-url=https://confluence.example.com \\
  --confluence.pat=your-token
```

## Umgebungsvariablen
| Variable | Beschreibung | Default |
| CONFLUENCE_BASE_URL | Confluence Server URL | http://localhost:8090 |
| CONFLUENCE_PAT | Personal Access Token | - |
| CONFLUENCE_SPACES | Komma-separierte Space Keys | - |
| OLLAMA_BASE_URL | Ollama Server URL | http://localhost:11434 |
| OLLAMA_CHAT_MODEL | Chat-Modell | llama3 |
| QDRANT_HOST | Qdrant Hostname | localhost |
"""),
    ]),
]


def main():
    global created
    print(f"Generating test pages in Confluence space {SPACE_KEY}...")
    print(f"Reading specs from {SPECS_DIR}")
    print()

    # Get homepage ID as root parent
    resp = api("GET", f"/space/{SPACE_KEY}?expand=homepage")
    if not resp:
        print("ERROR: Cannot access space OP")
        sys.exit(1)
    root_id = resp["homepage"]["id"]

    # Phase 1: Create pages from spec files
    spec_files = sorted(f for f in os.listdir(SPECS_DIR) if f.endswith(".md"))
    print(f"Found {len(spec_files)} spec files")
    print()

    for spec_file in spec_files:
        path = os.path.join(SPECS_DIR, spec_file)
        with open(path, "r") as f:
            content = f.read()

        # Extract title from first heading
        title_match = re.search(r'^#\s+(.+)', content, re.MULTILINE)
        title = title_match.group(1) if title_match else spec_file.replace(".md", "")

        # Create parent page for this spec
        parent_id = create_page(title, md_to_storage(content), root_id)
        if not parent_id:
            continue
        time.sleep(0.3)

        # Create child pages from ## sections
        sections = split_sections(content, level=2)
        for sec_title, sec_body in sections:
            if not sec_body.strip():
                continue
            child_id = create_page(
                f"{title} — {sec_title}",
                md_to_storage(sec_body),
                parent_id
            )
            if child_id:
                time.sleep(0.2)

                # Create sub-pages from ### sections
                subsections = split_sections(sec_body, level=3)
                for sub_title, sub_body in subsections:
                    if not sub_body.strip() or len(sub_body.strip()) < 50:
                        continue
                    create_page(
                        f"{sec_title} — {sub_title}",
                        md_to_storage(sub_body),
                        child_id
                    )
                    time.sleep(0.2)

    print(f"\nPhase 1 done: {created} pages from specs")

    # Phase 2: Create additional topic pages to reach ~100
    print("\nPhase 2: Creating additional topic pages...")
    for topic_name, pages in EXTRA_TOPICS:
        topic_id = create_page(topic_name, f"<p>Uebersicht: {h(topic_name)}</p>", root_id)
        if not topic_id:
            continue
        time.sleep(0.3)

        for page_title, page_content in pages:
            create_page(
                f"{topic_name} — {page_title}",
                md_to_storage(page_content),
                topic_id
            )
            time.sleep(0.2)

    print(f"\nTotal pages created: {created}")


if __name__ == "__main__":
    main()
