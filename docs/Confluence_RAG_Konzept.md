# Confluence On-Premise – Content-Extraktion für RAG-Pipeline

**Konzeptdokument: Analyse, Technologiebewertung & Entscheidungsbasis**

| | |
|---|---|
| **Version** | 1.0 – Konzeptphase |
| **Datum** | 21. März 2026 |
| **Status** | Entwurf / Entscheidungsbasis |

---

## 1. Ausgangslage & Zielsetzung

Confluence On-Premise dient als zentrales Wissensmanagement-System. Um dieses Wissen für eine KI-basierte Retrieval-Augmented Generation (RAG) Pipeline nutzbar zu machen, müssen Inhalte extrahiert, aufbereitet und in einen Vektorstore überführt werden.

> **Kernziel:** Versionsunabhängige, robuste Extraktion aller Confluence-Inhalte (Seiten, Kommentare, Attachments, Labels) mit minimalem Betriebsaufwand – bevorzugt im Java-Ökosystem.

### 1.1 Anforderungen

- **Versionsunabhängigkeit:** Muss mit Confluence 5.5+ bis Data Center 8.x funktionieren, ohne an eine spezifische API-Version gebunden zu sein.
- **Effizienz:** Inkrementelle Extraktion (nur geänderte Seiten), um Laufzeit und Last auf dem Confluence-Server zu minimieren.
- **Vollständigkeit:** Seiteninhalte, Kommentare, Labels, Metadaten und optional Attachments (insbesondere PDFs).
- **Java-Ökosystem:** Lösung soll sich nahtlos in ein bestehendes Java/Spring-Boot-Umfeld integrieren lassen.

---

## 2. Extraktionswege aus Confluence On-Premise

Es gibt drei grundsätzliche Wege, an die Inhalte von Confluence On-Premise heranzukommen. Jeder hat unterschiedliche Komplexität und Einschränkungen.

### 2.1 Confluence REST API (empfohlen)

Die REST API unter `/rest/api/content` ist seit Confluence 5.5 verfügbar und hat sich als extrem stabil erwiesen. Die Kernendpunkte haben sich über alle Versionen kaum geändert.

| Endpunkt | Zweck |
|---|---|
| `/rest/api/content` | Seiten & Blogposts auflisten, mit `expand=body.storage,metadata.labels,version,space` |
| `/rest/api/content/{id}/child/comment` | Kommentare einer Seite abrufen |
| `/rest/api/content/{id}/child/attachment` | Attachments auflisten und herunterladen |
| `/rest/api/space` | Alle Spaces auflisten |
| `/rest/api/content/search` (CQL) | `lastModified > "2024-01-01"` für inkrementelle Syncs |

**Vorteile:** Stabil über Versionen, gut dokumentiert, paginiert, unterstützt CQL-Suche für inkrementelle Extraktion, keine Server-seitigen Plugins nötig.

**Nachteile:** Rate-Limiting möglich, Body kommt im XHTML Storage Format (muss nach Plaintext/Markdown konvertiert werden), Attachments als separate Downloads.

**Komplexität:** Niedrig bis mittel. Ein HTTP-Client + HTML-Parser reicht aus.

### 2.2 Datenbank-Direktzugriff

Confluence speichert Inhalte in einer relationalen Datenbank (PostgreSQL, MySQL, Oracle). Theoretisch könnte man direkt auf die Tabellen `CONTENT`, `BODYCONTENT`, `SPACES` etc. zugreifen.

**Vorteile:** Schnellster Zugriff, keine API-Limits, volle Kontrolle.

**Nachteile:** Datenbankschema ist undokumentiert und ändert sich zwischen Versionen. Bricht das Ziel der Versionsunabhängigkeit. Sicherheitsrisiko und Support-Verlust bei Atlassian.

**Bewertung:** ❌ Nicht empfohlen. Zu fragil, zu riskant.

### 2.3 XML/HTML Space Export

