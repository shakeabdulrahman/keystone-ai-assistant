"""Tool registry: what the model may call, and the one safe way to run it.

Every call goes through `ToolRegistry.execute`, which:
  1. rejects unknown tools,
  2. validates the model's arguments against the tool's Pydantic model,
  3. runs the handler with the *server's* view of the user (`ToolContext`), never an
     identity taken from the model's arguments,
  4. enforces a timeout and turns every failure into a structured error the model can
     read and recover from (instead of crashing the conversation),
  5. measures latency for the audit log and metrics.

Write tools (Phase 5) never change anything when the model calls them. Their handler only
*prepares* a proposal (validated, with the exact details to show the user). The change
runs later through `run_confirmed`, and only after the user taps Confirm in the app.
"""

import asyncio
import json
import logging
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import date
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, ValidationError

from app.ai.llm.types import ToolCall, ToolSpec
from app.integrations.hris import HrisClient
from app.rag.citations import SourceBook
from app.rag.retriever import Retriever
from app.repositories.base import UserRecord

logger = logging.getLogger("keystone.tools")

MAX_RESULT_CHARS = 12_000  # keeps tool output from flooding the model's context window


class ToolArgs(BaseModel):
    """Base for tool arguments. Unknown fields (e.g. a model-invented `user_id`) are ignored."""

    model_config = ConfigDict(extra="ignore")


class NoArgs(ToolArgs):
    pass


class ToolRejected(Exception):
    """A business rule says no ("only 3 annual leave days left"). The message is written for
    the employee, so it is safe to pass to the model and to show in the app."""

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


@dataclass(frozen=True)
class ToolContext:
    user: UserRecord
    hris: HrisClient
    today: date
    knowledge: Retriever | None = None
    # Shared by every tool call in one answer, so source numbers stay unique.
    sources: SourceBook = field(default_factory=SourceBook)


ToolHandler = Callable[[Any, ToolContext], Awaitable[dict[str, Any]]]

# A write tool's proposal: what the confirmation card shows.
#   {"title": "Submit annual leave", "fields": [{"label": ..., "value": ...}],
#    "confirm_label": "Submit request"}
# and its result after confirmation: {"reference": "LR-1050", "message": "..."}


@dataclass(frozen=True)
class Tool:
    name: str
    description: str  # written for the model: when to use it, what it returns
    label: str  # written for people: shown while it runs ("Checking your leave balance")
    ui: str  # which card the app renders for the result
    args_model: type[ToolArgs]
    handler: ToolHandler  # read tools: the data. Write tools: the proposal to confirm.
    execute: ToolHandler | None = None  # write tools only: runs after the user confirms

    @property
    def read_only(self) -> bool:
        return self.execute is None

    def spec(self) -> ToolSpec:
        return ToolSpec(self.name, self.description, _clean_schema(self.args_model))


@dataclass(frozen=True)
class ToolExecution:
    call_id: str
    tool: str
    label: str
    status: Literal["ok", "error"]
    ui: str | None
    data: dict[str, Any]
    latency_ms: int
    arguments: dict[str, Any]
    error_code: str | None = None
    # Write tools: the validated arguments to run with after confirmation. Frozen here, so
    # the user confirms exactly what they see.
    pending_arguments: dict[str, Any] | None = None

    @property
    def requires_confirmation(self) -> bool:
        return self.pending_arguments is not None

    def for_model(self) -> str:
        """What the model sees: compact JSON, either the data or a readable error."""
        if self.status == "ok":
            return json.dumps(self.data, separators=(",", ":"), default=str)
        return json.dumps({"error": self.error_code, "detail": self.data.get("message", "")})


