#!/usr/bin/env python3
"""
Retrieval-Quality Check

Runs a configurable set of test queries against the ingested Qdrant
collection and prints per-query top matches with scores. Supports three
modes via the MODE env var:

  - vector  : raw vector search only (the "before reranker" baseline)
  - rerank  : top-K after LLM listwise rerank (the "after reranker" view)
  - both    : side-by-side comparison with rank-change indicators (default)

The rerank logic mirrors the Java LlmListwiseReranker — same prompt
shape and same JSON-array parser. Keep them in sync if you change the
production code.

Useful for:
  - Comparing embedding models (e.g. nomic-embed-text vs bge-m3)
  - Evaluating threshold settings
  - Spot-checking retrieval quality after ingest changes
  - Measuring before/after of the LLM reranker without restarting the app
  - Trying different reranker models (qwen3:0.6b vs gemma3:4b vs ...)

Prerequisites:
  - Ollama running on OLLAMA_URL with the embedding model loaded
  - Qdrant running on QDRANT_URL with the ingested collection
  - For MODE=rerank or MODE=both: also the rerank LLM (default qwen3:0.6b)
  - App has been ingested at least once

Usage:
  # Defaults: bge-m3 + qwen3:0.6b reranker, both modes side-by-side
  python3 scripts/retrieval-quality-check.py

  # Vector-only baseline (skip rerank)
  MODE=vector python3 scripts/retrieval-quality-check.py

  # After-rerank only
  MODE=rerank python3 scripts/retrieval-quality-check.py

  # Different rerank model
  RERANK_MODEL=gemma3:4b python3 scripts/retrieval-quality-check.py

  # Different embedding model (compare against the old nomic-embed-text)
  EMBED_MODEL=nomic-embed-text python3 scripts/retrieval-quality-check.py

  # Ad-hoc single query
  python3 scripts/retrieval-quality-check.py "Wie funktioniert X?"

Edit the QUERIES list below to match your own content.
"""

import json
import os
import re
import sys
import urllib.request

OLLAMA_URL = os.getenv("OLLAMA_URL", "http://localhost:11434")
QDRANT_URL = os.getenv("QDRANT_URL", "http://localhost:6333")
COLLECTION = os.getenv("COLLECTION", "confluence-chunks")
EMBED_MODEL = os.getenv("EMBED_MODEL", "bge-m3")
TOP_K = int(os.getenv("TOP_K", "5"))
MODE = os.getenv("MODE", "both")  # vector | rerank | both

# Reranker config (mirrors LlmListwiseReranker defaults)
RERANK_MODEL = os.getenv("RERANK_MODEL", "qwen3:0.6b")
CANDIDATE_COUNT = int(os.getenv("CANDIDATE_COUNT", "15"))
MAX_CHUNK_CHARS = int(os.getenv("MAX_CHUNK_CHARS", "500"))
RERANK_TIMEOUT = int(os.getenv("RERANK_TIMEOUT", "60"))
# num_predict needs to be high enough for "thinking" models like qwen3 to
# emit their internal <think> block AND still produce the JSON array.
NUM_PREDICT = int(os.getenv("NUM_PREDICT", "1024"))

# Pattern to extract first JSON array of integers from any text — same
# regex used in LlmListwiseReranker.parseJsonArray.
JSON_ARRAY_PATTERN = re.compile(r"\[\s*\d+(?:\s*,\s*\d+)*\s*]")

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


# ---------- HTTP helpers ----------


def _post_json(url: str, payload: dict, timeout: int = 30) -> dict:
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def embed(text: str) -> list:
    return _post_json(
        f"{OLLAMA_URL}/api/embed",
        {"model": EMBED_MODEL, "input": text},
    )["embeddings"][0]


def search(vector: list, top: int) -> list:
    return _post_json(
        f"{QDRANT_URL}/collections/{COLLECTION}/points/search",
        {"vector": vector, "limit": top, "with_payload": True},
    )["result"]


# ---------- Reranker (mirrors LlmListwiseReranker) ----------


def llm_listwise_rerank(query: str, candidates: list, top_k: int) -> list:
    """
    Replicate the Java LlmListwiseReranker logic via direct Ollama API call.
    Returns a list of candidate indices in new order, padded with original
    order if the LLM returned fewer than top_k valid indices. Falls back
    to original order on any error.
    """
    if not candidates:
        return []
    if len(candidates) <= top_k:
        return list(range(len(candidates)))

    try:
        prompt = _build_rerank_prompt(query, candidates, top_k)
        payload = {
            "model": RERANK_MODEL,
            "prompt": prompt,
            "stream": False,
            "options": {"temperature": 0, "top_p": 0.1, "num_predict": NUM_PREDICT},
        }
        resp = _post_json(f"{OLLAMA_URL}/api/generate", payload, timeout=RERANK_TIMEOUT)
        response_text = resp.get("response", "")
        return _parse_and_pad(response_text, len(candidates), top_k)
    except Exception as e:
        print(f"   ! Rerank fallback (Vektor-Reihenfolge): {e}", file=sys.stderr)
        return list(range(top_k))


