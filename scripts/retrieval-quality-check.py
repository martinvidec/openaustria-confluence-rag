#!/usr/bin/env python3
"""
Retrieval-Quality Check

Runs a configurable set of test queries against the ingested Qdrant
collection and prints per-query scores + top matches. Useful for:

  - Comparing embedding models (e.g. nomic-embed-text vs bge-m3)
  - Evaluating threshold settings
  - Spot-checking retrieval quality after ingest changes
  - Measuring the effect of reranker on/off (run app, compare output)

Prerequisites:
  - Ollama running on OLLAMA_URL with the embedding model loaded
  - Qdrant running on QDRANT_URL with the ingested collection
  - App has been ingested at least once

Usage:
  # With defaults (localhost, bge-m3, confluence-chunks collection)
  python3 scripts/retrieval-quality-check.py

  # Override model / URLs / collection
  EMBED_MODEL=nomic-embed-text python3 scripts/retrieval-quality-check.py
  OLLAMA_URL=http://localhost:11434 QDRANT_URL=http://localhost:6333 \
    COLLECTION=confluence-chunks python3 scripts/retrieval-quality-check.py

  # Ad-hoc single query
  python3 scripts/retrieval-quality-check.py "Wie funktioniert X?"

Edit the QUERIES list below to match your own content.
"""

import json
import os
import sys
import urllib.request

OLLAMA_URL = os.getenv("OLLAMA_URL", "http://localhost:11434")
QDRANT_URL = os.getenv("QDRANT_URL", "http://localhost:6333")
COLLECTION = os.getenv("COLLECTION", "confluence-chunks")
EMBED_MODEL = os.getenv("EMBED_MODEL", "bge-m3")
TOP_K = int(os.getenv("TOP_K", "10"))

# (query_text, expected_top_match_hint)
# Edit these to match your own corpus. The hint is just for human reading.
QUERIES = [
    ("Wie funktioniert der PlantUML Konverter?", "expect: Spec 03 / Issue #5"),
    ("Spec 07", "expect: Spec 07 RAG Query Service"),
    ("Wie wird die Qdrant Collection konfiguriert?", "expect: ADR-002 / Issue #1 / #2"),
    ("Backup und Recovery", "expect: Betriebshandbuch — Backup"),
    ("Welche Endpunkte hat die Admin API?", "expect: API-Dokumentation — Admin API"),
    ("Was ist CORS?", "expect: Issue #12 CORS"),
    ("Wie backe ich einen Apfelkuchen?", "expect: nothing relevant (off-topic sanity check)"),
]


def embed(text: str) -> list:
    req = urllib.request.Request(
        f"{OLLAMA_URL}/api/embed",
        data=json.dumps({"model": EMBED_MODEL, "input": text}).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read())["embeddings"][0]


def search(vector: list, top: int) -> list:
    req = urllib.request.Request(
        f"{QDRANT_URL}/collections/{COLLECTION}/points/search",
        data=json.dumps({"vector": vector, "limit": top, "with_payload": True}).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read())["result"]


def run(query: str, expect: str = "") -> None:
    print(f"\n=== Query: {query!r}")
    if expect:
        print(f"    ({expect})")
    vector = embed(query)
    hits = search(vector, TOP_K)
    if not hits:
        print("   (no hits)")
        return
    scores = [h["score"] for h in hits]
    spread = scores[0] - scores[-1]
    print(f"   top1={scores[0]:.4f}  top{TOP_K}={scores[-1]:.4f}  spread={spread:.4f}")
    for i, h in enumerate(hits):
        payload = h.get("payload", {})
        title = payload.get("pageTitle", "?")
        space = payload.get("spaceKey", "?")
        print(f"   {i+1:2}. {h['score']:.4f}  [{space}] {title[:80]}")


def main() -> None:
    print(f"# retrieval-quality-check")
    print(f"#   ollama:     {OLLAMA_URL}")
    print(f"#   qdrant:     {QDRANT_URL}/{COLLECTION}")
    print(f"#   embed:      {EMBED_MODEL}")
    print(f"#   top_k:      {TOP_K}")

    if len(sys.argv) > 1:
        # Ad-hoc single query from CLI
        run(" ".join(sys.argv[1:]))
        return

    for query, expect in QUERIES:
        run(query, expect)


if __name__ == "__main__":
    main()
