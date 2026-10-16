"""Knowledge base: parsing, chunking, embedding, ingestion, hybrid retrieval, citations."""

import json
import shutil
import uuid
from pathlib import Path

import httpx
import pytest

from app.rag.citations import SourceBook, snippet
from app.rag.documents import (
    DocumentFormatError,
    chunk_document,
    load_documents,
    parse_document,
    split_sections,
)
from app.rag.embeddings import EmbeddingError, HashingEmbedder, OllamaEmbedder, cosine
from app.rag.eval import run_eval
from app.rag.ingest import ingest
from app.rag.retriever import Retriever
from app.rag.store import ChunkRecord, InMemoryKnowledgeStore
from tests.conftest import KNOWLEDGE_DIR, knowledge_store

DOC = """---
id: test-policy
title: Test Policy
version: "1.0"
effective: 2026-01-01
owner: People Team
---

# Test Policy

Intro paragraph.

## First rule

You get 20 days.

## Second rule

Ask your manager.
"""


def test_parse_document_reads_front_matter_and_sections() -> None:
    doc = parse_document(DOC)
    assert (doc.id, doc.title, doc.version, doc.owner) == (
        "test-policy",
        "Test Policy",
        "1.0",
        "People Team",
    )
    assert [(s.index, s.heading) for s in doc.sections()] == [
        (0, "Overview"),
        (1, "First rule"),
        (2, "Second rule"),
    ]
    assert doc.sections()[1].body == "You get 20 days."


@pytest.mark.parametrize(
    ("text", "problem"),
    [
        ("# No front matter", "missing front matter"),
        ("---\ntitle: X\n---\nbody", "needs id"),
        (DOC.replace("id: test-policy", "id: Not A Slug"), "slug"),
        (DOC.replace("2026-01-01", "January"), "YYYY-MM-DD"),
    ],
)
def test_bad_documents_are_rejected_with_a_reason(text: str, problem: str) -> None:
    with pytest.raises(DocumentFormatError, match=problem):
        parse_document(text, source="bad.md")


def test_long_sections_are_split_on_paragraphs_with_overlap() -> None:
    paragraphs = [f"Paragraph {i} " + "word " * 60 for i in range(6)]
    body = "## Long\n\n" + "\n\n".join(paragraphs)
    doc = parse_document(DOC.split("# Test Policy")[0] + body)
    chunks = chunk_document(doc, max_chars=800)
    assert len(chunks) > 1
    assert all(len(c.content) <= 800 for c in chunks)
    assert all(c.heading == "Long" and c.section_index == 0 for c in chunks)
    # The last paragraph of one chunk opens the next one (overlap).
    first_last = chunks[0].content.split("\n\n")[-1]
    assert chunks[1].content.startswith(first_last)
    # Nothing is lost.
    joined = "\n\n".join(c.content for c in chunks)
    assert all(p.strip() in joined for p in paragraphs)


def test_chunks_are_embedded_with_their_title_and_heading() -> None:
    chunk = chunk_document(parse_document(DOC))[1]
    assert chunk.embedding_text("Test Policy") == "Test Policy > First rule\nYou get 20 days."


def test_the_shipped_knowledge_base_is_valid() -> None:
    documents = load_documents(KNOWLEDGE_DIR)
    assert len(documents) >= 7
    assert "readme" not in {d.id for d in documents}
    for doc in documents:
        assert len(doc.sections()) >= 3, doc.id
        assert split_sections(doc.body) == doc.sections()


async def test_hashing_embedder_is_deterministic_unit_length_and_lexical() -> None:
    e = HashingEmbedder()
    a, b, c = await e.embed_documents(["annual leave days", "annual leave days", "pension plan"])
    assert a == b
    assert abs(cosine(a, a) - 1.0) < 1e-9
    query = await e.embed_query("how many annual leave days")
    assert cosine(query, a) > cosine(query, c)


