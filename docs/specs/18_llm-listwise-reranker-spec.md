# Spec: LLM-basiertes Listwise-Reranking via Ollama

**Datum:** 2026-04-11
**Status:** Spec
**Bezug:** [16_cross-encoder-reranker-spec.md](16_cross-encoder-reranker-spec.md) (wird durch diesen Spec abgelöst, Code bleibt aber als alternative Implementierung erhalten)
**Roadmap:** [17_retrieval-quality-improvement-options.md](17_retrieval-quality-improvement-options.md)

**Betroffene Dateien:**
- `src/main/java/at/openaustria/confluencerag/query/Reranker.java` (**neu**, Interface)
- `src/main/java/at/openaustria/confluencerag/query/LlmListwiseReranker.java` (**neu**)
- `src/main/java/at/openaustria/confluencerag/query/NoOpReranker.java` (**neu**)
- `src/main/java/at/openaustria/confluencerag/query/RerankerService.java` → wird zu `InfinityCrossEncoderReranker.java` (rename + Interface implementieren)
- `src/main/java/at/openaustria/confluencerag/query/QueryService.java` (Depends on `Reranker`-Interface)
- `src/main/java/at/openaustria/confluencerag/config/QueryProperties.java` (Erweitert um `type` und per-Type Properties)
- `src/main/resources/application.yml` (neue Config-Struktur)
- `docker-compose.yml` (Reranker-Service als optionaler Block kommentiert)
- `src/test/java/at/openaustria/confluencerag/query/LlmListwiseRerankerTest.java` (**neu**)
- `src/test/java/at/openaustria/confluencerag/query/InfinityCrossEncoderRerankerTest.java` (umbenannt aus `RerankerServiceTest.java`)
- `CLAUDE.md`

---

## 1. Motivation & Architektur-Pivot

### 1.1 Warum nicht (mehr) Infinity?

Spec 16 hat einen Cross-Encoder-Reranker basierend auf einem externen `michaelf34/infinity` Container vorgesehen. Im Test-Setup hat sich gezeigt:

1. **Image-Größe ist 4.5 GB** (CUDA + PyTorch-Stack) — auf einer instabilen Verbindung praktisch nicht zuverlässig zu pullen
2. **Auf den produktiven Zielsystemen ist Docker Hub gesperrt** — nur eine private Docker Registry ist erreichbar
3. **Anforderung: Reranker unter eigener Kontrolle** — kein Drittanbieter-Service, kein externes Image, kein Hub-Vendoring

### 1.2 Lösung: LLM-basiertes Listwise-Reranking

Ollama läuft auf allen Zielsystemen ohnehin (für Embeddings und Chat). Statt ein dediziertes Cross-Encoder-Modell zu betreiben, nutzen wir ein bereits verfügbares kleines LLM (`qwen3:0.6b` o.ä.) als Reranker. Das LLM bekommt in **einem einzigen Call** alle Kandidaten als nummerierte Liste und gibt die Top-K als JSON-Array zurück.

**Vorteile gegenüber Infinity:**
- **Null neue Software** — nutzt bestehendes Ollama
- **Null neue Container** — kein Image-Pull, kein Hub-Zugriff
- **Voll unter eigener Kontrolle** — Modell läuft im selben Ollama-Prozess wie Embeddings/Chat
- Modell-Wahl pro Deployment konfigurierbar ohne Code-Änderung

**Trade-offs:**
- Qualität liegt bei ca. 70–85% eines echten Cross-Encoders (statt 95%+) — aber immer noch deutlich besser als die alte Keyword-Heuristik und besser als rohe Vektor-Reihenfolge
- Latenz: 1–3 Sekunden zusätzlich pro Query (ein LLM-Call) statt 200–800 ms (Cross-Encoder-Container)
- LLMs können halluzinieren → Robustheit beim JSON-Parsing wichtig

### 1.3 Bestehender Code wird nicht weggeworfen

Die Infinity-Implementierung aus PR #35 wird **nicht entfernt**, sondern in das neue Interface integriert. Beide Reranker sind über die Konfiguration auswählbar:

- `query.reranker.type=llm` (Default) — LLM-Listwise via Ollama
- `query.reranker.type=infinity` — Infinity-Cross-Encoder (für Umgebungen wo der Container verfügbar ist)
- `query.reranker.type=none` — kein Rerank, Vektor-Reihenfolge

