# Spec: Einfache Installation für Bare-Metal

**Datum:** 2026-04-11
**Status:** Spec

**Betroffene Dateien:**
- `.github/workflows/release.yml` (**neu**)
- `packaging/bin/confluence-rag` (**neu**, Shell-Wrapper)
- `packaging/bin/confluence-rag.cmd` (**neu**, Windows-Batch-Wrapper)
- `packaging/bin/confluence-rag.ps1` (**neu**, PowerShell-Implementierung)
- `packaging/assemble.sh` (**neu**, lokale + CI Archiv-Erstellung)
- `packaging/templates/config.env.example` (**neu**, Config-Template)
- `packaging/templates/README.txt` (**neu**, Archive-internes README)
- `install.sh` (**neu**, curl-pipe Installer macOS/Linux)
- `install.ps1` (**neu**, irm Installer Windows)
- `README.md` (erweitert mit neuer "Quick Install"-Sektion ganz oben)

---

## 1. Ziel

Installation der App auf Bare-Metal-Systemen mit einem einzigen Befehl, ohne das Repo zu klonen, vergleichbar mit dem Ollama-Installer. Nach der Installation verwaltet der Nutzer die App über eine `confluence-rag`-CLI mit Subcommands.

**User Journey:**

```bash
# macOS / Linux
curl -fsSL https://raw.githubusercontent.com/martinvidec/openaustria-confluence-rag/main/install.sh | sh

# Windows (PowerShell)
irm https://raw.githubusercontent.com/martinvidec/openaustria-confluence-rag/main/install.ps1 | iex

# Dann:
confluence-rag init         # interaktives Setup, pullt Modelle
confluence-rag doctor       # prüft Java, Ollama, Qdrant, Modelle
confluence-rag start        # im Hintergrund starten
confluence-rag ingest       # Voll-Ingest
# http://localhost:8080 im Browser
confluence-rag stop
```

---

## 2. Architektur

### 2.1 Release-Workflow

Bei Git-Tag `v*` baut GitHub Actions das Fat-JAR einmal und packt daraus **zwei Distribution-Archive**:

- `confluence-rag-<version>-unix.tar.gz` — für macOS (arm64/x86_64) und Linux (x86_64/arm64). Das Fat-JAR ist plattformunabhängig und auch alle nativen Libraries (Tika, Jsoup) sind in den jeweiligen Java-Runtimes abgedeckt.
- `confluence-rag-<version>-windows.zip` — für Windows x86_64

Beide Archive werden als GitHub Release Assets hochgeladen und von den Install-Scripts per API entdeckt.

### 2.2 Install-Scripts

Zwei minimale Bootstrap-Scripts im Repo-Root, die per `curl | sh` bzw. `irm | iex` ausgeführt werden:

- `install.sh` — detected OS + Arch, fragt die GitHub Releases API ab, lädt das Unix-Archiv, entpackt nach `~/.confluence-rag/`, legt Symlink `~/.local/bin/confluence-rag` an
- `install.ps1` — detected Arch, lädt Windows-Archiv, entpackt nach `%LOCALAPPDATA%\confluence-rag\`, fügt `bin`-Ordner zum User-PATH hinzu

Die Scripts prüfen **nicht** ob Java/Ollama/Qdrant installiert sind — sie installieren nur die App. Der `doctor`-Befehl prüft später.

### 2.3 Directory-Layout nach Installation

**macOS / Linux:**
```
~/.confluence-rag/
├── bin/
│   └── confluence-rag             # Shell-Wrapper (ausführbar)
├── lib/
│   └── confluence-rag.jar         # Fat JAR
├── config/
│   ├── config.env                 # Nutzerkonfiguration (nach init)
│   └── config.env.example         # Template
├── data/
│   └── sync-state.json            # Sync-State (CQL Delta)
├── logs/
│   └── confluence-rag.log         # App-Logs (stdout + stderr)
├── confluence-rag.pid             # PID vom laufenden Prozess
└── README.txt                     # Kurzanleitung

