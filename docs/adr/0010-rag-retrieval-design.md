# ADR 0010: RAG as a tool, with hybrid retrieval and verified citations

- Status: Accepted
- Date: 2026-10-16

## Context
Policy questions must be answered from the company's own documents, with sources an employee can check. The answers come from a small local model (Qwen 2.5 7B), so we can't rely on the model to be careful.

## Decision
- **Retrieval is a tool** (`searchPolicies`), not a step run on every message. The model searches only when a question needs policy knowledge, and it can combine a policy with the employee's own records.
- **Retrieval is hybrid.** It combines pgvector cosine search with Postgres full-text search, merged with Reciprocal Rank Fusion. Relevance floors (a minimum vector similarity, and a minimum share of the question's words for keyword matches) let unrelated questions return nothing.
- **Chunking is by section.** Each `##` section becomes one chunk, and long sections are split on paragraphs with overlap. Each chunk is embedded with its document title and heading.
- **Citations are verified on the server.** Passages are numbered per answer, and only numbers that actually appear in the answer become `citations`. Each citation points to a section the app can open.
- **Embeddings come from `nomic-embed-text` via Ollama.** An offline hashing embedder covers tests, CI and the fake mode. The model name is stored with the vectors, and vectors from different models are never compared.
- **Retrieval quality is measured.** An evaluation set with off-topic questions reports hit@k, MRR and the off-topic rejection rate.

## Consequences
- No extra infrastructure: documents, vectors and keyword indexes all live in the existing Postgres.
- Quality can be compared whenever chunking, models or thresholds change.
- The similarity floor depends on the model and has to be tuned once per embedding model.
- A model that forgets to cite produces an answer without sources. We show that honestly rather than guessing which passages it used.

## Alternatives considered
- A dedicated vector database (Pinecone, Qdrant): more moving parts for a few hundred chunks.
- Retrieve on every message: wastes latency on personal-data and small-talk questions, and puts irrelevant text into the prompt.
- Vector search only: misses exact terms such as "VPN" and specific numbers.
- Trusting whatever sources the model lists: it can name sources it didn't use.
- LangChain retrievers (see ADR 0008).