So bleibt der Aufwand aus #33/#35 nutzbar und das Setup ist zukunftssicher.

---

## 2. Interface & Bean-Selection

### 2.1 `Reranker` Interface

Neue Datei: `src/main/java/at/openaustria/confluencerag/query/Reranker.java`

```java
package at.openaustria.confluencerag.query;

import org.springframework.ai.document.Document;
import java.util.List;

/**
 * Reordering strategy applied to vector-search candidates before they
 * are passed to the LLM as context. Implementations choose how to
 * compute relevance scores (LLM listwise, cross-encoder, no-op, ...).
 */
public interface Reranker {

    /**
     * Re-rank the given candidates and return the top {@code topK}
     * documents in new order. Implementations must fall back to the
     * original order on any error to keep the query path safe.
     */
    List<Document> rerank(String query, List<Document> candidates, int topK);

    /**
     * Number of candidates the QueryService should fetch from Qdrant
     * before passing them to this reranker. Implementations have
     * different sweet spots: LLMs are limited by context window,
     * cross-encoders can handle more.
     */
    int candidateCount();
}
```

### 2.2 Drei Bean-Implementierungen

Alle drei sind `@Service` annotiert, aber durch `@ConditionalOnProperty` ist immer **genau einer** aktiv:

| Implementierung | Aktivierung | Default |
|-----------------|-------------|---------|
| `LlmListwiseReranker` | `query.reranker.type=llm` | ✅ (`matchIfMissing=true`) |
| `InfinityCrossEncoderReranker` | `query.reranker.type=infinity` | nein |
| `NoOpReranker` | `query.reranker.type=none` | nein |

```java
@Service
@ConditionalOnProperty(prefix = "query.reranker", name = "type",
        havingValue = "llm", matchIfMissing = true)
public class LlmListwiseReranker implements Reranker { ... }

@Service
@ConditionalOnProperty(prefix = "query.reranker", name = "type",
        havingValue = "infinity")
public class InfinityCrossEncoderReranker implements Reranker { ... }

@Service
@ConditionalOnProperty(prefix = "query.reranker", name = "type",
        havingValue = "none")
public class NoOpReranker implements Reranker { ... }
```

`QueryService` injiziert dann nur `Reranker` (das Interface) und weiß nicht welche Implementierung aktiv ist.

---

## 3. Configuration Model

### 3.1 `QueryProperties` Refactoring

```java
@ConfigurationProperties(prefix = "query")
public record QueryProperties(
    int topK,
    double similarityThreshold,
    RerankerProperties reranker
) {
    public record RerankerProperties(
        String type,                           // llm | infinity | none
        LlmRerankerProperties llm,
        InfinityRerankerProperties infinity
    ) {}

    public record LlmRerankerProperties(
        String baseUrl,                        // Ollama base URL
        String model,                          // e.g. qwen3:0.6b
        int candidateCount,                    // chunks pro Rerank-Call
        int timeoutSeconds,
        int maxChunkChars                      // pro Chunk im Prompt
    ) {}

    public record InfinityRerankerProperties(
        String baseUrl,
        String model,
        int candidateCount,
        int timeoutSeconds
    ) {}
}
```

Das alte `enabled`-Feld entfällt — `type=none` ersetzt es semantisch sauberer.

### 3.2 `application.yml`

```yaml
query:
  top-k: ${QUERY_TOP_K:5}
  similarity-threshold: ${QUERY_SIMILARITY_THRESHOLD:0.45}
  reranker:
    type: ${QUERY_RERANKER_TYPE:llm}              # llm | infinity | none
    llm:
      base-url: ${QUERY_RERANKER_LLM_URL:http://localhost:11434}
      model: ${QUERY_RERANKER_LLM_MODEL:qwen3:0.6b}
      candidate-count: ${QUERY_RERANKER_LLM_CANDIDATES:15}
      timeout-seconds: ${QUERY_RERANKER_LLM_TIMEOUT:60}
      max-chunk-chars: ${QUERY_RERANKER_LLM_MAX_CHUNK:500}
    infinity:
      base-url: ${QUERY_RERANKER_INFINITY_URL:http://localhost:7997}
      model: ${QUERY_RERANKER_INFINITY_MODEL:BAAI/bge-reranker-v2-m3}
      candidate-count: ${QUERY_RERANKER_INFINITY_CANDIDATES:30}
      timeout-seconds: ${QUERY_RERANKER_INFINITY_TIMEOUT:10}
```

