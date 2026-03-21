# MVP Phasenplan – Confluence RAG Pipeline

**Entscheidungen (Stand: 21. März 2026)**

| Frage | Entscheidung |
|---|---|
| RAG-Framework | Spring AI |
| VectorStore | Qdrant |
| LLM & Embedding | On-Premise via Ollama |
| Confluence-Auth | Personal Access Token (PAT) |
| MVP-Scope | Seiten, Kommentare, Labels, Attachments (inkl. PDFs) |
| Space-Auswahl | Konfigurierbar (selbst wählbar) |
| Makros | Standard-Makros + PlantUML (UML-Quellcode extrahieren) |
| Frontend | Chat-UI mit Quellenangabe |

---

## GitHub Issues Übersicht

Jedes Issue entspricht einer fokussierten Coding-Session. Die Issues bauen aufeinander auf — die Reihenfolge ist verbindlich.

| Issue | Titel | Phase | Spec | Aufwand |
|---|---|---|---|---|
| #1 | Spring Boot Projekt & Dependencies | 1 | [01_projekt-setup-infrastruktur.md](specs/01_projekt-setup-infrastruktur.md) | 0.5 Tage |
| #2 | Docker Compose & Konfiguration | 1 | [01_projekt-setup-infrastruktur.md](specs/01_projekt-setup-infrastruktur.md) | 0.5 Tage |
| #3 | Confluence REST API Client | 2 | [02_confluence-api-client.md](specs/02_confluence-api-client.md) | 1 Tag |
| #4 | XHTML → Plaintext Konverter | 2 | [03_html-konverter.md](specs/03_html-konverter.md) | 1 Tag |
| #5 | PlantUML-Makro Extraktion | 2 | [03_html-konverter.md](specs/03_html-konverter.md) | 0.5 Tage |
| #6 | Kommentare & Attachments | 2 | [04_kommentare-attachments.md](specs/04_kommentare-attachments.md) | 1 Tag |
| #7 | Crawler-Orchestrierung | 2 | [04_kommentare-attachments.md](specs/04_kommentare-attachments.md) | 0.5 Tage |
| #8 | Chunking-Pipeline | 3 | [05_document-processing.md](specs/05_document-processing.md) | 0.5 Tage |
| #9 | Embedding & Qdrant-Upsert | 3 | [05_document-processing.md](specs/05_document-processing.md) | 1 Tag |
| #10 | Inkrementelle Synchronisation | 3 | [06_inkrementeller-sync.md](specs/06_inkrementeller-sync.md) | 1 Tag |
| #11 | RAG Query Pipeline | 4 | [07_rag-query-service.md](specs/07_rag-query-service.md) | 1 Tag |
| #12 | REST API & SSE Streaming | 4 | [07_rag-query-service.md](specs/07_rag-query-service.md) | 1 Tag |
| #13 | Chat UI Grundgerüst & Streaming | 5 | [08_chat-frontend.md](specs/08_chat-frontend.md) | 1.5 Tage |
| #14 | Quellenangabe & Space-Filter | 5 | [08_chat-frontend.md](specs/08_chat-frontend.md) | 1 Tag |
| #15 | Error Handling, Logging & E2E | 6 | [09_integration-deployment.md](specs/09_integration-deployment.md) | 1 Tag |
| #16 | Docker Compose Final & README | 6 | [09_integration-deployment.md](specs/09_integration-deployment.md) | 1 Tag |

---

## Abhängigkeitsgraph

```
Issue #1 → #2 → #3 → #4 → #5
                  ↓         ↓
                  #6 → #7 → #8 → #9 → #10
                                        ↓
                                       #11 → #12 → #13 → #14 → #15 → #16
```

---

## Phase 1: Projekt-Setup & Infrastruktur

**Issues:** #1, #2
**Spec:** [01_projekt-setup-infrastruktur.md](specs/01_projekt-setup-infrastruktur.md)

### Issue #1 — Spring Boot Projekt & Dependencies

Neues Spring Boot 3.x Projekt aufsetzen. Alle Maven/Gradle Dependencies definieren. Package-Struktur anlegen. Leere `application.yml` mit Profil-Struktur.

**Akzeptanzkriterien:**
- `mvn clean compile` / `./gradlew build` läuft erfolgreich
- Anwendung startet (auch wenn noch keine Logik vorhanden)

### Issue #2 — Docker Compose & Konfiguration

Docker Compose mit Qdrant und Ollama. `application.yml` mit allen Konfigurationsparametern. Modelle automatisch beim Start pullen.

