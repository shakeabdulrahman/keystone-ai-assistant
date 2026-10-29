"""Home dashboard, device registration, push notifications and the demo HR workflow."""

import json
import uuid
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs

import httpx
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from httpx import ASGITransport, AsyncClient

from app.ai.llm.fake import FakeLLMProvider
from app.ai.llm.types import ToolCall
from app.notifications.push import FcmPushSender, PushMessage, PushResult
from app.notifications.service import NotificationService
from app.repositories.memory import InMemoryDeviceRepository
from tests.conftest import NOW, TODAY, Harness, as_user, build_test_app
from tests.sse import parse_sse

ALICE, BOB = as_user("alice"), as_user("bob")
TOKEN_A = "fcm-token-" + "a" * 40
TOKEN_B = "fcm-token-" + "b" * 40


def client_for(harness: Harness) -> AsyncClient:
    return AsyncClient(transport=ASGITransport(app=harness.app), base_url="http://test")


async def uid(harness: Harness, name: str = "alice") -> uuid.UUID:
    return (await harness.users.get_or_create(name, None, None)).id


def leave_call() -> list[ToolCall]:
    return [
        ToolCall(
            "c1",
            "submitLeaveRequest",
            {"leave_type": "annual", "start_date": "2026-12-14", "end_date": "2026-12-15"},
        )
    ]


async def propose_leave(c: AsyncClient) -> tuple[str, str]:
    body = {"client_message_id": str(uuid.uuid4()), "content": "Book leave"}
    frames = parse_sse((await c.post("/v1/chat", json=body, headers=ALICE)).text)
    card = next(f for f in frames if f.event == "confirmation.required").data
    return frames[0].data["conversation_id"], card["action_id"]


# --- Home ---------------------------------------------------------------------------


async def test_home_summarises_what_needs_attention() -> None:
    harness = build_test_app()
    async with client_for(harness) as c:
        home = (await c.get("/v1/home", headers=ALICE)).json()

    user_id = await uid(harness)
    tasks = {t.id: t for t in await harness.hris.tasks(user_id)}
    profile = await harness.hris.profile(user_id, None)

    assert home["first_name"] == profile.name.split(" ")[0]
    assert {b["type"] for b in home["leave"]} == {"Annual", "Sick", "Casual"}
    for item in home["attention"]:
        assert item["severity"] in ("high", "normal") and item["title"]
        if item["kind"] == "overdue_task":
            task = tasks[item["id"].removeprefix("task-")]
            assert task.status == "pending" and task.due < TODAY
            assert item["action"] == {
                "type": "ask",
                "prompt": f"Help me with my task {task.id}: {task.title}",
            }
    overdue = [t for t in tasks.values() if t.status == "pending" and t.due < TODAY]
    assert len([i for i in home["attention"] if i["kind"] == "overdue_task"]) == len(overdue)
    # Most urgent first.
    severities = [i["severity"] for i in home["attention"]]
    assert severities == sorted(severities, key=lambda s: s != "high")

    unread = [n for n in await harness.hris.notifications(user_id) if not n.read]
    assert home["counts"]["unread_notifications"] == len(unread)
    for meeting in home["upcoming"]:
        start = datetime.fromisoformat(meeting["start"])
        assert NOW <= start <= NOW + timedelta(days=14)
    assert 1 <= len(home["suggestions"]) <= 4


async def test_an_unconfirmed_card_shows_on_home_until_decided() -> None:
    harness = build_test_app(FakeLLMProvider(turns=[leave_call(), "Please confirm."]))
    async with client_for(harness) as c:
        conversation_id, action_id = await propose_leave(c)
        home = (await c.get("/v1/home", headers=ALICE)).json()
        [item] = [i for i in home["attention"] if i["kind"] == "pending_action"]
        assert item["severity"] == "high"
        assert item["title"] == "Waiting for you: request annual leave"
        assert item["action"] == {
            "type": "open_conversation",
            "conversation_id": conversation_id,
            "action_id": action_id,
        }
        assert home["attention"][0]["kind"] == "pending_action"  # first

        await c.post(f"/v1/actions/{action_id}/confirm", headers=ALICE)
        after = (await c.get("/v1/home", headers=ALICE)).json()
    assert all(i["kind"] != "pending_action" for i in after["attention"])
    assert after["counts"]["pending_leave_requests"] >= 1


async def test_home_is_per_user() -> None:
    harness = build_test_app(FakeLLMProvider(turns=[leave_call(), "Please confirm."]))
    async with client_for(harness) as c:
        await propose_leave(c)  # Alice's card
        bob = (await c.get("/v1/home", headers=BOB)).json()
    assert all(i["kind"] != "pending_action" for i in bob["attention"])


# --- Devices --------------------------------------------------------------------------


