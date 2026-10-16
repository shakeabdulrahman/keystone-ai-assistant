"""Hybrid retrieval: vector search + keyword search, merged with Reciprocal Rank Fusion.

Why both:
- Vector search finds passages with the same *meaning* ("time off for a new baby"
  → parental leave), even with different words.
- Keyword search finds exact terms that embeddings blur: numbers, names, acronyms
  ("VPN", "probation").

Reciprocal Rank Fusion (RRF) combines the two ranked lists without having to compare
their incompatible scores: each passage scores Σ 1 / (k + rank) over the lists it
appears in. A passage near the top of both wins.

Relevance gates, applied before fusion, so a question with no relevant policy returns
nothing and the assistant says so instead of answering from loosely related text:
- vector matches below the embedder's similarity floor are dropped;
- keyword matches must contain most of the question's words (`MIN_TERM_COVERAGE`), so
  one shared word ("tell me a joke" vs "tell your manager") isn't a match.
"""

import uuid
from dataclasses import dataclass

from app.rag.embeddings import Embedder, tokenize
from app.rag.store import ChunkRecord, KnowledgeStore

RRF_K = 60  # the constant from the original RRF paper; dampens the effect of rank 1 vs 2
MIN_TERM_COVERAGE = 0.6


@dataclass(frozen=True)
class RetrievedChunk:
    chunk: ChunkRecord
    score: float  # fused RRF score
    vector_similarity: float | None  # None if found only by keyword search


class Retriever:
    def __init__(
        self,
        store: KnowledgeStore,
        embedder: Embedder,
        *,
        top_k: int = 4,
        candidates: int = 12,
        min_similarity: float | None = None,
    ) -> None:
        self._store = store
        self._embedder = embedder
        self._top_k = top_k
        self._candidates = candidates
        self._min_similarity = embedder.min_similarity if min_similarity is None else min_similarity

    async def search(self, query: str, top_k: int | None = None) -> list[RetrievedChunk]:
        limit = top_k or self._top_k
        embedding = await self._embedder.embed_query(query)
        vector_hits = [
            h
            for h in await self._store.vector_search(
                embedding, self._embedder.model, self._candidates
            )
            if h.score >= self._min_similarity
        ]
        terms = set(tokenize(query))
        text_hits = [
            h
            for h in await self._store.text_search(query, self._candidates)
            if _coverage(terms, h.chunk) >= MIN_TERM_COVERAGE
        ]

        fused: dict[uuid.UUID, float] = {}
        chunks: dict[uuid.UUID, ChunkRecord] = {}
        similarity: dict[uuid.UUID, float] = {}
        for rank, hit in enumerate(vector_hits, start=1):
            fused[hit.chunk.id] = fused.get(hit.chunk.id, 0.0) + 1 / (RRF_K + rank)
            chunks[hit.chunk.id] = hit.chunk
            similarity[hit.chunk.id] = hit.score
        for rank, hit in enumerate(text_hits, start=1):
            fused[hit.chunk.id] = fused.get(hit.chunk.id, 0.0) + 1 / (RRF_K + rank)
            chunks[hit.chunk.id] = hit.chunk

        ranked = sorted(fused.items(), key=lambda item: item[1], reverse=True)[:limit]
        return [
            RetrievedChunk(chunks[chunk_id], score, similarity.get(chunk_id))
            for chunk_id, score in ranked
        ]


def _coverage(terms: set[str], chunk: ChunkRecord) -> float:
    """Share of the query's words that appear in the passage (or its heading/title)."""
    if not terms:
        return 0.0
    words = set(tokenize(f"{chunk.document_title} {chunk.heading} {chunk.content}"))
    return len(terms & words) / len(terms)
