# ADR 0003: Vector store: PostgreSQL 16 + pgvector

- Status: Accepted
- Date: 2026-10-06

## Context
RAG needs similarity search filtered by who may see each document.

## Decision
Store relational data and embeddings in one PostgreSQL database with the pgvector extension and an HNSW index.

## Consequences
One system to run and back up; access filters run inside the same SQL query; deleting a document removes its vectors transactionally. Free locally (Docker) and hosted (Neon free plan).

## Alternatives considered
Qdrant, Pinecone, Weaviate: better at very large scale, but an extra system with its own consistency and auth story.