async def test_devices_register_move_between_accounts_and_unregister() -> None:
    harness = build_test_app()
    async with client_for(harness) as c:
        assert (
            await c.post("/v1/devices", json={"token": TOKEN_A}, headers=ALICE)
        ).status_code == 204
        assert (
            await c.post("/v1/devices", json={"token": TOKEN_A}, headers=ALICE)
        ).status_code == 204
        assert await harness.devices.tokens_for(await uid(harness)) == [TOKEN_A]

        # Bob signs in on the same phone: the token now belongs to Bob only.
        await c.post("/v1/devices", json={"token": TOKEN_A}, headers=BOB)
        assert await harness.devices.tokens_for(await uid(harness)) == []
        assert await harness.devices.tokens_for(await uid(harness, "bob")) == [TOKEN_A]

        # Alice can't unregister Bob's device.
        await c.post("/v1/devices/unregister", json={"token": TOKEN_A}, headers=ALICE)
        assert await harness.devices.tokens_for(await uid(harness, "bob")) == [TOKEN_A]
        await c.post("/v1/devices/unregister", json={"token": TOKEN_A}, headers=BOB)
        assert await harness.devices.tokens_for(await uid(harness, "bob")) == []

        bad = await c.post("/v1/devices", json={"token": "short"}, headers=ALICE)
        assert bad.status_code == 422


# --- Notifications and the demo workflow -------------------------------------------------


class StaleSender:
    def __init__(self, stale: list[str]) -> None:
        self.stale = stale
        self.calls: list[tuple[list[str], PushMessage]] = []

    async def send(self, tokens: list[str], message: PushMessage) -> PushResult:
        self.calls.append((tokens, message))
        return PushResult(sent=len(tokens) - len(self.stale), stale_tokens=self.stale)


async def test_notify_adds_an_in_app_notification_pushes_and_prunes_stale_tokens() -> None:
    harness = build_test_app()
    devices = InMemoryDeviceRepository()
    user_id = await uid(harness)
    await devices.register(user_id, TOKEN_A, "android")
    await devices.register(user_id, TOKEN_B, "android")
    sender = StaleSender(stale=[TOKEN_B])
    service = NotificationService(harness.hris, devices, sender)

    await service.notify(
        user_id,
        kind="leave",
        ref="LR-1050",
        detail_title="Leave approved",
        detail_body="Your annual leave LR-1050 was approved.",
        push_body="Your leave request has an update.",
    )

    newest = (await harness.hris.notifications(user_id))[0]
    assert (newest.title, newest.read) == ("Leave approved", False)
    [(tokens, message)] = sender.calls
    assert sorted(tokens) == [TOKEN_A, TOKEN_B]
    assert message.body == "Your leave request has an update."
    assert message.data == {"kind": "leave", "ref": "LR-1050"}
    assert await devices.tokens_for(user_id) == [TOKEN_A]  # the stale one was forgotten


async def test_confirmed_leave_is_approved_by_the_demo_manager_with_a_discreet_push() -> None:
    harness = build_test_app(
        FakeLLMProvider(turns=[leave_call(), "Please confirm."]), workflow_delay_s=0.01
    )
    async with client_for(harness) as c:
        await c.post("/v1/devices", json={"token": TOKEN_A}, headers=ALICE)
        _, action_id = await propose_leave(c)
        await c.post(f"/v1/actions/{action_id}/confirm", headers=ALICE)
        await harness.workflow.drain()

        user_id = await uid(harness)
        request = next(r for r in await harness.hris.leave_requests(user_id) if r.id == "LR-1050")
        assert request.status == "approved"
        newest = (await harness.hris.notifications(user_id))[0]
        assert newest.title == "Leave approved" and "LR-1050" in newest.body

        [(tokens, message)] = harness.push.sent
        assert tokens == [TOKEN_A]
        # Lock-screen text has no HR details: no dates, leave type or decision.
        assert message.body == "Your leave request has an update."
        for secret in ("annual", "approved", "Dec", "2026"):
            assert secret.lower() not in (message.title + message.body).lower()

        home = (await c.get("/v1/home", headers=ALICE)).json()
    assert home["counts"]["unread_notifications"] >= 1


