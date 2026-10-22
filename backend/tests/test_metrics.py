"""/metrics: protected, and it reflects what happened."""

import uuid

from httpx import ASGITransport, AsyncClient
from prometheus_client.parser import text_string_to_metric_families
from pydantic import SecretStr

from app.ai.llm.fake import FakeLLMProvider
from app.ai.llm.types import ToolCall
from app.core.config import Settings
from tests.conftest import Harness, as_user, build_test_app

ALICE = as_user("alice")
TOKEN = "metrics-secret-123"  # noqa: S105 (a test value)


def harness_with_metrics(provider: FakeLLMProvider | None = None) -> Harness:
    harness = build_test_app(provider)
    harness.app.state.settings = Settings(
        app_env="test", auth_mode="dev", metrics_token=SecretStr(TOKEN)
    )
    return harness


Samples = dict[tuple[str, tuple[tuple[str, str], ...]], float]


def samples(text: str) -> Samples:
    out = {}
    for family in text_string_to_metric_families(text):
        for s in family.samples:
            out[(s.name, tuple(sorted(s.labels.items())))] = s.value
    return out


def value(data: Samples, name: str, **labels: str) -> float:
    return data.get((name, tuple(sorted(labels.items()))), 0.0)


async def test_metrics_need_the_token() -> None:
    async with AsyncClient(
        transport=ASGITransport(app=harness_with_metrics().app), base_url="http://test"
    ) as c:
        assert (await c.get("/metrics")).status_code == 401
        assert (
            await c.get("/metrics", headers={"Authorization": "Bearer wrong"})
        ).status_code == 401
        assert (
            await c.get("/metrics", headers={"Authorization": f"Bearer {TOKEN}"})
        ).status_code == 200
    # Without a configured token the endpoint doesn't exist.
    async with AsyncClient(
        transport=ASGITransport(app=build_test_app().app), base_url="http://test"
    ) as c:
        assert (await c.get("/metrics")).status_code == 404


async def test_chat_tools_and_http_show_up_in_metrics() -> None:
    provider = FakeLLMProvider(
        turns=[[ToolCall("c1", "getLeaveBalance", {})], "You have plenty left."]
    )
    harness = harness_with_metrics(provider)
    auth = {"Authorization": f"Bearer {TOKEN}"}
    async with AsyncClient(transport=ASGITransport(app=harness.app), base_url="http://test") as c:
        before = samples((await c.get("/metrics", headers=auth)).text)
        body = {"client_message_id": str(uuid.uuid4()), "content": "Leave?", "input_mode": "voice"}
        await c.post("/v1/chat", json=body, headers=ALICE)
        conversation = (await c.get("/v1/conversations", headers=ALICE)).json()["items"][0]["id"]
        await c.get(f"/v1/conversations/{conversation}", headers=ALICE)
        after = samples((await c.get("/metrics", headers=auth)).text)

    def delta(name: str, **labels: str) -> float:
        return value(after, name, **labels) - value(before, name, **labels)

    assert delta("keystone_chat_turns_total", status="complete", input_mode="voice") == 1
    assert delta("keystone_tool_calls_total", tool="getLeaveBalance", status="ok") == 1
    assert delta("keystone_chat_time_to_first_output_seconds_count") == 1
    assert delta("keystone_agent_steps_sum") == 2
    # Route templates, not raw paths with IDs: label cardinality stays bounded.
    assert (
        delta(
            "keystone_http_requests_total",
            method="GET",
            route="/v1/conversations/{conversation_id}",
            status="200",
        )
        == 1
    )
    assert not any(conversation in str(key) for key in after)