**Akzeptanzkriterien:**
- `docker compose up` startet Qdrant (6333) und Ollama
- Embedding-Modell und Chat-Modell sind geladen
- Spring Boot App verbindet sich erfolgreich mit beiden Services

---

## Phase 2: Confluence Crawler

**Issues:** #3, #4, #5, #6, #7
**Specs:** [02_confluence-api-client.md](specs/02_confluence-api-client.md), [03_html-konverter.md](specs/03_html-konverter.md), [04_kommentare-attachments.md](specs/04_kommentare-attachments.md)

### Issue #3 — Confluence REST API Client

HTTP-Client mit PAT-Auth, automatische Paginierung, Retry mit Backoff. Abruf von Spaces und Seiten.

**Akzeptanzkriterien:**
- Client ruft alle Seiten eines konfigurierten Spaces ab
- Paginierung funktioniert transparent
- Bei HTTP 429/5xx wird automatisch retried

### Issue #4 — XHTML → Plaintext Konverter

Jsoup-basierter Konverter für Confluence Storage Format. Behandlung von Tabellen, Listen, Code-Blöcken, Panels, Info/Warning/Note-Makros.

**Akzeptanzkriterien:**
- Unit-Tests für jedes Makro/Element-Typ
- Sauberer, lesbarer Plaintext ohne HTML-Artefakte

### Issue #5 — PlantUML-Makro Extraktion

Erkennung und Extraktion von PlantUML-Makros aus dem Storage Format. UML-Quellcode als Codeblock in den Plaintext einfügen.

**Akzeptanzkriterien:**
- PlantUML-Quellcode wird korrekt extrahiert
- Verschiedene PlantUML-Plugin-Formate werden erkannt
- Fallback: Makro wird übersprungen wenn kein Quellcode gefunden

### Issue #6 — Kommentare & Attachments

Kommentare pro Seite abrufen und konvertieren. Attachments auflisten, PDFs herunterladen und via Tika extrahieren.

**Akzeptanzkriterien:**
- Kommentare werden als Text der Seite zugeordnet
- PDF-Text wird extrahiert und der Seite zugeordnet
- Nicht-PDF-Attachments werden übersprungen (mit Log-Hinweis)

### Issue #7 — Crawler-Orchestrierung

Zusammenführung aller Crawler-Komponenten. Service der für konfigurierte Spaces den kompletten Crawl durchführt und `ConfluenceDocument`-Objekte liefert.

**Akzeptanzkriterien:**
- Ein Aufruf crawlt alle konfigurierten Spaces vollständig
- Output: Liste von `ConfluenceDocument` mit Body, Kommentaren, Attachment-Texten, Metadaten
- Fortschritts-Logging (Space X: Seite Y/Z)

---

## Phase 3: Document Processing & Ingestion

**Issues:** #8, #9, #10
**Specs:** [05_document-processing.md](specs/05_document-processing.md), [06_inkrementeller-sync.md](specs/06_inkrementeller-sync.md)

### Issue #8 — Chunking-Pipeline

Text-Splitting mit konfigurierbarer Chunk-Größe/Overlap. Metadaten-Vererbung. Separate Chunks für Body, Kommentare, Attachments.

**Akzeptanzkriterien:**
- Chunks sind 500–800 Token groß mit 50–100 Token Overlap
- Jeder Chunk hat vollständige Metadaten (pageId, spaceKey, title, url, chunkType, labels)

### Issue #9 — Embedding & Qdrant-Upsert

Ollama Embedding-Anbindung via Spring AI. Qdrant Collection anlegen. Batch-Upsert mit Vektoren + Metadaten.

**Akzeptanzkriterien:**
- Chunks werden embedded und in Qdrant gespeichert
- Metadaten sind als Qdrant Payload filterbar
- Batch-Verarbeitung für Performance (z.B. 50 Chunks pro Batch)

### Issue #10 — Inkrementelle Synchronisation

CQL-basierte Delta-Abfrage. Timestamp-Tracking. Löschen veralteter Chunks. Scheduler.

**Akzeptanzkriterien:**
- Nur geänderte Seiten seit letztem Sync werden verarbeitet
- Alte Chunks einer geänderten Seite werden vor Re-Ingestion gelöscht
- Scheduler läuft periodisch (konfigurierbar, Default: 30 Min)

---

## Phase 4: RAG Query Service

**Issues:** #11, #12
**Spec:** [07_rag-query-service.md](specs/07_rag-query-service.md)

### Issue #11 — RAG Query Pipeline

