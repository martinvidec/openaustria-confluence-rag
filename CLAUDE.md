# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This project is a **Confluence On-Premise content extraction and RAG (Retrieval-Augmented Generation) pipeline**. It extracts content from Confluence On-Premise instances via the REST API, processes it into embeddings, and stores them in a vector store for AI-powered question answering.

The project is currently in the **concept/pre-implementation phase**. The concept document is at `docs/Confluence_RAG_Konzept.md` (written in German).

## Planned Architecture

Three logical components, intended as a single Spring Boot application or separate services:

1. **Confluence Crawler** — Extracts content via Confluence REST API (`/rest/api/content`), converts XHTML storage format to plaintext using Jsoup, extracts PDF attachments via Apache Tika, supports incremental sync via CQL `lastModified` queries.

2. **Document Processing / Ingestion** — Splits text into 500–800 token chunks with 50–100 token overlap, generates embeddings (OpenAI or local via Ollama), upserts vectors + metadata (space, title, labels, URL) into vector store.

3. **RAG Query Service** — Performs similarity search with optional metadata filtering, generates LLM answers with source attribution, exposes REST API.

## Planned Tech Stack

- **Language:** Java 17+
- **Framework:** Spring Boot 3.x with Spring AI 1.0+ (alternative: LangChain4j)
- **HTTP Client:** JDK HttpClient (async)
- **HTML Parsing:** Jsoup
- **PDF Extraction:** Apache Tika
- **Vector Store:** pgvector (PostgreSQL) or Qdrant
- **Embedding:** OpenAI text-embedding-3-small or Ollama (nomic-embed-text, mxbai-embed-large)
- **LLM:** Claude, GPT-4o, or local via Ollama

## Key Design Decisions

- **REST API over DB access or XML export** — REST API (`/rest/api/content`) is the only approach that satisfies all requirements: version-independent (Confluence 5.5+ through Data Center 8.x), incremental, complete, and safe for production.
- **Java ecosystem preferred** — Team has existing Java expertise; the full stack (API client, HTML parsing, PDF extraction, RAG framework, vector store SDKs) is well covered in Java.
- **On-premise constraints** — Solution must work entirely on-premise; cloud LLM/embedding providers are optional depending on data privacy requirements.

## Language Note

The concept document and likely future documentation are in **German**. Code and technical artifacts should use English identifiers and comments.