---

## 4. `LlmListwiseReranker` Implementierung

### 4.1 Prompt-Design

Listwise mit JSON-Array Ausgabe — bewährte Methode in der Literatur (RankGPT):

```
Du bist ein Such-Assistent. Bewerte die Relevanz folgender Dokument-Auszüge
für die Frage.

Frage: "{query}"

Dokumente:
[1] Titel: {title}
{content_truncated_to_500_chars}

[2] Titel: {title}
{content_truncated_to_500_chars}

...

[N] Titel: {title}
{content_truncated_to_500_chars}

Gib die Nummern der {topK} relevantesten Dokumente in absteigender Relevanz
als JSON-Array zurück. Beispiel: [3, 7, 1, 12, 5]

Antworte AUSSCHLIESSLICH mit dem Array. Keine Erklärung, kein Markdown,
keine Klammern oder Anführungszeichen drumherum. Nur das Array.
```

### 4.2 Inferenz-Flow

```java
public List<Document> rerank(String query, List<Document> candidates, int topK) {
    if (candidates.isEmpty()) return List.of();
    if (candidates.size() <= topK) return candidates;

    try {
        String prompt = buildPrompt(query, candidates, topK);
        String response = callOllama(prompt);  // POST /api/generate
        List<Integer> ranking = parseJsonArray(response);
        return mapToDocuments(ranking, candidates, topK);
    } catch (Exception e) {
        log.warn("LLM-Rerank fehlgeschlagen, fallback auf Vektor-Reihenfolge: {}",
                 e.getMessage());
        return candidates.stream().limit(topK).toList();
    }
}
```

### 4.3 Robustheits-Anforderungen

1. **JSON-Parse-Fallback**: Wenn das LLM Markdown-Wrap, Erklärungstext oder kaputtes JSON liefert, mit Regex das erste `[...]`-Array extrahieren und parsen
2. **Index-Validierung**: Nur Indices `1 ≤ i ≤ N` akzeptieren, Duplikate entfernen
3. **Auffüllen**: Wenn das LLM weniger als `topK` Indices liefert, mit den fehlenden Original-Top-Kandidaten auffüllen
4. **Truncation**: Jeder Chunk auf `maxChunkChars` (Default 500) gekürzt damit der Prompt im Context-Window bleibt
5. **Stop-Token**: `temperature=0`, `top_p=0.1`, `num_predict=64` für deterministische, kurze Antworten
6. **Timeout-Fallback**: Bei Timeout sauber auf Vektor-Reihenfolge zurückfallen

### 4.4 Ollama API Call

Direkt über `RestClient` (kein Spring AI ChatClient — der ist für die Antwort-Generierung reserviert):

```java
POST {baseUrl}/api/generate
{
  "model": "qwen3:0.6b",
  "prompt": "...",
  "stream": false,
  "options": {
    "temperature": 0,
    "top_p": 0.1,
    "num_predict": 64
  }
}
```

Response:
```json
{
  "model": "qwen3:0.6b",
  "response": "[3, 7, 1, 12, 5]",
  ...
}
```

---

## 5. `NoOpReranker`

Trivial — gibt die ersten `topK` Kandidaten unverändert zurück. Wird nur aktiv wenn `query.reranker.type=none`. Vorteil gegenüber dem alten `enabled=false`-Pattern: keine Wenn-Abfrage in jedem Bean, sauberes Single-Responsibility.

```java
@Service
@ConditionalOnProperty(prefix = "query.reranker", name = "type", havingValue = "none")
public class NoOpReranker implements Reranker {

    private final int candidateCount;

    public NoOpReranker(QueryProperties properties) {
        // Use top-k as candidate count when no reranking happens
        this.candidateCount = properties.topK();
    }

    @Override
    public List<Document> rerank(String query, List<Document> candidates, int topK) {
        return candidates.stream().limit(topK).toList();
    }

    @Override
    public int candidateCount() {
        return candidateCount;
    }
}
```

---