class ToolRegistry:
    def __init__(self, tools: list[Tool], timeout_s: float = 5.0) -> None:
        self._tools = {t.name: t for t in tools}
        self._timeout_s = timeout_s

    def specs(self) -> list[ToolSpec]:
        return [t.spec() for t in self._tools.values()]

    def get(self, name: str) -> Tool | None:
        return self._tools.get(name)

    def label_for(self, name: str) -> str:
        tool = self._tools.get(name)
        return tool.label if tool else "Working on it"

    async def execute(self, call: ToolCall, ctx: ToolContext) -> ToolExecution:
        started = time.perf_counter()
        tool = self._tools.get(call.name)

        def result(
            status: Literal["ok", "error"],
            data: dict[str, Any],
            error: str | None = None,
            pending: dict[str, Any] | None = None,
        ) -> ToolExecution:
            return ToolExecution(
                call_id=call.id,
                tool=call.name,
                label=tool.label if tool else "Working on it",
                status=status,
                ui=tool.ui if (tool and status == "ok") else None,
                data=data,
                latency_ms=round((time.perf_counter() - started) * 1000),
                arguments=call.arguments,
                error_code=error,
                pending_arguments=pending,
            )

        if tool is None:
            known = ", ".join(sorted(self._tools))
            return result(
                "error",
                {"message": f"No tool named '{call.name}'. Use one of: {known}."},
                "unknown_tool",
            )
        try:
            args = tool.args_model.model_validate(call.arguments)
        except ValidationError as exc:
            problems = "; ".join(
                f"{'.'.join(str(p) for p in e['loc'])}: {e['msg']}" for e in exc.errors()
            )
            return result(
                "error", {"message": f"Invalid arguments: {problems}"}, "invalid_arguments"
            )
        try:
            async with asyncio.timeout(self._timeout_s):
                data = await tool.handler(args, ctx)
        except ToolRejected as exc:
            return result("error", {"message": exc.message}, "rejected")
        except TimeoutError:
            logger.warning("tool_timeout", extra={"tool": tool.name})
            return result(
                "error", {"message": "The HR system took too long to answer."}, "tool_timeout"
            )
        except Exception:
            logger.exception("tool_failed", extra={"tool": tool.name})
            return result("error", {"message": "The HR system returned an error."}, "tool_failed")

        if not tool.read_only:
            return result("ok", data, pending=args.model_dump(mode="json"))
        if len(json.dumps(data, default=str)) > MAX_RESULT_CHARS:
            data = _truncate(data)
        return result("ok", data)

    async def run_confirmed(
        self, name: str, arguments: dict[str, Any], ctx: ToolContext
    ) -> dict[str, Any]:
        """Runs a write tool the user confirmed. Re-checks the rules first (the balance may
        have changed since the proposal). Raises ToolRejected, TimeoutError or the error."""
        tool = self._tools.get(name)
        if tool is None or tool.execute is None:
            raise ToolRejected("This action is no longer available.")
        args = tool.args_model.model_validate(arguments)
        async with asyncio.timeout(self._timeout_s):
            await tool.handler(args, ctx)  # re-validate: raises ToolRejected if stale
            return await tool.execute(args, ctx)


def _clean_schema(model: type[BaseModel]) -> dict[str, Any]:
    """Pydantic's JSON Schema, minus noise that confuses small models (titles)."""
    schema = model.model_json_schema()

    def strip(node: Any) -> Any:
        if isinstance(node, dict):
            # Only *metadata* titles (strings). A property that is itself called "title"
            # (e.g. an HR request's subject) has a schema object as its value and must stay.
            return {
                k: strip(v) for k, v in node.items() if not (k == "title" and isinstance(v, str))
            }
        if isinstance(node, list):
            return [strip(v) for v in node]
        return node

    cleaned: dict[str, Any] = strip(schema)
    cleaned.setdefault("properties", {})
    cleaned["type"] = "object"
    return cleaned


def _truncate(data: dict[str, Any]) -> dict[str, Any]:
    items = data.get("items")
    if isinstance(items, list):
        kept = list(items)
        while kept and len(json.dumps({**data, "items": kept}, default=str)) > MAX_RESULT_CHARS:
            kept.pop()
        return {**data, "items": kept, "truncated": True}
    return {"truncated": True}