def _build_rerank_prompt(query: str, candidates: list, top_k: int) -> str:
    lines = [
        "Du bist ein Such-Assistent. Bewerte die Relevanz folgender Dokument-Auszüge",
        "für die Frage.",
        "",
        f'Frage: "{query}"',
        "",
        "Dokumente:",
    ]
    n = min(len(candidates), CANDIDATE_COUNT)
    for i in range(n):
        c = candidates[i]
        payload = c.get("payload", {})
        title = payload.get("pageTitle", "")
        text = payload.get("doc_content", "") or ""
        truncated = text[:MAX_CHUNK_CHARS] + ("..." if len(text) > MAX_CHUNK_CHARS else "")
        lines.append(f"[{i + 1}] Titel: {title}")
        lines.append(truncated)
        lines.append("")
    lines.extend(
        [
            f"Gib die Nummern der {top_k} relevantesten Dokumente in absteigender Relevanz",
            "als JSON-Array zurück. Beispiel: [3, 7, 1, 12, 5]",
            "",
            "Antworte AUSSCHLIESSLICH mit dem Array. Keine Erklärung, kein Markdown,",
            "keine Anführungszeichen drumherum. Nur das Array.",
        ]
    )
    return "\n".join(lines)


def _parse_and_pad(text: str, n_candidates: int, top_k: int) -> list:
    """Parse first JSON array from text, dedupe, validate, pad with original order."""
    indices: list = []
    seen: set = set()
    if text:
        m = JSON_ARRAY_PATTERN.search(text)
        if m:
            inner = m.group()[1:-1]
            for token in inner.split(","):
                token = token.strip()
                if not token:
                    continue
                try:
                    idx = int(token) - 1  # convert from 1-based
                except ValueError:
                    continue
                if 0 <= idx < n_candidates and idx not in seen:
                    seen.add(idx)
                    indices.append(idx)
                if len(indices) >= top_k:
                    break
    # Pad with original-order candidates not yet used
    if len(indices) < top_k:
        for i in range(n_candidates):
            if i not in seen and len(indices) < top_k:
                indices.append(i)
                seen.add(i)
    return indices[:top_k]


# ---------- Output ----------


def _format_title(payload: dict) -> str:
    title = payload.get("pageTitle", "?")
    space = payload.get("spaceKey", "?")
    return f"[{space}] {title[:70]}"


def _print_vector_only(candidates: list, top_k: int) -> None:
    shown = candidates[:top_k]
    if not shown:
        print("   (no hits)")
        return
    scores = [h["score"] for h in shown]
    spread = scores[0] - scores[-1] if len(scores) > 1 else 0.0
    print(f"   top1={scores[0]:.4f}  top{len(shown)}={scores[-1]:.4f}  spread={spread:.4f}")
    for i, h in enumerate(shown):
        print(f"   {i + 1:2}. {h['score']:.4f}  {_format_title(h.get('payload', {}))}")


def _print_rerank_only(candidates: list, indices: list) -> None:
    if not indices:
        print("   (no hits)")
        return
    for new_pos, idx in enumerate(indices):
        h = candidates[idx]
        print(f"   {new_pos + 1:2}. {h['score']:.4f}  {_format_title(h.get('payload', {}))}")


def _print_compare(candidates: list, indices: list, top_k: int) -> None:
    """Side-by-side: vector top-K and reranked top-K with rank-change arrows."""
    if not candidates:
        print("   (no hits)")
        return

    print("\n   -- Vector-only --")
    _print_vector_only(candidates, top_k)

    print("\n   -- After LLM Listwise Rerank --")
    if not indices:
        print("   (no hits)")
        return
    for new_pos, idx in enumerate(indices):
        h = candidates[idx]
        old_pos = idx  # in vector order, position equals index
        if old_pos < top_k:
            if old_pos > new_pos:
                arrow = f"↑ from #{old_pos + 1}"
            elif old_pos < new_pos:
                arrow = f"↓ from #{old_pos + 1}"
            else:
                arrow = "= unchanged"
        else:
            arrow = f"★ promoted (was #{old_pos + 1})"
        title = _format_title(h.get("payload", {}))
        print(f"   {new_pos + 1:2}. {h['score']:.4f}  {arrow:18s}  {title}")


def run(query: str, expect: str = "") -> None:
    print(f"\n=== Query: {query!r}")
    if expect:
        print(f"    ({expect})")

    fetch_k = max(TOP_K, CANDIDATE_COUNT) if MODE in ("rerank", "both") else TOP_K
    vector = embed(query)
    candidates = search(vector, fetch_k)

    if MODE == "vector":
        _print_vector_only(candidates, TOP_K)
        return

    indices = llm_listwise_rerank(query, candidates, TOP_K)

    if MODE == "rerank":
        _print_rerank_only(candidates, indices)
        return

    # default: both
    _print_compare(candidates, indices, TOP_K)


def main() -> None:
    print("# retrieval-quality-check")
    print(f"#   ollama:          {OLLAMA_URL}")
    print(f"#   qdrant:          {QDRANT_URL}/{COLLECTION}")
    print(f"#   embed:           {EMBED_MODEL}")
    print(f"#   top_k:           {TOP_K}")
    print(f"#   mode:            {MODE}")
    if MODE in ("rerank", "both"):
        print(f"#   rerank_model:    {RERANK_MODEL}")
        print(f"#   candidate_count: {CANDIDATE_COUNT}")

    if len(sys.argv) > 1:
        run(" ".join(sys.argv[1:]))
        return

    for query, expect in QUERIES:
        run(query, expect)


if __name__ == "__main__":
    main()