~/.local/bin/confluence-rag        # Symlink → ~/.confluence-rag/bin/confluence-rag
```

**Windows:**
```
%LOCALAPPDATA%\confluence-rag\
├── bin\
│   ├── confluence-rag.cmd         # Batch-Wrapper (im PATH)
│   └── confluence-rag.ps1         # PowerShell-Implementierung
├── lib\
│   └── confluence-rag.jar
├── config\
│   ├── config.env
│   └── config.env.example
├── data\
├── logs\
├── confluence-rag.pid
└── README.txt

User-PATH enthält: %LOCALAPPDATA%\confluence-rag\bin
```

---

## 3. CLI-Subcommands

Alle Subcommands funktionieren gleich auf allen drei Plattformen. Die Shell- und PowerShell-Implementierungen verhalten sich identisch.

| Subcommand | Zweck |
|---|---|
| `init` | Interaktives Erst-Setup: fragt Confluence-URL, Auth, Spaces, optional Modelle pullen. Schreibt `config/config.env`. |
| `doctor` | Prüft Java ≥17, Ollama erreichbar, Qdrant erreichbar, benötigte Ollama-Modelle verfügbar. Gibt klare Install-Hinweise bei Fehlern. |
| `start` | Startet die App im Hintergrund mit den Env-Vars aus `config/config.env`. Schreibt PID nach `confluence-rag.pid`, leitet Output nach `logs/confluence-rag.log`. Exit-Code 0 wenn Start erfolgreich (HTTP 200 auf `/api/spaces` erreichbar). |
| `stop` | Liest PID, sendet TERM, wartet bis zu 30s auf Beendigung, dann KILL. Räumt PID-File auf. |
| `status` | Zeigt ob App läuft (PID vorhanden + Prozess lebt + HTTP-Check). |
| `logs` | Zeigt die letzten 50 Zeilen vom Log-File und folgt (`tail -f` Verhalten). Mit `--tail N` und `--no-follow` Varianten. |
| `config` | Ohne Args: gibt den Pfad zur config.env aus. Mit `edit`: öffnet im `$EDITOR` / notepad. |
| `ingest` | `curl -XPOST http://localhost:8080/api/admin/ingest`. |
| `update` | Fragt GitHub Releases API, vergleicht mit installierter Version, lädt neuestes Archive, atomares Replace der `lib/` und `bin/` Dateien. Config und Data unberührt. |
| `uninstall` | Stoppt laufende App, entfernt `~/.confluence-rag/` und Symlink bzw. PATH-Eintrag. Interaktive Bestätigung. |
| `version` | Gibt installierte Version aus. |
| `help` | Listet Subcommands mit Kurzbeschreibung. |

### 3.1 `init` Flow

```
$ confluence-rag init
Confluence Base URL [http://localhost:8090]:
Auth method (pat|basic) [pat]:
Personal Access Token:
Spaces (comma-separated):
Chat model [gemma3:4b]:
Embedding model [bge-m3]:
Reranker (llm|none) [llm]:
Reranker model [qwen3:0.6b]:

Configuration written to ~/.confluence-rag/config/config.env

Pull required Ollama models now? (bge-m3, gemma3:4b, qwen3:0.6b) [Y/n]: Y
→ ollama pull bge-m3 ...
→ ollama pull gemma3:4b ...
→ ollama pull qwen3:0.6b ...

Setup complete. Next steps:
  confluence-rag doctor   # verify prerequisites
  confluence-rag start    # start the app
  confluence-rag ingest   # trigger initial ingest
```

Das Pullen der Modelle ist mit `--no-pull` abschaltbar.

### 3.2 `doctor` Checks

```
$ confluence-rag doctor
Java 17+ ......................... ✓  (Java 17.0.18)
Ollama reachable ................. ✓  (http://localhost:11434)
  bge-m3 available ............... ✓
  gemma3:4b available ............ ✓
  qwen3:0.6b available ........... ✓
Qdrant reachable ................. ✓  (http://localhost:6333)
Config present ................... ✓  (~/.confluence-rag/config/config.env)

All checks passed.
```

Bei Fehlern: rote Kreuze und per-Check Install-Hinweise, z.B.:
```
Ollama reachable ................. ✗
  ↳ Ollama nicht erreichbar unter http://localhost:11434
    Install: https://ollama.com/download
    Start:   ollama serve
```

