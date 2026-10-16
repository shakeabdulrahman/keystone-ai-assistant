"""Policy questions end to end: search tool → cited answer → citations event → history."""

import json
import uuid

import pytest
from httpx import ASGITransport, AsyncClient

from app.ai.llm.fake import FakeLLMProvider
from app.ai.llm.types import ToolCall
from app.rag.embeddings import EmbeddingError, HashingEmbedder
from app.rag.retriever import Retriever
from tests.conftest import Harness, as_user, build_test_app, knowledge_store
from tests.sse import Frame, parse_sse

ALICE = as_user("alice")


def client_for(harness: Harness) -> AsyncClient:
    return AsyncClient(transport=ASGITransport(app=harness.app), base_url="http://test")


async def ask(c: AsyncClient, text: str) -> list[Frame]:
    response = await c.post(
        "/v1/chat",
        json={"client_message_id": str(uuid.uuid4()), "content": text},
        headers=ALICE,
    )
    assert response.status_code == 200
    return parse_sse(response.text)


def search(query: str, call_id: str = "call_1") -> list[ToolCall]:
    return [ToolCall(call_id, "searchPolicies", {"query": query})]


async def test_cited_answer_streams_citations_before_completion() -> None:
    provider = FakeLLMProvider(
        turns=[search("carry over annual leave"), "You can carry over up to 5 days [1]."]
    )
    async with client_for(build_test_app(provider)) as c:
        frames = await ask(c, "Can I carry over leave?")

    types = [f.event for f in frames]
    assert types[-2:] == ["citations", "message.completed"]
    result = next(f for f in frames if f.event == "tool.result")
    assert result.data["ui"] == "policy_search"
    first = result.data["data"]["results"][0]
    assert (first["ref"], first["document"], first["section"]) == (
        1,
        "Leave Policy",
        "Carry-over and expiry",
    )

    [citation] = frames[-2].data["items"]
    assert citation["ref"] == 1
    assert (citation["document_id"], citation["heading"]) == (
        "leave-policy",
        "Carry-over and expiry",
    )
    assert citation["section_index"] >= 0 and "5 days" in citation["snippet"]

    # The model saw numbered passages and the rules for using them.
    second = provider.requests[1]
    passages = json.loads(second.messages[-1].content)
    assert passages["results"][0]["ref"] == 1
    assert "cite every fact" in second.system


async def test_only_cited_sources_become_citations() -> None:
    provider = FakeLLMProvider(
        turns=[search("parental leave"), "Primary caregivers get 26 weeks [2]. See also [7]."]
    )
    async with client_for(build_test_app(provider)) as c:
        frames = await ask(c, "Parental leave?")
    [event] = [f for f in frames if f.event == "citations"]
    assert [i["ref"] for i in event.data["items"]] == [2]  # [1] not used, [7] doesn't exist


async def test_no_citations_event_when_the_answer_cites_nothing() -> None:
    provider = FakeLLMProvider(turns=[search("expenses"), "Please check with Finance."])
    async with client_for(build_test_app(provider)) as c:
        frames = await ask(c, "Expenses?")
    assert "citations" not in [f.event for f in frames]


async def test_numbers_stay_unique_across_several_searches() -> None:
    provider = FakeLLMProvider(
        turns=[
            search("parental leave primary caregiver", "c1")
            + search("home office allowance", "c2"),
            "26 weeks [1], and a 300 USD allowance [5].",
        ]
    )
    async with client_for(build_test_app(provider)) as c:
        frames = await ask(c, "Two questions")
    results = [f.data["data"]["results"] for f in frames if f.event == "tool.result"]
    refs = [r["ref"] for batch in results for r in batch]
    assert refs == list(range(1, len(refs) + 1))
    [event] = [f for f in frames if f.event == "citations"]
    assert [i["ref"] for i in event.data["items"]] == [1, 5]


