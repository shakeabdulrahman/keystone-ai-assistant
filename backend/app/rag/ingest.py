"""Loads the policy documents into the knowledge store.

    uv run python -m app.rag.ingest            # uses EMBEDDING_PROVIDER / KNOWLEDGE_DIR
    uv run python -m app.rag.ingest --force    # re-embed everything

Idempotent: a document is re-embedded only when its content or the embedding model
changed (compared by fingerprint), and documents whose files were removed are deleted.
Each document is replaced in one transaction, so search never sees half a document.
"""

import argparse
import asyncio
import logging
import sys
from dataclasses import dataclass, field
from pathlib import Path

from app.rag.documents import chunk_document, load_documents
from app.rag.embeddings import Embedder
from app.rag.store import EmbeddedChunk, KnowledgeStore

logger = logging.getLogger("keystone.ingest")


@dataclass
class IngestReport:
    added: list[str] = field(default_factory=list)
    updated: list[str] = field(default_factory=list)
    unchanged: list[str] = field(default_factory=list)
    deleted: list[str] = field(default_factory=list)
    chunks: int = 0

    def summary(self) -> str:
        return (
            f"{len(self.added)} added, {len(self.updated)} updated, "
            f"{len(self.unchanged)} unchanged, {len(self.deleted)} deleted, "
            f"{self.chunks} chunks embedded"
        )


async def ingest(
    directory: Path, store: KnowledgeStore, embedder: Embedder, *, force: bool = False
) -> IngestReport:
    documents = load_documents(directory)  # parse everything first: fail before writing
    stored = await store.fingerprints()
    report = IngestReport()

    for doc in documents:
        previous = stored.get(doc.id)
        if not force and previous == (doc.content_hash, embedder.model):
            report.unchanged.append(doc.id)
            continue
        chunks = chunk_document(doc)
        vectors = await embedder.embed_documents([c.embedding_text(doc.title) for c in chunks])
        await store.replace_document(
            doc,
            [EmbeddedChunk(c, v) for c, v in zip(chunks, vectors, strict=True)],
            embedder.model,
        )
        report.chunks += len(chunks)
        (report.updated if previous else report.added).append(doc.id)

    removed = sorted(set(stored) - {d.id for d in documents})
    if removed:
        await store.delete_documents(removed)
        report.deleted = removed
    return report


async def _main(force: bool) -> int:
    # Imported here so that importing this module (e.g. in tests) has no side effects.
    from app.core.config import get_settings
    from app.db.session import Database
    from app.rag.documents import DocumentFormatError
    from app.rag.embeddings import EmbeddingError
    from app.rag.factory import create_embedder
    from app.rag.sql_store import SqlKnowledgeStore

    settings = get_settings()
    db = Database(settings)
    embedder = create_embedder(settings)
    try:
        report = await ingest(
            Path(settings.knowledge_dir),
            SqlKnowledgeStore(db.session_factory),
            embedder,
            force=force,
        )
    except (EmbeddingError, DocumentFormatError) as exc:
        print(f"Knowledge base not loaded: {exc}", file=sys.stderr)
        return 1
    finally:
        aclose = getattr(embedder, "aclose", None)
        if aclose is not None:
            await aclose()
        await db.dispose()
    print(f"Knowledge base ({embedder.model}): {report.summary()}")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Load HR policy documents for search.")
    parser.add_argument("--force", action="store_true", help="re-embed every document")
    sys.exit(asyncio.run(_main(parser.parse_args().force)))
