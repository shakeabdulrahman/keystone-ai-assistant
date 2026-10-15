import uuid

import pytest
from httpx import ASGITransport, AsyncClient

from app.ai.llm.fake import FakeLLMProvider
from app.services.titles import clean_title, fallback_title
from tests.conftest import as_user, build_test_app
from tests.sse import parse_sse


def test_fallback_title_shortens_long_questions() -> None:
    assert fallback_title("Short   question") == "Short question"
    long = fallback_title("word " * 40)
    assert len(long) <= 60
    assert long.endswith("…")


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ('"Annual Leave Request Process."', "Annual Leave Request Process"),
        ("Title: Parental leave basics", "Parental leave basics"),
        ("   ", None),
    ],
)
def test_clean_title(raw: str, expected: str | None) -> None:
    assert clean_title(raw) == expected


async def test_first_exchange_gets_generated_title() -> None:
    harness = build_test_app(FakeLLMProvider("Leave Request Steps"), titles=True)
    transport = ASGITransport(app=harness.app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        response = await c.post(
            "/v1/chat",
            json={"client_message_id": str(uuid.uuid4()), "content": "How do I ask for leave?"},
            headers=as_user("alice"),
        )
        conversation_id = parse_sse(response.text)[0].data["conversation_id"]
        await harness.titles.drain()
        detail = await c.get(f"/v1/conversations/{conversation_id}", headers=as_user("alice"))

    assert detail.json()["title"] == "Leave Request Steps"
    title_request = harness.provider.requests[-1]
    assert title_request.tier == "fast"
    assert "title" in title_request.system.lower()