Confluence kann Spaces als HTML oder XML exportieren (über die Admin-Oberfläche oder API). Der XML-Export enthält alle Seiten im Storage Format.

**Vorteile:** Kompletter Space-Dump auf einmal, inklusive Attachments.

**Nachteile:** Kein inkrementeller Export möglich (immer alles), belastet den Server stark bei großen Spaces, keine Kommentare im HTML-Export, muss manuell oder per Workaround getriggert werden.

**Bewertung:** ⚠️ Nur als einmaliger Seed sinnvoll, nicht für laufende Synchronisation.

> **Empfehlung:** Die REST API ist der einzige Weg, der alle Anforderungen erfüllt: versionsunabhängig, inkrementell, vollständig, und ohne Risiko für den Confluence-Betrieb.

---

## 3. Sprachvergleich: Java vs. Python vs. Andere

Da du im Java-Umfeld arbeitest, ist Java die naheliegendste Wahl – aber nicht automatisch die beste für jede Teilaufgabe. Hier eine ehrliche Abwägung:

| Kriterium | Java | Python | Kotlin/JVM |
|---|---|---|---|
| **HTTP-Client** | OkHttp, HttpClient (JDK 11+), WebClient | requests, httpx | Ktor-Client, Fuel |
| **HTML → Text** | Jsoup (exzellent) | BeautifulSoup, lxml | Jsoup (via JVM) |
| **RAG-Framework** | LangChain4j, Spring AI | LangChain, LlamaIndex | LangChain4j, Spring AI |
| **VectorStore-SDKs** | Milvus, Qdrant, PgVector (alle Java-SDKs) | Alle (erstklassig) | Alle via Java-SDKs |
| **PDF-Extraktion** | Apache Tika, PDFBox | PyMuPDF, pdfplumber | Tika, PDFBox (via JVM) |
| **Deployment** | Spring Boot JAR, Docker | Script, Docker | Spring Boot JAR, Docker |
| **Team-Know-how** | ✅ Vorhanden | ⚠️ Zusätzlich nötig | ✅ Leicht erlernbar |

> **Bewertung:** Java deckt den gesamten Stack ab: Confluence-API-Client, HTML-Parsing (Jsoup), PDF-Extraktion (Tika), RAG-Framework (LangChain4j oder Spring AI), und Vektorstore-Anbindung. Es gibt keinen zwingenden Grund, für diesen Use Case auf Python zu wechseln. Kotlin wäre eine elegantere Alternative auf der JVM, bringt aber keinen funktionalen Mehrwert.

---

## 4. RAG-Frameworks im Java-Ökosystem

Stand März 2026 gibt es zwei ernsthafte Optionen für RAG in Java. Beide haben im Mai 2025 ihre 1.0 GA Releases erreicht und sind produktionsreif.

### 4.1 LangChain4j

| Eigenschaft | Details |
|---|---|
| **Philosophie** | Toolbox-Ansatz: Baukasten aus modularen Komponenten, frei kombinierbar |
| **RAG-Pipeline** | DocumentLoader → DocumentSplitter → EmbeddingModel → EmbeddingStore. Sehr ausgereift mit RecursiveCharacterSplitter, Sentence-Splitter etc. |
| **VectorStores** | 30+ Integrationen: PgVector, Qdrant, Milvus, Chroma, Weaviate, Elasticsearch, Redis u.v.m. |
| **LLM-Provider** | 20+ Provider: OpenAI, Anthropic (Claude), Ollama, Azure, AWS Bedrock, Google etc. |
| **DocumentLoader** | PDF, Word, HTML, Text – via Apache Tika Integration |
| **Spring-Integration** | Optional über `langchain4j-spring-boot-starter`, aber nicht zwingend |
| **Community** | 9.2k+ GitHub Stars, aktive Community, schnelle Adoption neuer Features |

**Stärken für diesen Use Case:** Flexible RAG-Pipeline, man kann den Confluence-Crawler als eigenen DocumentLoader implementieren. Kein Framework-Lock-in. Sehr gute Text-Splitter.

