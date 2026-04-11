# Analyse: Optionen zur Verbesserung der Retrieval-Qualität

**Datum:** 2026-04-10
**Status:** Analyse / Option-Übersicht (kein aktiver Spec)
**Verwandte Dokumente:**
- [13_rag-antwortqualitaet-ist-analyse.md](13_rag-antwortqualitaet-ist-analyse.md) — frühere Ist-Analyse (Titel-Anreicherung, Threshold-Config)
- [15_embedding-model-upgrade-spec.md](15_embedding-model-upgrade-spec.md) — bge-m3 Upgrade (umgesetzt, PR #34)
- [16_cross-encoder-reranker-spec.md](16_cross-encoder-reranker-spec.md) — Cross-Encoder Reranker (Code fertig, PR #35)

---

## 1. Kontext & Problem

### 1.1 Das Single-Domain-Problem

Bei einem RAG-System über einen einzelnen Confluence (eine Firma, ein Themengebiet) liegen die Cosine-Scores aller Chunks in einem engen Band — typisch 0.55–0.70. Das Embedding-Modell hat gelernt, dass der gesamte Korpus „semantisch ähnlich" ist, weil Vokabular, Domain-Sprache und Kontext durchgehend ähnlich sind. Die Konsequenz: die Trennschärfe zwischen „relevant für die Frage" und „irrelevant, aber aus der gleichen Domain" ist schlecht, und irrelevante Chunks landen regelmäßig in den Top-K.

Die ursprüngliche Beobachtung war: der Reranker per Keyword-Heuristik mit 3x-Titel-Boost half ein wenig, aber reichte nicht.

### 1.2 Historische Zwischenschritte

1. **Titel im Chunk-Text** (Spec 13) — half spürbar, reicht aber nicht
2. **Konfigurierbarer Threshold** (Spec 13) — kosmetisch, löst das Spread-Problem nicht
3. **Keyword-Rerank mit Titel-Boost** (Spec 13) — Heuristik, bei semantischen Fragen unzureichend
4. **bge-m3 Upgrade** (Spec 15, umgesetzt) — ✅ hat die Off-topic-Trennung messbar verbessert (siehe 2.1)

---

## 2. Umgesetzte Maßnahmen

### 2.1 bge-m3 Embedding-Modell (PR #34, 2026-04-09)

Wechsel von `nomic-embed-text` (768 dim, Englisch-optimiert) auf `bge-m3` (1024 dim, multilingual, MTEB DE top-tier). Vector-Dimension ist jetzt konfigurierbar.

**Messergebnis** (7 Test-Queries gegen den Test-Korpus, bge-m3 + leerer Reranker):

| Query | Top-1 | Top-10 | Spread | Qualität |
|-------|-------|--------|--------|----------|
| „PlantUML Konverter" | 0.66 | 0.50 | **0.16** | ✅ alle 10 relevant |
| „Spec 07" | 0.57 | 0.53 | 0.04 | ✅ 1–6 korrekt |
| „Qdrant Collection konfigurieren" | 0.59 | 0.53 | 0.06 | ⚠️ Backup-Seite falsch auf Platz 5 |
| „Backup und Recovery" | 0.65 | 0.51 | **0.14** | ✅ top-1 exakt |
| „Admin API Endpunkte" | 0.57 | 0.52 | 0.05 | ⚠️ exakte Doku-Seite erst auf Platz 4 |
| „Was ist CORS?" | 0.45 | 0.34 | 0.12 | ⚠️ exakte CORS-Seite erst auf Platz 5 |
| 🍎 „Apfelkuchen" (off-topic) | **0.41** | 0.36 | 0.05 | ✅ unter Threshold 0.45 |

**Beobachtungen:**
- Off-topic-Queries landen sauber unter dem relevanten Band (0.36–0.41 vs 0.55–0.66). Der Default-Threshold 0.45 funktioniert jetzt als echter Cut-off.
- Bei eindeutigen Queries ist das Ergebnis sehr gut (PlantUML, Backup)
- Bei abstrakten / kompositorischen Queries bleibt der Spread eng (0.04–0.06) — das Single-Domain-Problem ist nicht verschwunden, nur abgemildert
- Titel-exakte Treffer werden manchmal von thematisch umgebenden Seiten überranked (Admin API, CORS) → Cross-Encoder würde hier helfen

### 2.2 Cross-Encoder Reranker (PR #35, Code als alternative Implementierung)

`RerankerService` rief ursprünglich einen externen `michaelf34/infinity` Container mit `BAAI/bge-reranker-v2-m3` auf (Spec 16).

**Architektur-Pivot 2026-04-11 (Spec 18, PR #40):** Auf den produktiven Zielsystemen ist Docker Hub gesperrt und das Image (~4.5 GB) ist nicht zuverlässig pullbar. Die Anforderung lautet "Reranker unter eigener Kontrolle". Konsequenz:

- **Neuer Default:** `LlmListwiseReranker` — nutzt Ollama mit einem kleinen LLM (qwen3:0.6b o.ä.) für Listwise-Reranking in einem einzigen Call. Kein neuer Container, keine Hub-Abhängigkeit.
- **Alte Implementierung erhalten:** Der Infinity-Code wurde nicht weggeworfen, sondern als `InfinityCrossEncoderReranker` über das neue `Reranker`-Interface erreichbar gemacht. Aktivierbar via `query.reranker.type=infinity` für Umgebungen wo der Container verfügbar ist.
- **Drittes Mode:** `NoOpReranker` (`type=none`) als sauberer Disable-Pfad ohne Wenn-Abfragen.

Bean-Auswahl beim Start via `@ConditionalOnProperty`. `QueryService` injiziert nur das Interface und weiß nicht welche Implementierung aktiv ist.

---

## 3. Offene Optionen

Sortiert nach Aufwand/Erwarteter Wirkung. Keine dieser Maßnahmen ist aktiv — hier nur als Inventar für spätere Entscheidungen.

### 3.1 Hybrid Search (Dense + Sparse BM25) 🟢 low-medium effort

**Worum es geht:** Qdrant unterstützt seit 1.10 Named Vectors und Sparse Vectors nativ. Man speichert pro Chunk zwei Vektoren: einen dense (bge-m3) und einen sparse (BM25 oder SPLADE). Bei der Query werden beide in einem einzigen Qdrant-Request kombiniert, meist mit Reciprocal Rank Fusion (RRF).

**Warum es hilft:** Dense-Embeddings sind schwach bei exakten Begriffen — Produktnamen, Fehlercodes, IDs, Akronyme, Versionsnummern. Sparse BM25 findet genau diese. Die Kombination fängt beide Fälle ab.

**Aufwand:**
- Spring AI 1.0 unterstützt Named/Sparse Vectors nicht out-of-the-box → Qdrant-Client direkt verwenden (haben wir bei der Deletion schon)
- Ingestion muss zwei Vektoren pro Chunk schreiben
- Query-Pfad muss die Kombinations-Logik machen
- Kein zusätzlicher Service nötig, Qdrant-nativ

**Warum potentiell besser als bge-m3 allein:** Löst genau die Fälle wo die Frage einen exakten Begriff enthält, den das Dense-Modell „glättet". Beispiel im Test: „Was ist CORS?" — BM25 würde die „Issue #12 CORS"-Seite mit exaktem Match auf Platz 1 ziehen, statt sie wegen semantischer Ähnlichkeit zu „Spec 07 Akzeptanzkriterien" zu überranken.

**Größter Hebel zusammen mit:** bge-m3 + Reranker.

### 3.2 Adaptive Threshold / MMR 🟢 low effort

**Adaptive Threshold:** Statt fester `0.45` Cut-off nehmen wir relative zum Top-Score („alles was mehr als 0.05 unter dem besten Treffer liegt wird verworfen"). Vermeidet dass bei einer schwachen Query (CORS, top1=0.45) alle Treffer leer sind.

**MMR (Maximal Marginal Relevance):** Statt einfach top-K nach Score zu nehmen, wähle K Chunks so dass sie **divers** sind — reduziert Duplikate aus derselben Seite. Praktisch: wenn die top-10 alle aus „Spec 03" kommen, nimmt MMR stattdessen top-5 aus „Spec 03" + 5 andere relevante, damit das LLM breiteren Kontext bekommt.

**Aufwand:** Beides sind kleine Änderungen in `QueryService` (10–30 Zeilen). Kein neuer Service.

**Wirkung:** Eher kosmetisch gegenüber bge-m3 + Reranker, aber billig dazuzunehmen.

### 3.3 Query Rewriting / Expansion 🟡 medium effort

**Worum es geht:** Vor der Suche lässt man ein schnelles LLM (qwen3:0.6b reicht) die Frage umformulieren oder erweitern:
- Original: „Wie mach ich Backup?"
- Expanded: „Backup, Backup-Strategie, Sicherung, Wiederherstellung, Datensicherung, Recovery"

Gesucht wird dann mit der expandierten Query.

**Warum es hilft:** Bei kurzen oder vagen Fragen fehlt dem Embedding Kontext. Expansion gibt dem Vektor mehr Material zum „Anhängen" an die passenden Chunks.

**Aufwand:** Ein LLM-Call mehr pro Query (~50–200ms bei qwen3:0.6b). Prompt-Engineering nötig.

**Wirkung:** Vor allem bei kurzen Queries spürbar. Bei langen präzisen Fragen egal bis leicht negativ.

### 3.4 HyDE (Hypothetical Document Embeddings) 🟡 medium effort

**Worum es geht:** Das LLM generiert aus der Frage eine hypothetische Antwort. Diese Antwort wird embedded statt der Frage. Die hypothetische Antwort ist lexikalisch näher am tatsächlich relevanten Chunk als die Frage selbst.

**Beispiel:** Frage „Wie wird die Qdrant Collection erstellt?" → LLM-Halluzination „Die Qdrant Collection wird mit `createCollectionAsync` und `VectorParams.newBuilder()` angelegt, Distance ist Cosine..." → Embedding dieser Pseudo-Antwort matcht direkt auf den passenden Chunk.

**Warum es hilft:** Überbrückt den semantischen Gap zwischen Frage-Vokabular und Dokumenten-Vokabular. Gerade bei technischen Dokumentationen sehr effektiv.

**Aufwand:** Ein LLM-Call mehr pro Query (~200–500ms), simpler Prompt. Die Pseudo-Antwort wird verworfen, nur das Embedding davon wird benutzt.

**Wirkung:** Messbarer Sprung bei Query-↔-Dokument-Vokabular-Mismatch. Für einen Entwickler-Confluence mit Code/Konfig-Snippets besonders geeignet.

### 3.5 Multi-Query 🟡 medium effort

**Worum es geht:** Das LLM erzeugt 3–5 Varianten der Frage. Jede Variante wird gegen Qdrant geschickt. Die Ergebnisse werden mit RRF (Reciprocal Rank Fusion) fusioniert.

**Aufwand:** 1 LLM-Call für Varianten + N Qdrant-Calls. Erhöht Query-Latenz spürbar (500–1000ms).

**Wirkung:** Bei mehrdeutigen / offenen Fragen gut. Bei klaren Fragen redundant.

### 3.6 Knowledge Graph / LightRAG 🔴 high effort

**Worum es geht:** LightRAG extrahiert beim Ingest mit einem LLM aus jedem Chunk Entities (Kubernetes, Backup-Policy XY, Marketing-Team) und Relations (Backup-Policy XY *gilt für* Kubernetes-Cluster). Der Graph wird in einer Graph-DB gespeichert. Bei einer Query macht LightRAG einen dualen Retrieval: Vector Search über Chunks plus Graph-Traversal über Entities/Relations.

**Warum es potentiell hilft:**
- Versteht Cross-Referenzen zwischen Dokumenten
- Kann „Wie hängt X mit Y zusammen"-Fragen besser beantworten
- Löst Single-Domain strukturell, nicht nur statistisch

**Warum wir es nicht als ersten Schritt empfehlen:**
- **Ingestion-Kosten explodieren** — jeder Chunk muss durch ein LLM für Entity-Extraction. Bei unserem Test-Setup (248 Chunks) wäre das noch OK, bei 10k+ Chunks wird es sehr langsam
- **Quality-Abhängig vom Extraction-LLM** — qwen3:0.6b reicht nicht, man braucht mindestens ein 7B-Modell für brauchbare Entities
- **Zusätzliche Infrastruktur** — Graph-Store (Neo4j, NetworkX) als neuer Service
- **Kompletter Umbau** der Ingestion- und Query-Services
- **Unklarer ROI** — das Kern-Problem (schwache Trennschärfe) löst ein Graph nicht direkt; wenn Titel/Content schon gut embedded sind, bringt Graph-Traversal wenig dazu

**Knowledge Graph mit Qdrant statt LightRAG:** Technisch möglich — Entity-Liste und Nachbar-Chunk-IDs als Payload in jedem Chunk, Traversal in der App. Ist aber Eigenbau und nicht annähernd so sauber wie LightRAG. Für einen echten Graph-RAG wäre Neo4j die bessere Wahl — Neo4j hat seit Neuestem auch eigene Vektorsuche integriert, was Qdrant dann überflüssig machen würde.

**Fazit:** LightRAG / Graph-RAG ist eine architektonische Neu-Ausrichtung, nicht eine inkrementelle Verbesserung. Nur interessant wenn alle anderen Optionen ausgeschöpft sind und die Domänen-Logik stark genug ist, dass strukturierte Beziehungen mehr bringen als semantische Ähnlichkeit.

---

## 4. Empfohlene Priorisierung

Sortiert nach Wirkung-pro-Aufwand — zum späteren Nachlesen wenn wir den nächsten Schritt auswählen:

1. **Cross-Encoder Reranker deployen** — Code ist schon da (PR #35), es fehlt nur der `infinity` Container in der Infrastruktur. Höchste Priorität weil Arbeit schon gemacht ist und der Reranker die im Test gesehenen engen Spreads (Admin API, CORS) direkt löst.
2. **Hybrid Search (Dense + BM25)** — fängt genau die Lücke ab die bge-m3 nicht füllt (exakte Begriffe, Codes, IDs). Kein neuer Service, nur Qdrant-Client-Arbeit. Mittlerer Aufwand, hohe Wirkung.
3. **HyDE** — bei technischer Dokumentation besonders effektiv. Ein LLM-Call mehr, simpler Prompt.
4. **Adaptive Threshold / MMR** — billig dazu, kleiner Gewinn in Edge-Cases.
5. **Query Rewriting / Multi-Query** — nur wenn Stichproben zeigen dass kurze/vage Fragen das dominante Failure-Pattern sind.
6. **LightRAG / Knowledge Graph** — nicht vor 1–5 sinnvoll. Architektonische Entscheidung, die eine eigene Evaluation verdient.

---

## 5. Messmethode

Für jeden dieser Schritte brauchen wir Before/After-Vergleich statt Bauchgefühl. Das Script `scripts/retrieval-quality-check.py` (siehe [`scripts/README.md`](../../scripts/README.md)) ist dafür gedacht:

```bash
# Vorher messen (aktueller Stand, z.B. ohne Reranker)
python3 scripts/retrieval-quality-check.py > before.txt

# Änderung deployen

# Nachher messen
python3 scripts/retrieval-quality-check.py > after.txt

# Diffen
diff before.txt after.txt
```

Die QUERIES-Liste im Script muss für produktive Daten angepasst werden — aktuell sind dort die Beispiel-Queries aus dem Test-Korpus. Ideal wäre ein fester „Gold Standard"-Set von 20–30 Fragen aus echten User-Anfragen mit bekannten erwarteten Quellen.

---

## 6. Offene Fragen

- **Gold Standard aufbauen:** Für seriöse Messung brauchen wir Query → erwartete Quelle Paare aus echten User-Fragen. Ideal automatisiert aus Chat-History sobald die im Einsatz ist.
- **Latenz-Budget:** Welche End-to-End-Query-Latenz ist akzeptabel? Das bestimmt welche der Optionen kombinierbar sind. Reranker + HyDE + Multi-Query zusammen kann leicht 2–3 Sekunden kosten bevor das LLM überhaupt startet.
- **Confluence-Scale:** Die oben genannten Ingestion-Kosten (LightRAG) sind abhängig von Dokument-Anzahl. Für einen kleinen Team-Confluence kann Graph-RAG durchaus machbar sein, für einen firmenweiten Confluence nicht.
