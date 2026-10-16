"""Knowledge storage port, plus an in-memory implementation for tests.

The Postgres implementation (`sql_store.py`) does the same with pgvector (cosine
distance, HNSW index) and Postgres full-text search (tsvector, GIN index).
"""

import uuid
from collections import Counter
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime
from typing import Protocol

from app.rag.documents import Chunk, SourceDocument
from app.rag.embeddings import cosine, tokenize


@dataclass(frozen=True)
class StoredDocument:
    id: str
    title: str
    version: str
    effective: date
    owner: str
    body: str
    content_hash: str
    embedding_model: str
    updated_at: datetime


@dataclass(frozen=True)
class ChunkRecord:
    id: uuid.UUID
    document_id: str
    document_title: str
    section_index: int
    chunk_index: int
    heading: str
    content: str


@dataclass(frozen=True)
class SearchHit:
    chunk: ChunkRecord
    score: float  # vector search: cosine similarity; text search: rank (higher is better)


@dataclass(frozen=True)
class EmbeddedChunk:
    chunk: Chunk
    embedding: list[float]


class KnowledgeStore(Protocol):
    async def fingerprints(self) -> dict[str, tuple[str, str]]:
        """document id → (content hash, embedding model) of what is stored."""
        ...

    async def replace_document(
        self, doc: SourceDocument, chunks: Sequence[EmbeddedChunk], embedding_model: str
    ) -> None:
        """Atomically store a document and its chunks, replacing any previous version."""
        ...

    async def delete_documents(self, ids: Sequence[str]) -> None: ...

    async def vector_search(
        self, embedding: Sequence[float], embedding_model: str, limit: int
    ) -> list[SearchHit]: ...

    async def text_search(self, query: str, limit: int) -> list[SearchHit]: ...

    async def get_document(self, document_id: str) -> StoredDocument | None: ...

    async def list_documents(self) -> list[StoredDocument]: ...


class InMemoryKnowledgeStore:
    def __init__(self) -> None:
        self._documents: dict[str, StoredDocument] = {}
        self._chunks: dict[str, list[tuple[ChunkRecord, list[float]]]] = {}

    async def fingerprints(self) -> dict[str, tuple[str, str]]:
        return {d.id: (d.content_hash, d.embedding_model) for d in self._documents.values()}

    async def replace_document(
        self, doc: SourceDocument, chunks: Sequence[EmbeddedChunk], embedding_model: str
    ) -> None:
        self._documents[doc.id] = StoredDocument(
            doc.id,
            doc.title,
            doc.version,
            doc.effective,
            doc.owner,
            doc.body,
            doc.content_hash,
            embedding_model,
            datetime.now(UTC),
        )
        self._chunks[doc.id] = [
            (
                ChunkRecord(
                    uuid.uuid4(),
                    doc.id,
                    doc.title,
                    c.chunk.section_index,
                    c.chunk.chunk_index,
                    c.chunk.heading,
                    c.chunk.content,
                ),
                c.embedding,
            )
            for c in chunks
        ]

    async def delete_documents(self, ids: Sequence[str]) -> None:
        for document_id in ids:
            self._documents.pop(document_id, None)
            self._chunks.pop(document_id, None)

    async def vector_search(
        self, embedding: Sequence[float], embedding_model: str, limit: int
    ) -> list[SearchHit]:
        hits = [
            SearchHit(record, cosine(embedding, vector))
            for doc_id, rows in self._chunks.items()
            if self._documents[doc_id].embedding_model == embedding_model
            for record, vector in rows
        ]
        return sorted(hits, key=lambda h: h.score, reverse=True)[:limit]

    async def text_search(self, query: str, limit: int) -> list[SearchHit]:
        terms = set(tokenize(query))
        if not terms:
            return []
        hits = []
        for rows in self._chunks.values():
            for record, _ in rows:
                counts = Counter(tokenize(f"{record.heading} {record.content}"))
                score = sum(min(counts[t], 3) for t in terms)  # cap repeats, like ts_rank
                if score:
                    hits.append(SearchHit(record, float(score)))
        return sorted(hits, key=lambda h: h.score, reverse=True)[:limit]

    async def get_document(self, document_id: str) -> StoredDocument | None:
        return self._documents.get(document_id)

    async def list_documents(self) -> list[StoredDocument]:
        return sorted(self._documents.values(), key=lambda d: d.title)
