"""Retrieval evaluation: does search find the passage that answers each question?

    uv run python -m app.rag.eval          # against the configured embedder (e.g. Ollama)

Metrics over `knowledge/eval/retrieval.jsonl`:
- hit@k (document): the right document is among the top k passages
- hit@k (section): the exact section is among the top k passages
- MRR: mean reciprocal rank of the right section (1.0 = always first)
- off-topic rejected: share of unrelated questions ("document": null) that return nothing,
  which measures the relevance thresholds (too low → the assistant gets junk passages,
  too high → real questions find nothing)

Run it after changing chunking, the embedding model or retrieval settings, and compare.
"""

import asyncio
import json
import sys
from dataclasses import dataclass
from pathlib import Path

from app.rag.embeddings import Embedder
from app.rag.ingest import ingest
from app.rag.retriever import Retriever
from app.rag.store import InMemoryKnowledgeStore


@dataclass(frozen=True)
class EvalCase:
    question: str
    document: str | None  # None: off-topic, search should return nothing
    section: str | None = None


@dataclass(frozen=True)
class EvalResult:
    cases: int
    document_hit_rate: float
    section_hit_rate: float
    mrr: float
    off_topic: int
    off_topic_rejected_rate: float
    misses: list[str]

    def summary(self, k: int) -> str:
        return (
            f"{self.cases} questions | document hit@{k}: {self.document_hit_rate:.0%} | "
            f"section hit@{k}: {self.section_hit_rate:.0%} | MRR: {self.mrr:.2f} | "
            f"off-topic rejected: {self.off_topic_rejected_rate:.0%} of {self.off_topic}"
        )


def load_cases(path: Path) -> list[EvalCase]:
    lines = path.read_text(encoding="utf-8").splitlines()
    return [EvalCase(**json.loads(line)) for line in lines if line.strip()]


async def evaluate(retriever: Retriever, cases: list[EvalCase], k: int = 4) -> EvalResult:
    doc_hits = section_hits = 0
    reciprocal = 0.0
    misses: list[str] = []
    off_topic = rejected = 0
    for case in cases:
        results = await retriever.search(case.question, top_k=k)
        if case.document is None:
            off_topic += 1
            if results:
                misses.append(f"{case.question} (off-topic, but found passages)")
            else:
                rejected += 1
            continue
        documents = [r.chunk.document_id for r in results]
        sections = [(r.chunk.document_id, r.chunk.heading) for r in results]
        doc_hits += case.document in documents
        target = (case.document, case.section)
        if target in sections:
            section_hits += 1
            reciprocal += 1 / (sections.index(target) + 1)
        else:
            misses.append(case.question)
    n = max(len(cases) - off_topic, 1)
    return EvalResult(
        n,
        doc_hits / n,
        section_hits / n,
        reciprocal / n,
        off_topic,
        rejected / off_topic if off_topic else 1.0,
        misses,
    )


async def run_eval(
    knowledge_dir: Path, embedder: Embedder, k: int = 4, min_similarity: float | None = None
) -> EvalResult:
    """Ingests into a throwaway in-memory store, so the eval never touches the database."""
    store = InMemoryKnowledgeStore()
    await ingest(knowledge_dir, store, embedder)
    cases = load_cases(knowledge_dir / "eval" / "retrieval.jsonl")
    return await evaluate(
        Retriever(store, embedder, top_k=k, min_similarity=min_similarity), cases, k
    )


async def _main() -> int:
    from app.core.config import get_settings
    from app.rag.factory import create_embedder

    settings = get_settings()
    embedder = create_embedder(settings)
    try:
        result = await run_eval(
            Path(settings.knowledge_dir), embedder, settings.rag_top_k, settings.rag_min_similarity
        )
    finally:
        aclose = getattr(embedder, "aclose", None)
        if aclose is not None:
            await aclose()
    print(f"[{embedder.model}] {result.summary(settings.rag_top_k)}")
    for question in result.misses:
        print(f"  missed: {question}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(_main()))
