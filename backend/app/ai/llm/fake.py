"""Deterministic provider for tests, demos and offline UI work. Costs nothing, needs no network."""

import asyncio
import json
import re
import uuid
from collections.abc import AsyncGenerator, Sequence
from datetime import date, timedelta

from app.ai.llm.base import LLMError
from app.ai.llm.types import (
    ChatMessage,
    Done,
    LLMEvent,
    LLMRequest,
    TextDelta,
    ToolCall,
    ToolCallRequested,
    Usage,
)

DEFAULT_REPLY = (
    "Hi! I'm Keystone, your HR assistant. Ask me about your leave balance, leave requests, "
    "HR requests, onboarding tasks, meetings, notifications or company policies."
)

# Demo mode: questions with these words search the policy documents (checked first).
_POLICY_WORDS = (
    "policy",
    "policies",
    "how do i",
    "allowed",
    "rule",
    "parental",
    "maternity",
    "paternity",
    "adoption",
    "remote",
    "work from home",
    "hybrid",
    "expense",
    "reimburse",
    "receipt",
    "carry over",
    "public holiday",
    "benefit",
    "insurance",
    "pension",
    "gym",
    "wellbeing",
    "gift",
    "probation",
    "conduct",
    "harassment",
    "allowance",
    "sick leave",
    "medical certificate",
)

# A scripted turn is either text to stream, or tool calls to request.
FakeTurn = str | list[ToolCall]

# Demo mode (no script): which tool a question about each keyword triggers.
_DEMO_ROUTES: list[tuple[tuple[str, ...], str]] = [
    (("balance", "how many", "days left", "remaining"), "getLeaveBalance"),
    (("leave request", "time off request", "my leave"), "getLeaveRequests"),
    (("hr request", "ticket", "laptop", "payroll"), "getHrRequests"),
    (("task", "onboarding", "training", "to do", "todo"), "getTasks"),
    (("meeting", "appointment", "1:1", "calendar"), "getAppointments"),
    (("notification", "update", "news"), "getNotifications"),
    (("profile", "manager", "who am i", "my details"), "getUserProfile"),
]


class FakeLLMProvider:
    """Replays scripted turns word by word.

    - `turns`: one entry per call to `stream` (the last one repeats). A string streams as
      text; a list of ToolCall asks for tools. Without `turns`, `reply` is streamed, or in
      `demo` mode the fake picks a tool by keyword so the app can be explored without a model.
    - `delay_s`: pause between chunks, to exercise streaming UIs and cancellation.
    - `fail_with` / `fail_after`: raise an error part-way, to test error paths.
    """

    name = "fake"
    model = "fake-1"

    def __init__(
        self,
        reply: str = DEFAULT_REPLY,
        *,
        turns: Sequence[FakeTurn] | None = None,
        demo: bool = False,
        delay_s: float = 0.0,
        fail_with: LLMError | None = None,
        fail_after: int = 0,
    ) -> None:
        self.reply = reply
        self.turns = list(turns) if turns else None
        self.demo = demo
        self.delay_s = delay_s
        self.fail_with = fail_with
        self.fail_after = fail_after
        self.requests: list[LLMRequest] = []  # lets tests assert on what was sent

    async def stream(self, request: LLMRequest) -> AsyncGenerator[LLMEvent, None]:
        self.requests.append(request)
        turn = self._next_turn(request)
        input_tokens = sum(len(m.content.split()) for m in request.messages)

        if isinstance(turn, list):
            for call in turn:
                yield ToolCallRequested(call)
            yield Done("tool_calls", Usage(input_tokens=input_tokens, output_tokens=len(turn)))
            return

        chunks = _chunk(turn)
        for i, chunk in enumerate(chunks):
            if self.fail_with is not None and i == self.fail_after:
                raise self.fail_with
            if self.delay_s:
                await asyncio.sleep(self.delay_s)
            yield TextDelta(chunk)
        if self.fail_with is not None and self.fail_after >= len(chunks):
            raise self.fail_with
        yield Done("stop", Usage(input_tokens=input_tokens, output_tokens=len(chunks)))

    def _next_turn(self, request: LLMRequest) -> FakeTurn:
        if self.turns:
            index = min(len(self.requests) - 1, len(self.turns) - 1)
            return self.turns[index]
        if self.demo and request.tools:
            return _demo_turn(request.messages, {t.name for t in request.tools}, request.system)
        return self.reply


