"""Postgres implementation of the knowledge store: pgvector + full-text search."""

import uuid
from collections.abc import Sequence
from datetime import UTC, datetime

from sqlalchemy import delete, func, select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.db.models import Document, DocumentChunk
from app.rag.documents import SourceDocument
from app.rag.embeddings import tokenize
from app.rag.store import ChunkRecord, EmbeddedChunk, SearchHit, StoredDocument


def _document(row: Document) -> StoredDocument:
    return StoredDocument(
        row.id,
        row.title,
        row.version,
        row.effective,
        row.owner,
        row.body,
        row.content_hash,
        row.embedding_model,
        row.updated_at,
    )


def _chunk(row: DocumentChunk, title: str) -> ChunkRecord:
    return ChunkRecord(
        row.id, row.document_id, title, row.section_index, row.chunk_index, row.heading, row.content
    )


class SqlKnowledgeStore:
    def __init__(self, sessions: async_sessionmaker[AsyncSession]) -> None:
        self._sessions = sessions

    async def fingerprints(self) -> dict[str, tuple[str, str]]:
        async with self._sessions() as session:
            rows = await session.execute(
                select(Document.id, Document.content_hash, Document.embedding_model)
            )
            return {r.id: (r.content_hash, r.embedding_model) for r in rows}

    async def replace_document(
        self, doc: SourceDocument, chunks: Sequence[EmbeddedChunk], embedding_model: str
    ) -> None:
        values = {
            "title": doc.title,
            "version": doc.version,
            "effective": doc.effective,
            "owner": doc.owner,
            "body": doc.body,
            "content_hash": doc.content_hash,
            "embedding_model": embedding_model,
            "updated_at": datetime.now(UTC),
        }
        async with self._sessions() as session, session.begin():
            await session.execute(
                insert(Document)
                .values(id=doc.id, **values)
                .on_conflict_do_update(index_elements=[Document.id], set_=values)
            )
            await session.execute(delete(DocumentChunk).where(DocumentChunk.document_id == doc.id))
            session.add_all(
                DocumentChunk(
                    id=uuid.uuid4(),
                    document_id=doc.id,
                    section_index=c.chunk.section_index,
                    chunk_index=c.chunk.chunk_index,
                    heading=c.chunk.heading,
                    content=c.chunk.content,
                    embedding=c.embedding,
                )
                for c in chunks
            )

    async def delete_documents(self, ids: Sequence[str]) -> None:
        async with self._sessions() as session, session.begin():
            await session.execute(delete(Document).where(Document.id.in_(list(ids))))

    async def vector_search(
        self, embedding: Sequence[float], embedding_model: str, limit: int
    ) -> list[SearchHit]:
        distance = DocumentChunk.embedding.cosine_distance(list(embedding))
        query = (
            select(DocumentChunk, Document.title, (1 - distance).label("similarity"))
            .join(Document, Document.id == DocumentChunk.document_id)
            # Only vectors from the same model are comparable.
            .where(Document.embedding_model == embedding_model)
            .order_by(distance)
            .limit(limit)
        )
        async with self._sessions() as session:
            rows = await session.execute(query)
            return [SearchHit(_chunk(r[0], r[1]), float(r[2])) for r in rows]

    async def text_search(self, query: str, limit: int) -> list[SearchHit]:
        # OR the words together: "carry over unused leave" should match a passage with
        # only some of them. Only [a-z0-9] words reach to_tsquery, so input can't break it.
        terms = sorted(set(tokenize(query)))
        if not terms:
            return []
        tsquery = func.to_tsquery("english", " | ".join(terms))
        rank = func.ts_rank_cd(DocumentChunk.tsv, tsquery)
        statement = (
            select(DocumentChunk, Document.title, rank.label("rank"))
            .join(Document, Document.id == DocumentChunk.document_id)
            .where(DocumentChunk.tsv.op("@@")(tsquery))
            .order_by(rank.desc())
            .limit(limit)
        )
        async with self._sessions() as session:
            rows = await session.execute(statement)
            return [SearchHit(_chunk(r[0], r[1]), float(r[2])) for r in rows]

    async def get_document(self, document_id: str) -> StoredDocument | None:
        async with self._sessions() as session:
            row = await session.get(Document, document_id)
            return _document(row) if row else None

    async def list_documents(self) -> list[StoredDocument]:
        async with self._sessions() as session:
            rows = await session.scalars(select(Document).order_by(Document.title))
            return [_document(r) for r in rows]