## 6. `InfinityCrossEncoderReranker` (Rename + Interface)

Die bestehende `RerankerService.java` wird:
1. Umbenannt zu `InfinityCrossEncoderReranker.java`
2. `implements Reranker` hinzugefügt
3. `@ConditionalOnProperty` für `type=infinity` ergänzt
4. Konfiguration liest aus `properties.reranker().infinity()` statt der bisherigen flachen Struktur
5. Das `enabled`-Handling entfällt (wird durch Bean-Selection ersetzt)
6. Test-Datei wird entsprechend umbenannt

Die HTTP-Logik, Fallback-Logik und Tests bleiben unverändert.

---

## 7. `QueryService` Anpassung

Statt `RerankerService` injiziert `QueryService` jetzt das `Reranker`-Interface:

```java
private final Reranker reranker;

public QueryService(VectorStore vectorStore, ChatClient.Builder chatClientBuilder,
                    QueryProperties queryProperties, Reranker reranker) {
    // ...
    this.reranker = reranker;
}

private List<Document> searchRelevantDocs(QueryRequest request) {
    int fetchK = Math.max(queryProperties.topK(), reranker.candidateCount());
    // ... search wie bisher
    List<Document> candidates = vectorStore.similaritySearch(searchBuilder.build());
    return reranker.rerank(request.question(), candidates, queryProperties.topK());
}
```

`QueryService` muss nicht wissen welche Reranker-Implementierung aktiv ist. Spring sorgt dafür dass genau eine vorhanden ist.

---

## 8. `docker-compose.yml`

Der `reranker`-Service-Block bleibt im File, aber wird **auskommentiert** mit Hinweis darauf dass der Default-LLM-Reranker (Option llm) keinen Container braucht. Wer den Infinity-Pfad nutzen will, kommentiert den Block ein und setzt `QUERY_RERANKER_TYPE=infinity`.

```yaml
# Optional: dedizierter Cross-Encoder Reranker (alternative zum Default-LLM-Reranker).
# Aktiviert via QUERY_RERANKER_TYPE=infinity. Image ist groß (~4.5 GB), nur sinnvoll
# wenn Docker Hub bzw. das Mirror-Image verfügbar ist.
#
#  reranker:
#    image: michaelf34/infinity:latest
#    ports:
#      - "7997:7997"
#    command: ["v2", "--model-id", "BAAI/bge-reranker-v2-m3", "--port", "7997"]
#    volumes:
#      - reranker_cache:/app/.cache
#    restart: unless-stopped
```

Auch das `depends_on: reranker` im `app`-Service wird entfernt — die App soll auch starten wenn kein Reranker-Container läuft (bei `type=llm` oder `type=none` braucht sie ihn nicht).

---

## 9. Tests

### 9.1 Neue Tests: `LlmListwiseRerankerTest`

Pro Szenario ein Test mit eingebettetem `HttpServer` der die Ollama-API mockt (gleiche Pattern wie der existierende Reranker-Test):

1. **Happy path** — Ollama liefert sauberes JSON-Array → Reihenfolge korrekt
2. **JSON in Markdown wrap** — Ollama liefert ` ```json\n[3,1,2]\n``` ` → Parser extrahiert das Array trotzdem
3. **JSON mit Erklärung davor** — Ollama liefert "Hier sind die Ergebnisse: [3,1,2]" → wird trotzdem geparst
4. **Out-of-range Index** — Ollama liefert `[99, 1, 2]` → 99 wird verworfen
5. **Duplikate** — Ollama liefert `[1, 1, 2]` → Duplikat verworfen
6. **Zu wenige Indices** — Ollama liefert `[3]` aber topK=5 → wird mit Original-Top aufgefüllt
7. **HTTP-Fehler** — Ollama 500 → Fallback auf Vektor-Reihenfolge
8. **Timeout** — Mock blockiert > Timeout → Fallback
9. **Komplett kaputtes JSON** — Ollama liefert "lorem ipsum" → Fallback
10. **Leere Kandidaten-Liste** → leere Liste zurück
11. **Weniger Kandidaten als topK** → unverändert zurück (kein LLM-Call nötig)

### 9.2 `InfinityCrossEncoderRerankerTest`