### 3.3 Config-Datei Format

`config/config.env` ist ein einfaches Shell-Source-fähiges Format (auch von PowerShell geparst):

```shell
# Confluence
CONFLUENCE_BASE_URL=http://localhost:8090
CONFLUENCE_PAT=
CONFLUENCE_USERNAME=
CONFLUENCE_PASSWORD=
CONFLUENCE_SPACES=DEV,OPS

# LLM & Embedding
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_CHAT_MODEL=gemma3:4b
OLLAMA_EMBEDDING_MODEL=bge-m3
VECTOR_DIMENSION=1024

# Reranker
QUERY_RERANKER_TYPE=llm
QUERY_RERANKER_LLM_MODEL=qwen3:0.6b

# Qdrant
QDRANT_HOST=localhost
QDRANT_GRPC_PORT=6334

# Data location (wird vom Wrapper gesetzt — nicht editieren)
SYNC_STATE_FILE=${HOME}/.confluence-rag/data/sync-state.json
```

### 3.4 Start-Strategie

**Unix:**
```bash
nohup java -jar "$INSTALL_DIR/lib/confluence-rag.jar" \
    >"$LOG_DIR/confluence-rag.log" 2>&1 &
echo $! > "$INSTALL_DIR/confluence-rag.pid"
```

Dann Polling bis `/api/spaces` HTTP 200 liefert (max 60s), dann OK zurückmelden.

**Windows (PowerShell):**
```powershell
$p = Start-Process -FilePath "java" -ArgumentList "-jar", "$InstallDir\lib\confluence-rag.jar" `
    -RedirectStandardOutput "$LogDir\confluence-rag.log" `
    -RedirectStandardError "$LogDir\confluence-rag.err.log" `
    -WindowStyle Hidden -PassThru
$p.Id | Out-File "$InstallDir\confluence-rag.pid"
```

---

## 4. GitHub Actions Release-Workflow

`.github/workflows/release.yml` — wird nur auf Git-Tag `v*` getriggert:

```yaml
name: Release

on:
  push:
    tags:
      - 'v*'

permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: maven
      - name: Build Fat JAR
        run: mvn -B package -DskipTests
      - name: Assemble distribution archives
        run: ./packaging/assemble.sh "${GITHUB_REF_NAME}"
      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          files: |
            dist/confluence-rag-*-unix.tar.gz
            dist/confluence-rag-*-windows.zip
          generate_release_notes: true
```

`packaging/assemble.sh` nimmt einen optionalen Version-Argument (default: aus `pom.xml` extrahiert), packt das Fat-JAR aus `target/` zusammen mit den Wrapper-Scripts und Templates in zwei Archive.

---

## 5. Release-Prozess (Maintainer)

```bash
# Version im pom.xml setzen (z.B. 0.1.0 ohne SNAPSHOT)
mvn versions:set -DnewVersion=0.1.0
git commit -am "release: v0.1.0"

# Tag + push triggert CI-Release
git tag v0.1.0
git push origin main v0.1.0