async def test_ollama_embedder_batches_prefixes_and_normalises() -> None:
    seen: list[dict[str, object]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        seen.append(body)
        return httpx.Response(
            200, json={"embeddings": [[3.0] + [0.0] * 767 for _ in body["input"]]}
        )

    client = httpx.AsyncClient(base_url="http://ollama", transport=httpx.MockTransport(handler))
    embedder = OllamaEmbedder("http://ollama", client=client)
    vectors = await embedder.embed_documents([f"text {i}" for i in range(20)])
    query = await embedder.embed_query("leave")

    assert len(vectors) == 20 and vectors[0][0] == 1.0  # normalised to unit length
    assert [len(b["input"]) for b in seen[:2]] == [16, 4]  # type: ignore[arg-type]
    assert seen[0]["input"][0] == "search_document: text 0"  # type: ignore[index]
    assert seen[-1]["input"] == ["search_query: leave"]
    assert query[0] == 1.0


@pytest.mark.parametrize(
    ("response", "message"),
    [
        (httpx.Response(404, json={"error": "model not found"}), "ollama pull"),
        (httpx.Response(500), "HTTP 500"),
        (httpx.Response(200, json={"embeddings": [[1.0, 2.0]]}), "768 dimensions"),
        (httpx.Response(200, text="not json"), "Unexpected"),
    ],
)
async def test_ollama_embedder_errors_are_explained(response: httpx.Response, message: str) -> None:
    client = httpx.AsyncClient(
        base_url="http://ollama", transport=httpx.MockTransport(lambda _: response)
    )
    with pytest.raises(EmbeddingError, match=message):
        await OllamaEmbedder("http://ollama", client=client).embed_query("x")


async def test_ollama_unreachable_is_an_embedding_error() -> None:
    def refuse(_: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused")

    client = httpx.AsyncClient(base_url="http://ollama", transport=httpx.MockTransport(refuse))
    with pytest.raises(EmbeddingError, match="unreachable"):
        await OllamaEmbedder("http://ollama", client=client).embed_query("x")


async def test_ingest_is_idempotent_and_tracks_changes(tmp_path: Path) -> None:
    for name in ("leave-policy.md", "benefits.md", "expenses.md"):
        shutil.copy(KNOWLEDGE_DIR / name, tmp_path / name)
    store, embedder = InMemoryKnowledgeStore(), HashingEmbedder()

    first = await ingest(tmp_path, store, embedder)
    assert sorted(first.added) == ["benefits", "expenses", "leave-policy"] and first.chunks > 10

    again = await ingest(tmp_path, store, embedder)
    assert again.chunks == 0 and len(again.unchanged) == 3  # nothing re-embedded

    path = tmp_path / "benefits.md"
    path.write_text(path.read_text().replace("500 USD", "600 USD"))
    (tmp_path / "expenses.md").unlink()
    changed = await ingest(tmp_path, store, embedder)
    assert (changed.updated, changed.deleted, changed.unchanged) == (
        ["benefits"],
        ["expenses"],
        ["leave-policy"],
    )
    hits = await Retriever(store, embedder).search("wellbeing allowance gym")
    assert "600 USD" in hits[0].chunk.content

    forced = await ingest(tmp_path, store, embedder, force=True)
    assert sorted(forced.updated) == ["benefits", "leave-policy"]


async def test_a_new_embedding_model_re_embeds_everything(tmp_path: Path) -> None:
    shutil.copy(KNOWLEDGE_DIR / "benefits.md", tmp_path / "benefits.md")
    store = InMemoryKnowledgeStore()
    await ingest(tmp_path, store, HashingEmbedder())

    class OtherModel(HashingEmbedder):
        model = "hashing-v2"

    report = await ingest(tmp_path, store, OtherModel())
    assert report.updated == ["benefits"]


async def test_hybrid_search_finds_the_right_section() -> None:
    retriever = Retriever(knowledge_store(), HashingEmbedder())
    hits = await retriever.search("How many days can I carry over to next year?")
    assert (hits[0].chunk.document_id, hits[0].chunk.heading) == (
        "leave-policy",
        "Carry-over and expiry",
    )
    # Keyword search catches exact terms like acronyms.
    vpn = await retriever.search("VPN")
    assert vpn and all(h.chunk.document_id == "remote-work" for h in vpn)


async def test_unrelated_questions_find_nothing() -> None:
    retriever = Retriever(knowledge_store(), HashingEmbedder())
    assert await retriever.search("Tell me a joke") == []
    assert await retriever.search("What is the capital of France?") == []


async def test_retrieval_quality_does_not_regress() -> None:
    """The offline embedder knows no synonyms, so these floors are modest. Ollama scores
    higher; run `python -m app.rag.eval` to see real-model numbers."""
    result = await run_eval(KNOWLEDGE_DIR, HashingEmbedder())
    assert result.cases >= 25
    assert result.document_hit_rate >= 0.9
    assert result.section_hit_rate >= 0.8
    assert result.off_topic_rejected_rate >= 0.5


def _record(heading: str) -> ChunkRecord:
    return ChunkRecord(uuid.uuid4(), "leave-policy", "Leave Policy", 1, 0, heading, "text")


def test_sources_are_numbered_once_and_citations_follow_the_text() -> None:
    book = SourceBook()
    a, b, c = _record("A"), _record("B"), _record("C")
    assert [book.add(a), book.add(b), book.add(a), book.add(c)] == [1, 2, 1, 3]

    cited = book.cited("Rule C applies [3]. Also [1, 3] and [1][9]. In 2026 [2026] nothing.")
    assert [x.ref for x in cited] == [3, 1]  # first mention order; [9] and [2026] ignored
    assert cited[0].to_dict()["heading"] == "C"
    assert book.cited("No markers here.") == []


def test_snippet_cuts_on_a_word_boundary() -> None:
    assert snippet("short  text\nhere") == "short text here"
    assert snippet("- **Annual leave:** 20 days\n- `Sick`") == "Annual leave: 20 days Sick"
    long = snippet("word " * 100, limit=30)
    assert long.endswith("…") and len(long) <= 31 and not long[:-1].endswith(" ")