def _demo_turn(messages: list[ChatMessage], available: set[str], system: str = "") -> FakeTurn:
    last = messages[-1] if messages else None
    if last is not None and last.role == "tool" and last.tool_name in _ACTION_TOOLS:
        return _demo_action_reply(last.content)
    if last is not None and last.role == "tool" and last.tool_name == "searchPolicies":
        return _demo_policy_answer(last.content)
    if last is not None and last.role == "tool":
        try:
            data = json.loads(last.content)
        except ValueError:
            data = {}
        count = len(data.get("items", [])) if isinstance(data, dict) else 0
        detail = f" I found {count} item{'s' if count != 1 else ''}." if count else ""
        return f"Here's what I found in your HR records.{detail}"
    if last is not None and last.role == "user":
        text = last.content.lower()
        action = _demo_action(text, last.content, available, system)
        if action is not None:
            return action
        if "searchPolicies" in available and any(w in text for w in _POLICY_WORDS):
            query = last.content.strip()[:300]
            return [ToolCall(f"call_{uuid.uuid4().hex[:8]}", "searchPolicies", {"query": query})]
        for keywords, tool in _DEMO_ROUTES:
            if tool in available and any(k in text for k in keywords):
                return [ToolCall(f"call_{uuid.uuid4().hex[:8]}", tool, {})]
    return DEFAULT_REPLY


_ACTION_TOOLS = {"submitLeaveRequest", "cancelLeaveRequest", "createHrRequest", "completeTask"}
_BOOK_WORDS = (
    "book ",
    "apply for",
    "request leave",
    "take leave",
    "take a day off",
    "submit leave",
)
_RAISE_WORDS = ("raise", "open a ticket", "create a ticket", "log a ticket", "report a")
_TODAY = re.compile(r"Today's date is (\d{4}-\d{2}-\d{2})")


def _demo_action(text: str, original: str, available: set[str], system: str) -> FakeTurn | None:
    """Demo mode: 'book leave' prepares annual leave for next Monday-Tuesday, 'raise a
    ticket' prepares an IT request. Enough to try the confirmation flow without a model."""
    call_id = f"call_{uuid.uuid4().hex[:8]}"
    if "submitLeaveRequest" in available and any(w in text for w in _BOOK_WORDS):
        match = _TODAY.search(system)
        today = date.fromisoformat(match.group(1)) if match else date.today()
        monday = today + timedelta(days=7 - today.weekday() + 7)  # Monday after next
        kind = "sick" if "sick" in text else "casual" if "casual" in text else "annual"
        args = {
            "leave_type": kind,
            "start_date": (today if kind == "sick" else monday).isoformat(),
            "end_date": (today if kind == "sick" else monday + timedelta(days=1)).isoformat(),
        }
        return [ToolCall(call_id, "submitLeaveRequest", args)]
    if "createHrRequest" in available and any(w in text for w in _RAISE_WORDS):
        subject = original.strip().rstrip(".?!")[:120]
        args = {"category": "IT", "title": subject if len(subject) >= 5 else "Help needed"}
        return [ToolCall(call_id, "createHrRequest", args)]
    return None


def _demo_action_reply(tool_output: str) -> str:
    try:
        data = json.loads(tool_output)
    except ValueError:
        data = {}
    if isinstance(data, dict) and data.get("status") == "awaiting_confirmation":
        return (
            f"I've prepared this for you: {data.get('summary', 'your request')}. "
            "Please check the details on the card and tap the button to confirm."
        )
    detail = data.get("detail") if isinstance(data, dict) else None
    return f"I couldn't prepare that. {detail or 'Please try again.'}"


def _demo_policy_answer(tool_output: str) -> str:
    """Quotes the first sentence of the best passage, with a citation, like a real model."""
    try:
        data = json.loads(tool_output)
        best = data["results"][0]
    except (ValueError, KeyError, IndexError, TypeError):
        return (
            "I couldn't find that in the company policies. You can raise an HR request "
            "and the People Team will help."
        )
    lines = [ln.strip("-* ").strip() for ln in str(best["text"]).splitlines()]
    sentence = next((ln for ln in lines if ln), "").split(". ")[0].rstrip(".").replace("**", "")
    return f"According to the {best['document']} ({best['section']}): {sentence} [{best['ref']}]."


def _chunk(text: str) -> Sequence[str]:
    words = text.split(" ")
    return [w if i == len(words) - 1 else w + " " for i, w in enumerate(words)]