### 4.2 Spring AI

| Eigenschaft | Details |
|---|---|
| **Philosophie** | Spring-native: AI als First-Class Citizen im Spring-Ökosystem mit Auto-Configuration, Beans, Actuator |
| **RAG-Pipeline** | ETL-Framework: DocumentReader → Transformer → VectorStore. QuestionAnswerAdvisor für RAG out-of-the-box |
| **VectorStores** | PgVector, Milvus, Qdrant, Chroma, Redis, Weaviate, Neo4j, MongoDB Atlas u.a. |
| **LLM-Provider** | Anthropic, OpenAI, Azure, AWS Bedrock, Google, Ollama |
| **DocumentReader** | Tika-basiert, S3, MongoDB – eigene Reader implementierbar |
| **Observability** | Spring Boot Actuator, Micrometer-Metriken, Health-Checks nativ |
| **Community** | 7.2k+ GitHub Stars, VMware/Broadcom-backed, stabiler Release-Zyklus |

**Stärken für diesen Use Case:** Wenn bereits Spring Boot im Einsatz ist, fühlt sich Spring AI wie eine natürliche Erweiterung an. Structured Output, Observability und API-Stabilität sind Pluspunkte für Enterprise-Umgebungen.

### 4.3 Direkter Vergleich

| Dimension | LangChain4j | Spring AI |
|---|---|---|
| **Setup-Aufwand** | Minimal, plain Java reicht | Spring Boot Projekt nötig |
| **RAG-Flexibilität** | Sehr hoch (Baukasten) | Hoch (ETL-Pattern) |
| **Neue AI-Features** | Schnelle Adoption | Stabiler, langsamer |
| **Enterprise-Readiness** | Gut (Community-driven) | Sehr gut (Spring-Öko) |
| **Lernkurve** | Flach, eigene Konzepte | Flach wenn Spring bekannt |
| **Lock-in** | Gering | Spring-Ökosystem |

> **Empfehlung:** Wenn du bereits Spring Boot einsetzt: **Spring AI**. Es integriert sich nahtlos und liefert Observability, Health-Checks und strukturierte Konfiguration out-of-the-box. Wenn du maximale Flexibilität willst oder kein Spring Boot im Projekt hast: **LangChain4j**. Beide Frameworks können den gleichen VectorStore und LLM-Provider nutzen – die Wahl betrifft nur die Orchestrierungsschicht.

---

## 5. VectorStore-Auswahl

Der VectorStore ist die zentrale Komponente der RAG-Pipeline. Für einen On-Premise-Einsatz kommen primär Open-Source-Lösungen in Frage.

| Kriterium | pgvector | Qdrant | Milvus | Chroma | Weaviate |
|---|---|---|---|---|---|
| **Setup** | PostgreSQL Extension | Docker/Binary | Docker + Deps | Docker/pip | Docker |
| **Einfachheit** | ★★★★★ | ★★★★☆ | ★★★☆☆ | ★★★★★ | ★★★★☆ |
| **Skalierbarkeit** | ★★★☆☆ | ★★★★☆ | ★★★★★ | ★★☆☆☆ | ★★★★☆ |
| **Java-SDK** | JDBC (nativ) | Offiziell | Offiziell | Community | Offiziell |
| **Hybrid Search** | Ja (mit tsvector) | Ja (Payload) | Ja (Multi-Index) | Begrenzt | Ja (nativ) |
| **On-Premise** | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Vektoren-Limit** | ~10M performant | 100M+ | Milliarden | ~100K ideal | 100M+ |

### Einschätzung für typische Confluence-Instanzen

Eine typische Enterprise-Confluence-Instanz hat zwischen 10.000 und 500.000 Seiten. Bei einem durchschnittlichen Chunking von 3–5 Chunks pro Seite ergibt das 30.000 bis 2.500.000 Vektoren. Das ist für alle genannten VectorStores problemlos handhabbar.