async def test_withdrawn_leave_is_skipped_by_the_demo_manager() -> None:
    harness = build_test_app(
        FakeLLMProvider(
            turns=[
                leave_call(),
                "Please confirm.",
                [ToolCall("c2", "cancelLeaveRequest", {"request_id": "LR-1050"})],
                "Please confirm.",
            ]
        ),
        workflow_delay_s=0.05,
    )
    async with client_for(harness) as c:
        conversation_id, action_id = await propose_leave(c)
        await c.post(f"/v1/actions/{action_id}/confirm", headers=ALICE)
        body = {
            "client_message_id": str(uuid.uuid4()),
            "content": "Cancel it",
            "conversation_id": conversation_id,
        }
        frames = parse_sse((await c.post("/v1/chat", json=body, headers=ALICE)).text)
        withdraw = next(f for f in frames if f.event == "confirmation.required").data
        await c.post(f"/v1/actions/{withdraw['action_id']}/confirm", headers=ALICE)
        await harness.workflow.drain()  # the "manager" finds nothing to approve: no crash
    request = next(
        r for r in await harness.hris.leave_requests(await uid(harness)) if r.id == "LR-1050"
    )
    assert request.status == "cancelled"
    assert harness.push.sent == []


# --- FCM sender --------------------------------------------------------------------------


def service_account() -> dict[str, Any]:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ).decode()
    return {
        "type": "service_account",
        "project_id": "keystone-hr",
        "private_key_id": "k1",
        "private_key": pem,
        "client_email": "push@keystone-hr.iam.gserviceaccount.com",
    }


async def test_fcm_sender_authenticates_once_sends_and_reports_stale_tokens() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        if request.url.host == "oauth2.googleapis.com":
            form = parse_qs(request.content.decode())
            assert form["grant_type"] == ["urn:ietf:params:oauth:grant-type:jwt-bearer"]
            return httpx.Response(200, json={"access_token": "ya29.test", "expires_in": 3600})
        assert request.url.path == "/v1/projects/keystone-hr/messages:send"
        assert request.headers["authorization"] == "Bearer ya29.test"
        token = json.loads(request.content)["message"]["token"]
        if token == TOKEN_B:
            return httpx.Response(
                404,
                json={"error": {"status": "NOT_FOUND", "details": [{"errorCode": "UNREGISTERED"}]}},
            )
        return httpx.Response(200, json={"name": "projects/keystone-hr/messages/1"})

    sender = FcmPushSender(
        service_account(), client=httpx.AsyncClient(transport=httpx.MockTransport(handler))
    )
    message = PushMessage(
        "Keystone", "Your leave request has an update.", {"kind": "leave", "ref": "LR-1050"}
    )
    first = await sender.send([TOKEN_A, TOKEN_B], message)
    second = await sender.send([TOKEN_A], message)

    assert (first.sent, first.stale_tokens) == (1, [TOKEN_B])
    assert second.sent == 1
    token_requests = [r for r in seen if r.url.host == "oauth2.googleapis.com"]
    assert len(token_requests) == 1  # cached for the hour
    sent = json.loads(next(r for r in seen if r.url.host == "fcm.googleapis.com").content)[
        "message"
    ]
    assert sent["android"]["notification"]["channel_id"] == "hr_updates"
    assert sent["data"] == {"kind": "leave", "ref": "LR-1050"}


async def test_fcm_sender_survives_network_errors() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.host == "oauth2.googleapis.com":
            return httpx.Response(200, json={"access_token": "t", "expires_in": 3600})
        raise httpx.ConnectError("offline")

    sender = FcmPushSender(
        service_account(), client=httpx.AsyncClient(transport=httpx.MockTransport(handler))
    )
    result = await sender.send([TOKEN_A], PushMessage("Keystone", "Update", {}))
    assert (result.sent, result.stale_tokens) == (0, [])


def test_dates_used_in_home_tests_are_fixed() -> None:
    assert TODAY == date(2026, 10, 15) and NOW.date() == TODAY


def test_missing_push_key_falls_back_to_logging(tmp_path: Path) -> None:
    from app.core.config import Settings
    from app.main import _push_sender
    from app.notifications.push import LogPushSender

    missing = Settings(fcm_service_account_file=str(tmp_path / "nope.json"), _env_file=None)
    assert isinstance(_push_sender(missing), LogPushSender)

    broken = tmp_path / "broken.json"
    broken.write_text("{not json")
    bad = Settings(fcm_service_account_file=str(broken), _env_file=None)
    assert isinstance(_push_sender(bad), LogPushSender)


def test_people_see_their_own_name_not_a_generated_one() -> None:
    from app.integrations.hris import person_name

    assert person_name("  Abdul   Rahman ", "x@y.com") == "Abdul Rahman"
    assert person_name(None, "rahman.basha@amtexsystems.com") == "Rahman Basha"
    assert person_name("", "priya_k+test@corp.io") == "Priya K"
    assert person_name(None, "12345@corp.io") is None
    assert person_name(None, None) is None


async def test_home_greets_by_the_name_from_sign_in() -> None:
    harness = build_test_app()
    async with client_for(harness) as c:
        home = (await c.get("/v1/home", headers=as_user("u1", "rahman.basha@example.com"))).json()
    assert home["first_name"] == "Rahman"
