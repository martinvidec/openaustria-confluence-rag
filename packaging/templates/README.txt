Confluence RAG — Quick Reference
=================================

First-time setup:
  confluence-rag init        Interactive setup, writes config/config.env
                             and optionally pulls required Ollama models.
  confluence-rag doctor      Verifies Java, Ollama, Qdrant, models.

Running the app:
  confluence-rag start       Starts in the background, writes PID file
                             and logs/confluence-rag.log
  confluence-rag stop        Graceful shutdown (TERM, then KILL after 30s)
  confluence-rag status      Is the app running?
  confluence-rag logs        Tail the log file
  confluence-rag logs --no-follow --tail 200

Operation:
  confluence-rag ingest      Trigger a full re-ingest via /api/admin/ingest

Config & maintenance:
  confluence-rag config      Print path to config.env
  confluence-rag config edit Open config.env in your default editor
  confluence-rag version     Show installed version
  confluence-rag update      Download and install the latest release
  confluence-rag uninstall   Remove the installation (with confirmation)
  confluence-rag help        List all subcommands

Prerequisites (not installed by this package):
  - Java 17+     https://adoptium.net/
  - Ollama       https://ollama.com/download
  - Qdrant       https://qdrant.tech/documentation/quick-start/

After install the Web UI runs on http://localhost:8080

Files:
  config/config.env          Your configuration (contains secrets, perms 600)
  lib/confluence-rag.jar     Fat JAR
  data/sync-state.json       Incremental-sync bookkeeping
  logs/confluence-rag.log    App logs
  confluence-rag.pid         PID of the running process (if any)

More information:
  https://github.com/martinvidec/openaustria-confluence-rag