# GitHub Actions baut automatisch die Archive + Release-Notes
# Die Install-Scripts finden das Release automatisch via API
```

---

## 6. Akzeptanzkriterien

- [ ] `./packaging/assemble.sh` erzeugt lokal die beiden Archive in `dist/`
- [ ] `install.sh` läuft auf macOS (arm64 + x86_64) und Linux ohne Fehler durch (manuell testbar via `cat install.sh | INSTALL_PREFIX=/tmp/test sh`)
- [ ] `install.ps1` läuft auf Windows (manuell testbar — nicht in CI)
- [ ] `confluence-rag init` erstellt eine valide `config.env`
- [ ] `confluence-rag doctor` zeigt alle Checks korrekt an
- [ ] `confluence-rag start/stop/status` funktionieren mit PID-Management
- [ ] `confluence-rag logs` zeigt die Log-Datei
- [ ] `confluence-rag ingest` triggert den Voll-Ingest
- [ ] `confluence-rag uninstall` entfernt die Installation sauber
- [ ] `.github/workflows/release.yml` ist syntaktisch valide (GitHub Actions Linter)
- [ ] README.md hat eine "Quick Install"-Sektion ganz oben vor der bestehenden manuellen Installation
- [ ] Alle bestehenden Tests (67) bleiben grün
- [ ] Keine neuen Maven-Dependencies

---

## 7. Test-Plan

### 7.1 Lokal testbar (ohne Release)

1. `./packaging/assemble.sh dev` → prüft dass 2 Archive in `dist/` landen
2. Archive entpacken → Layout prüfen
3. `bin/confluence-rag help` → Help-Output
4. `bin/confluence-rag init` mit Defaults → config.env prüfen
5. `bin/confluence-rag doctor` gegen laufenden Stack
6. `bin/confluence-rag start` → App läuft, PID-File vorhanden
7. `bin/confluence-rag status` → "running"
8. `curl http://localhost:8080/api/spaces` → 200
9. `bin/confluence-rag stop` → Prozess weg, PID-File weg
10. `bin/confluence-rag uninstall` → Verzeichnisse weg (im Test-Setup)

### 7.2 Erst nach Release testbar

1. Git tag `v0.1.0-test` → Workflow läuft durch
2. Release-Assets vorhanden
3. Install-Script im sauberen VM/Container:
   - Ubuntu: fresh container, `curl ... | sh`, `confluence-rag help`
   - macOS: native, `curl ... | sh`
   - Windows: VM mit PowerShell, `irm ... | iex`

Die Post-Release-Tests sind manuell und werden bei der ersten echten Version durchgeführt.

---

## 8. Sicherheit & Datenschutz

- `config.env` enthält PAT/Passwort → File-Permissions `600` auf Unix, ACL-Beschränkung auf Windows
- `install.sh` und `install.ps1` werden per curl-pipe ausgeführt → User muss dem Repo vertrauen (klassisches Modell wie bei Ollama/rustup). Scripts sind im Repo les- und review-bar.
- Keine Telemetrie, kein Phone-Home, kein externer Service-Call außerhalb GitHub Releases API
- Das `update`-Command bestätigt den Hash des heruntergeladenen Archivs gegen den in der GitHub Release API gelisteten SHA256 wenn verfügbar (nicht erforderlich für MVP, nice-to-have)

---

## 9. Nicht im Scope (Follow-ups)

- **Homebrew Formula** — später, eigener PR
- **APT / RPM Pakete** — später
- **Windows MSI Installer** — später
- **systemd Service-Unit** — später, als optionaler `confluence-rag service install`
- **launchd plist** (macOS Auto-Start) — später
- **Code Signing** für Binaries — später
- **GraalVM Native Image** — bewusst nicht, siehe Spec-Diskussion oben
- **Installation einer bestimmten Version** (`install.sh --version v0.1.0`) — später; MVP nimmt immer das neueste Release
- **`confluence-rag backup`/`restore`** — später
- **Anbindung an einen privaten Maven/Artifact-Store** — separat

---

## 10. Risiken

| Risiko | Mitigation |
|---|---|
| PowerShell-Script funktioniert nur manuell testbar (ich habe kein Windows) | Sauberer Stil, minimale Plattform-spezifische Annahmen, kleine Funktionen, offensichtliche Syntax. Als Follow-up auf echter Windows-VM validieren. |
| curl-pipe-Install als Security-Smell | Klassisches Modell (Ollama, rustup, brew install.sh). Scripts werden im Repo sauber reviewbar sein. README erwähnt die Alternative "Script inspizieren, dann ausführen". |
| Java 17 nicht installiert | `doctor` prüft und gibt plattformspezifische Install-Hinweise. Install selbst schlägt nicht fehl. |
| Version-String-Mismatch zwischen `pom.xml` und Git-Tag | `assemble.sh` bevorzugt den Tag wenn übergeben, fällt auf pom-Version zurück. Maintainer bumpt `pom.xml` vor dem Tag (siehe §5). |
| GitHub Releases API rate-limit beim Install-Script | Verwendet anonymous API-Zugriff (60 requests/h/IP). Im Fehlerfall klare Fehlermeldung statt Abbruch. |