Bestehende `RerankerServiceTest` wird umbenannt und an die neue Constructor-Signatur angepasst (Properties-Pfad ändert sich von `reranker()` auf `reranker().infinity()`). Logik der Tests bleibt identisch.

### 9.3 `NoOpRerankerTest`

Trivial: `rerank()` gibt die ersten `topK` zurück, `candidateCount()` gibt den konfigurierten Wert.

---

## 10. Akzeptanzkriterien

- [ ] `Reranker`-Interface existiert mit `rerank()` und `candidateCount()`
- [ ] `LlmListwiseReranker` implementiert das Interface, ruft Ollama `/api/generate` auf, parst JSON robust mit allen 11 Test-Szenarien
- [ ] `InfinityCrossEncoderReranker` ist die umbenannte alte Implementierung, jetzt Interface-konform
- [ ] `NoOpReranker` ist die explizite "kein Rerank"-Variante
- [ ] Genau **eine** Reranker-Bean wird beim Start instanziiert (über `@ConditionalOnProperty`)
- [ ] Default beim Start ohne Config: `LlmListwiseReranker` aktiv
- [ ] App startet mit `QUERY_RERANKER_TYPE=infinity` (vorausgesetzt Container läuft) — nutzt Infinity
- [ ] App startet mit `QUERY_RERANKER_TYPE=none` — nutzt NoOp
- [ ] App startet mit `QUERY_RERANKER_TYPE=llm` (oder ohne Variable) — nutzt LLM
- [ ] `QueryService` injiziert nur `Reranker`, nicht die konkrete Klasse
- [ ] LLM-Reranker fällt bei jedem Fehler-Fall (HTTP, Timeout, Parse-Fehler) auf Vektor-Reihenfolge zurück, ohne die Query zu brechen
- [ ] `application.yml` enthält die neue Config-Struktur mit allen ENV-Variablen
- [ ] `docker-compose.yml` Reranker-Block ist auskommentiert mit Hinweis
- [ ] Alle bestehenden Tests grün, plus neue LlmListwiseRerankerTest
- [ ] `CLAUDE.md` aktualisiert mit den drei Reranker-Modi und Default-Hinweis
- [ ] Spec 16 markiert als "superseded by Spec 18"
- [ ] Roadmap (Spec 17) erwähnt den Pivot

---

## 11. Migrations-Hinweis

**Breaking Change** für bestehende Deployments die `QUERY_RERANKER_ENABLED=false` setzen:

- Alt: `QUERY_RERANKER_ENABLED=false`
- Neu: `QUERY_RERANKER_TYPE=none`

Ohne Anpassung bleibt das Verhalten identisch (wegen `matchIfMissing=true` ist `llm` der Default), aber die alte ENV-Variable wird ignoriert. Im Migrations-Commit wird das in der README/CLAUDE.md vermerkt.

Für die meisten Deployments wird die Umstellung **transparent**: Default-Verhalten ist jetzt LLM-Rerank statt Infinity, was eine Verbesserung gegenüber dem aktuellen `none`-State (Reranker mangels Container deaktiviert) darstellt.

---

## 12. Performance-Erwartung

Mit `qwen3:0.6b` als Reranker-Modell auf moderater Hardware:

- 15 Kandidaten × ~400 Zeichen = ~6000 Zeichen Prompt-Eingabe
- Output: ~30 Zeichen JSON-Array
- Erwartete Latenz: **800–2000 ms** pro Rerank-Call
- Gesamte Query-Latenz (Embed + Search + Rerank + LLM-Antwort): ~3–6 Sekunden (vs. ~2–4 Sek. ohne Rerank)

Wer mehr Präzision und längere Wartezeit toleriert kann auf `gemma3:4b` oder `qwen3:1.7b` umstellen — nur eine ENV-Variable.

Wer maximale Performance braucht und einen GPU-Reranker verfügbar hat, schaltet auf `type=infinity`.

---

## 13. Nicht im Scope

- Caching von Rerank-Ergebnissen (selbe Query → selbes Ranking)
- Streaming-Rerank während die Antwort generiert wird
- A/B-Vergleich der drei Reranker im laufenden Betrieb
- Automatische Modell-Auswahl je nach Query-Typ
- Erweiterung von `retrieval-quality-check.py` um den Rerank-Pfad zu messen (separates Issue)