> **Empfehlung:** **pgvector** wenn bereits PostgreSQL im Einsatz ist – minimaler Zusatzaufwand, SQL-basierte Abfragen, und für Confluence-Größen mehr als ausreichend. **Qdrant** wenn ein dedizierter, performanter VectorStore gewünscht ist – einfaches Setup via Docker, exzellente Filterung, gutes Java-SDK. **Milvus** nur wenn Skalierung auf Milliarden von Vektoren absehbar ist.

---

## 6. Architekturübersicht

Die Lösung besteht aus drei logischen Komponenten, die als einzelne Spring Boot Applikation oder als separate Services realisiert werden können:

### 6.1 Komponente 1: Confluence Crawler

Verantwortlich für die Extraktion aus Confluence via REST API.

| Aufgabe | Technologie |
|---|---|
| **HTTP-Client** | JDK HttpClient (ab Java 11) oder WebClient (Spring WebFlux) – async für höheren Durchsatz |
| **HTML → Plaintext** | Jsoup: Confluence Storage Format (XHTML) parsen, Makros/Tabellen/Listen in lesbaren Text umwandeln |
| **PDF-Extraktion** | Apache Tika: Universeller Parser für Attachments (PDF, Word, Excel etc.) |
| **Inkrement-Tracking** | CQL-Query mit lastModified-Filter + lokaler Timestamp-Speicher (DB oder Datei) |
| **Output** | Strukturierte Dokumente (JSON) mit Metadaten: Space, Titel, Labels, Autor, URL, Hierarchie |

### 6.2 Komponente 2: Document Processing / Ingestion

Transformiert rohe Confluence-Inhalte in Embeddings und lädt sie in den VectorStore.

| Aufgabe | Technologie |
|---|---|
| **Text-Splitting** | LangChain4j `DocumentSplitters.recursive()` oder Spring AI `TextSplitter` – Chunk-Größe 500–800 Token mit 50–100 Token Overlap |
| **Embedding-Modell** | OpenAI text-embedding-3-small, oder lokal via Ollama (z.B. nomic-embed-text, mxbai-embed-large) |
| **Metadaten-Anreicherung** | Space-Key, Seitentitel, Labels, URL, letztes Änderungsdatum – als Filterdimensionen im VectorStore |
| **VectorStore-Client** | Framework-spezifisch: Spring AI VectorStore Bean oder LangChain4j EmbeddingStore |

### 6.3 Komponente 3: RAG Query Service

Nimmt Benutzeranfragen entgegen und generiert Antworten mit Kontext aus dem VectorStore.

| Aufgabe | Technologie |
|---|---|
| **Query Embedding** | Gleiches Embedding-Modell wie bei Ingestion |
| **Similarity Search** | Top-K Suche mit optionalem Metadata-Filter (z.B. nur bestimmte Spaces) |
| **LLM-Generierung** | Claude, GPT-4o, oder lokales Modell via Ollama – mit Quellenangabe |
| **API** | REST-Endpunkt oder Chat-Interface |

---

## 7. Datenfluss im Überblick

| # | Schritt | Input | Output |
|---|---|---|---|
| 1 | Space Discovery | `GET /rest/api/space` | Liste aller Space-Keys |
| 2 | Seiten crawlen | `GET /rest/api/content?spaceKey=X&expand=body.storage,...` | Seiten mit XHTML-Body, Metadaten, Labels |
| 3 | HTML → Plaintext | XHTML Storage Format | Clean Plaintext + strukturierte Metadaten |
| 4 | Kommentare & Attachments | `GET .../child/comment`, `.../child/attachment` | Zusätzliche Textblöcke |
| 5 | Chunking | Plaintext-Dokumente | Chunks à 500–800 Token |
| 6 | Embedding | Text-Chunks | Vektoren (z.B. 1536-dim) |
| 7 | VectorStore Upsert | Vektoren + Metadaten | Indizierter VectorStore |

---

