# Requirement-Analyse: RAG-Antwortqualität optimieren

**Datum:** 2026-03-24
**Bezug:** [Ist-Analyse](13_rag-antwortqualitaet-ist-analyse.md)

---

## 1. Ziel

Benutzer sollen auf dokumentenbezogene Anfragen (z.B. "Was steht in Spec 07?", "Zeige mir die Anforderungen aus Phase 2") korrekte, relevante Antworten erhalten. Die Similarity Search muss die richtigen Chunks finden — auch wenn die Suchanfrage sich auf Titel, Labels oder Hierarchie bezieht.

---

## 2. Funktionale Requirements

### REQ-01: Chunk-Anreicherung mit Titel und Kontext (KRITISCH)

**Ist:** Chunk-Text enthält nur den Body-Text der Confluence-Seite.
**Soll:** Jeder Chunk-Text muss mit einem strukturierten Header angereichert werden, der folgende Informationen enthält:

- **Seitentitel** (immer)
- **Breadcrumb/Ancestors** (wenn vorhanden)
- **Labels** (wenn vorhanden)
- **Chunk-Typ** (PAGE, COMMENT, ATTACHMENT)

**Format-Beispiel:**
```
Titel: Spec 07 — Authentifizierung
Pfad: Projektdoku > Specs
Labels: spec, security, phase-2
Typ: PAGE

[eigentlicher Chunk-Text]
```

**Begründung:** Der Seitentitel und die Hierarchie müssen im Embedding-Vektor repräsentiert sein, damit dokumentenbezogene Anfragen korrekt zugeordnet werden.

**Akzeptanzkriterium:** Eine Suche nach "Spec 07" liefert Chunks der Seite "Spec 07" in den Top-Ergebnissen, nicht Chunks anderer Seiten.

### REQ-02: Chunk-Overlap aktivieren (KRITISCH)

**Ist:** `chunkOverlap` ist konfiguriert (50 Tokens) aber wird nicht an den `TokenTextSplitter` übergeben.
**Soll:** Der Overlap-Parameter muss korrekt an den Splitter übergeben werden.

**Begründung:** Ohne Overlap gehen Informationen an Chunk-Grenzen verloren. Zusammenhängende Absätze werden hart getrennt.

**Akzeptanzkriterium:** Aufeinanderfolgende Chunks überlappen um die konfigurierte Tokenanzahl.

### REQ-03: Konfigurierbarer Top-K und Similarity Threshold (HOCH)

**Ist:** `topK=5` und `similarityThreshold=0.5` sind hardcoded.
**Soll:** Beide Parameter müssen über `application.yml` / Environment-Variablen konfigurierbar sein.

**Empfohlene Defaults:**
- `topK`: 10 (erhöht von 5)
- `similarityThreshold`: 0.65 (erhöht von 0.5)

**Begründung:** Mehr Kandidaten-Chunks erhöhen die Trefferwahrscheinlichkeit. Ein höherer Threshold filtert schwach relevante Chunks heraus, die den Kontext verwässern.

**Akzeptanzkriterium:** Werte sind über `QUERY_TOP_K` und `QUERY_SIMILARITY_THRESHOLD` ENV-Variablen konfigurierbar.

### REQ-04: Verbesserter System Prompt (MITTEL)

**Ist:** Einfacher Prompt ohne Disambiguation-Instruktionen.
**Soll:** System Prompt muss ergänzt werden um:

- Instruktion, den Seitentitel als primäres Zuordnungskriterium zu verwenden
- Instruktion, bei mehrdeutigen Kontexten den Titel/Pfad zur Disambiguierung heranzuziehen
- Instruktion, zwischen verschiedenen Dokumenttypen zu unterscheiden (Spec, Issue, Protokoll, etc.)
- Instruktion, den Chunk-Typ (PAGE, COMMENT, ATTACHMENT) zu berücksichtigen

**Akzeptanzkriterium:** Das LLM unterscheidet korrekt zwischen "Spec 07" und "Issue 07" wenn beide im Kontext vorkommen.

### REQ-05: Kontext-Aufbau mit Relevanz-Signalen (MITTEL)

**Ist:** Chunks werden ohne Unterscheidung konkateniert.
**Soll:** Der Kontext-Aufbau soll differenzieren:

- Chunk-Typ anzeigen (Seite, Kommentar, Anhang)
- Vollständigen Seitentitel und Pfad im Kontext-Header zeigen

**Format-Beispiel:**
```
--- Quelle: Spec 07 — Authentifizierung (Space: OP, Pfad: Projektdoku > Specs, Typ: Seite) ---
[chunkText]
```

**Akzeptanzkriterium:** Das LLM kann anhand des Kontext-Headers die richtige Quelle zuordnen.

---

## 3. Nicht-funktionale Requirements

### REQ-NF-01: Re-Ingest erforderlich

Nach Änderungen an der Chunk-Anreicherung (REQ-01) müssen alle Dokumente neu ingested werden, da sich der Embedding-Text ändert.

### REQ-NF-02: Rückwärtskompatibilität

Die API-Schnittstellen (`/api/chat`, `/api/chat/stream`, `/api/spaces`) bleiben unverändert. Das Frontend muss nicht angepasst werden.

### REQ-NF-03: Testbarkeit

Bestehende Unit-Tests (ChunkingServiceTest, QueryServiceTest falls vorhanden) müssen erweitert werden, um die neuen Chunk-Formate zu verifizieren.

---

## 4. Abgrenzung (Out of Scope)

Die folgenden Maßnahmen werden in dieser Iteration **nicht** umgesetzt:

| Thema | Begründung |
|-------|------------|
| Cross-Encoder Re-Ranking | Erfordert zusätzliches Modell, hoher Aufwand |
| Hybrid Search (Keyword + Vektor) | Spring AI Qdrant-Integration unterstützt dies nicht nativ |
| Modell-Upgrade (größeres LLM) | Infrastruktur-Entscheidung, unabhängig von Code-Änderungen |
| Query Expansion / HyDE | Komplexität, erst nach Basis-Optimierung evaluieren |

---

## 5. Priorisierung

| Priorität | Requirement | Erwarteter Impact |
|-----------|-------------|-------------------|
| 1 | REQ-01: Chunk-Anreicherung | Höchster Impact — löst das Kernproblem |
| 2 | REQ-02: Chunk-Overlap Fix | Bug-Fix, verbessert Chunk-Qualität |
| 3 | REQ-03: Konfigurierbarer Top-K/Threshold | Ermöglicht Tuning ohne Code-Änderungen |
| 4 | REQ-04: Verbesserter System Prompt | Hilft dem LLM bei Disambiguation |
| 5 | REQ-05: Kontext-Aufbau | Bessere Kontext-Darstellung für LLM |