async def test_unanswerable_policy_question_returns_an_empty_search() -> None:
    provider = FakeLLMProvider(turns=[search("tell me a joke"), "I couldn't find that."])
    async with client_for(build_test_app(provider)) as c:
        frames = await ask(c, "Joke?")
    result = next(f for f in frames if f.event == "tool.result")
    assert result.data["data"]["results"] == []
    assert "No company policy" in result.data["data"]["note"]


async def test_citations_are_saved_with_the_answer() -> None:
    provider = FakeLLMProvider(turns=[search("probation period"), "Probation lasts 6 months [1]."])
    harness = build_test_app(provider)
    async with client_for(harness) as c:
        frames = await ask(c, "How long is probation?")
        conversation_id = frames[0].data["conversation_id"]
        page = (await c.get(f"/v1/conversations/{conversation_id}/messages", headers=ALICE)).json()
    parts = page["items"][-1]["parts"]
    assert [p["type"] for p in parts] == ["tool_result", "citations"]
    assert parts[1]["items"] == next(f for f in frames if f.event == "citations").data["items"]
    [entry] = harness.audit.entries
    assert entry.tool_name == "searchPolicies"
    assert entry.arguments == {"query": "probation period"}


async def test_demo_mode_answers_policy_questions_with_a_citation() -> None:
    async with client_for(build_test_app(FakeLLMProvider(demo=True))) as c:
        frames = await ask(c, "What is the parental leave policy?")
    types = [f.event for f in frames]
    assert "tool.started" in types and types[-2] == "citations"
    text = "".join(f.data["text"] for f in frames if f.event == "text.delta")
    assert text.startswith("According to the Parental Leave Policy") and text.endswith("[1].")


@pytest.mark.parametrize("question", ["How many leave days do I have left?", "Show my tasks"])
async def test_demo_mode_still_uses_hr_records_for_personal_questions(question: str) -> None:
    async with client_for(build_test_app(FakeLLMProvider(demo=True))) as c:
        frames = await ask(c, question)
    tool = next(f for f in frames if f.event == "tool.started").data["tool"]
    assert tool != "searchPolicies"


async def test_documents_can_be_listed_and_opened_at_a_section() -> None:
    async with client_for(build_test_app()) as c:
        listing = (await c.get("/v1/documents", headers=ALICE)).json()["items"]
        assert {"leave-policy", "parental-leave", "onboarding"} <= {d["id"] for d in listing}

        doc = (await c.get("/v1/documents/leave-policy", headers=ALICE)).json()
        assert doc["title"] == "Leave Policy" and doc["version"] == "2026.1"
        headings = [s["heading"] for s in doc["sections"]]
        assert "Carry-over and expiry" in headings
        assert [s["index"] for s in doc["sections"]] == list(range(len(headings)))

        missing = await c.get("/v1/documents/nope", headers=ALICE)
        assert missing.status_code == 404


async def test_citation_section_index_matches_the_document_section() -> None:
    provider = FakeLLMProvider(turns=[search("medical certificate sick"), "You need one [1]."])
    async with client_for(build_test_app(provider)) as c:
        frames = await ask(c, "Sick note?")
        [citation] = next(f for f in frames if f.event == "citations").data["items"]
        doc = (await c.get(f"/v1/documents/{citation['document_id']}", headers=ALICE)).json()
    section = doc["sections"][citation["section_index"]]
    assert section["heading"] == citation["heading"]


async def test_embedding_outage_becomes_a_tool_error_not_a_crash() -> None:
    class DownEmbedder(HashingEmbedder):
        async def embed_query(self, text: str) -> list[float]:
            raise EmbeddingError("Ollama unreachable")

    provider = FakeLLMProvider(turns=[search("leave"), "I couldn't search the policies."])
    harness = build_test_app(provider, retriever=Retriever(knowledge_store(), DownEmbedder()))
    async with client_for(harness) as c:
        frames = await ask(c, "Leave policy?")
    result = next(f for f in frames if f.event == "tool.result")
    assert (result.data["status"], result.data["ui"]) == ("error", None)
    assert frames[-1].event == "message.completed"
    assert "Ollama" not in json.dumps(result.data)  # internals stay in the logs