Query-Embedding → Qdrant Similarity Search → Kontext-Aufbau → LLM-Aufruf mit System-Prompt. Quellenextraktion aus den gefundenen Chunks.

**Akzeptanzkriterien:**
- Frage wird beantwortet mit Kontext aus Qdrant
- Antwort enthält Quellenangaben (Titel, URL, Space)
- Space-Filter funktioniert

### Issue #12 — REST API & SSE Streaming

REST-Controller mit synchronem und Streaming-Endpunkt. Request/Response DTOs. CORS-Konfiguration.

**Akzeptanzkriterien:**
- `POST /api/chat` liefert JSON mit answer + sources
- `POST /api/chat/stream` liefert SSE mit Token-by-Token Antwort + Quellen am Ende
- `GET /api/spaces` liefert verfügbare Spaces

---

## Phase 5: Chat-Frontend

**Issues:** #13, #14
**Spec:** [08_chat-frontend.md](specs/08_chat-frontend.md)

### Issue #13 — Chat UI Grundgerüst & Streaming

SPA mit Chat-Interface. Nachrichtenverlauf, Eingabefeld, Streaming-Anzeige via SSE.

**Akzeptanzkriterien:**
- User kann Frage eingeben und Antwort wird gestreamt
- Nachrichtenverlauf wird korrekt dargestellt
- Markdown in Antworten wird gerendert

### Issue #14 — Quellenangabe & Space-Filter

Quellenlinks unter jeder Antwort. Space-Filter-UI. Lade-/Fehlerzustände.

**Akzeptanzkriterien:**
- Quellen werden als klickbare Links mit Space-Badge angezeigt
- Space-Filter schränkt die Suche ein
- Loading-State und Fehlerbehandlung im UI

---

## Phase 6: Integration & Deployment

**Issues:** #15, #16
**Spec:** [09_integration-deployment.md](specs/09_integration-deployment.md)

### Issue #15 — Error Handling, Logging & E2E

Robustes Error Handling über alle Komponenten. Strukturiertes Logging. End-to-End Test.

**Akzeptanzkriterien:**
- Anwendung fängt alle erwartbaren Fehler ab (Netzwerk, Timeout, leere Responses)
- Crawl-Fortschritt, Query-Latenz und Fehler werden geloggt
- E2E: Crawl → Ingestion → Query → Antwort mit Quellen funktioniert

### Issue #16 — Docker Compose Final & README

Finales Docker Compose mit App + Qdrant + Ollama. Setup-Anleitung. Konfigurationsdokumentation.

**Akzeptanzkriterien:**
- `docker compose up` startet das komplette System
- README beschreibt Setup, Konfiguration und ersten Crawl
- Ein Test-Space ist erfolgreich indexiert und abfragbar

---

## Zeitübersicht

| Phase | Issues | Aufwand | Kumuliert |
|---|---|---|---|
| 1 – Projekt-Setup | #1, #2 | 1 Tag | 1 Tag |
| 2 – Confluence Crawler | #3, #4, #5, #6, #7 | 4 Tage | 5 Tage |
| 3 – Document Processing | #8, #9, #10 | 2.5 Tage | 7.5 Tage |
| 4 – RAG Query Service | #11, #12 | 2 Tage | 9.5 Tage |
| 5 – Chat-Frontend | #13, #14 | 2.5 Tage | 12 Tage |
| 6 – Integration | #15, #16 | 2 Tage | 14 Tage |
| **Gesamt** | **16 Issues** | **14 Tage** | |

---

## Risiken & Mitigationen

| Risiko | Mitigation |
|---|---|
| Ollama-Modellqualität reicht nicht aus | Modell austauschbar (Konfiguration), größeres Modell laden oder Fine-Tuning evaluieren |
| PlantUML-Makros haben unterschiedliche Formate je nach Plugin-Version | Crawler defensiv bauen: Quellcode extrahieren wo vorhanden, sonst Makro skippen |
| Große Spaces brauchen lange beim initialen Crawl | Fortschrittsanzeige, Pagination, parallelisierte Requests |
| Chunk-Qualität passt nicht → schlechte Antworten | Chunk-Größe ist konfigurierbar, erst mit einem Space testen und iterieren |
| Qdrant-Collection wird bei Re-Import inkonsistent | Upsert-Strategie mit pageId-basiertem Löschen vor Neuindexierung |
| Ollama benötigt GPU für akzeptable Latenz | Hardware-Anforderungen im README dokumentieren, CPU-Fallback konfigurierbar |