## 8. Komplexitätsschätzung

Geschätzter Aufwand für einen erfahrenen Java-Entwickler:

| Komponente | Aufwand | Komplexität | Abhängigkeiten |
|---|---|---|---|
| Confluence REST API Client | 2–3 Tage | 🟢 Niedrig | HttpClient, Jsoup |
| HTML → Plaintext Konverter | 1–2 Tage | 🟢 Niedrig | Jsoup |
| Attachment-Extraktion (PDF etc.) | 1–2 Tage | 🟢 Niedrig | Apache Tika |
| Inkrementelle Sync-Logik | 1–2 Tage | 🟡 Mittel | CQL, Timestamp-Store |
| RAG-Pipeline (Chunking + Embedding) | 2–3 Tage | 🟡 Mittel | LangChain4j / Spring AI |
| VectorStore Setup + Integration | 1 Tag | 🟢 Niedrig | pgvector / Qdrant |
| Query Service + API | 2–3 Tage | 🟡 Mittel | LLM-Provider, REST |
| **GESAMT** | **10–16 Tage** | | |

---

## 9. Empfohlener Tech-Stack

Basierend auf der Analyse empfehle ich folgenden Stack für die einfachste und effizienteste Umsetzung:

| Schicht | Technologie | Begründung |
|---|---|---|
| **Sprache** | **Java 17+** | Bestehendes Know-how, gesamter Stack abgedeckt |
| **Framework** | **Spring Boot 3.x** | Basis für REST API, Scheduling, Configuration |
| **RAG-Framework** | **Spring AI 1.0+** | Native Spring-Integration, Observability, stabile API |
| Alternative RAG | LangChain4j 1.0+ | Wenn kein Spring Boot im Projekt oder mehr Flexibilität gewünscht |
| **HTTP-Client** | **JDK HttpClient** | Kein Zusatz-Dependency, async-fähig, ab Java 11 |
| **HTML-Parsing** | **Jsoup** | De-facto Standard für HTML/XML in Java, robust, performant |
| **PDF-Extraktion** | **Apache Tika** | Universeller Dokumentenparser, auch für Word, Excel, etc. |
| **VectorStore** | **pgvector (PostgreSQL)** | Einfachste Lösung wenn PostgreSQL vorhanden. Alternative: Qdrant |
| **Embedding-Modell** | **OpenAI oder Ollama (lokal)** | OpenAI für Qualität, Ollama für On-Premise/Datenschutz |
| **LLM** | **Claude / GPT-4o / Ollama** | Nach Anforderung: Cloud-API oder lokales Modell |

---

## 10. Nächste Schritte

| # | Schritt | Entscheidung |
|---|---|---|
| 1 | RAG-Framework wählen | Spring AI (bei bestehendem Spring-Projekt) oder LangChain4j (standalone) |
| 2 | VectorStore wählen | pgvector (wenn PostgreSQL vorhanden) oder Qdrant (dediziert) |
| 3 | Embedding-Modell & LLM festlegen | Cloud-API vs. On-Premise (Ollama) – Datenschutz-Anforderungen klären |
| 4 | PoC: Confluence Crawler | REST API Client + Jsoup für einen Space als Proof of Concept |
| 5 | PoC: End-to-End RAG | Crawler → Chunking → Embedding → VectorStore → Query für einen Space durchspielen |
| 6 | Produktivbetrieb planen | Scheduling, Monitoring, Error Handling, Security (PAT vs. Basic Auth) |

> **Fazit:** Die Gesamtkomplexität ist überschaubar (10–16 Entwicklertage). Der größte Aufwand liegt nicht in der Technik, sondern in der Feinabstimmung: Welche Spaces sollen indexiert werden? Wie sollen Makros behandelt werden? Welche Chunk-Größe liefert die besten Ergebnisse? Ein PoC mit einem einzelnen Space und dem empfohlenen Stack liefert innerhalb von 3–5 Tagen belastbare Erkenntnisse.
