"""Check the configured AI provider end to end, outside the app.

    python -m app.ai.llm.check

Sends one short question with the real tool definitions, exactly as a chat turn does, and
prints what comes back: text, tool calls, and on failure Google's (or Ollama's) own
explanation. Uses the same settings as the server (LLM_PROVIDER, GEMINI_MODEL, ...).
Prints no keys.
"""

import asyncio
import logging
import sys

from app.ai.llm.base import LLMError
from app.ai.llm.factory import create_llm_provider
from app.ai.llm.types import ChatMessage, Done, LLMRequest, TextDelta, ToolCallRequested
from app.ai.tools.hr_tools import build_hr_registry
from app.core.config import get_settings


async def main() -> int:
    logging.basicConfig(level=logging.ERROR)
    settings = get_settings()
    provider = create_llm_provider(settings)
    print(f"Provider: {provider.name} · model: {provider.model}")
    request = LLMRequest(
        system="You are Keystone, an HR assistant. Use tools for the employee's data.",
        messages=[ChatMessage("user", "How many annual leave days do I have left?")],
        tools=build_hr_registry().specs(),
    )
    try:
        async for event in provider.stream(request):
            if isinstance(event, TextDelta):
                print(f"  text: {event.text!r}")
            elif isinstance(event, ToolCallRequested):
                print(f"  tool call: {event.call.name}({event.call.arguments})")
            elif isinstance(event, Done):
                usage, reason = event.usage, event.finish_reason
                print(f"  done: {reason}, tokens {usage.input_tokens}/{usage.output_tokens}")
    except LLMError as exc:
        print(f"FAILED: {type(exc).__name__} [{exc.code}]: {exc}")
        cause = exc.__cause__
        if cause is not None:
            print(f"  cause: {type(cause).__name__}: {str(cause)[:300]}")
        return 1
    print("OK: the provider works with our tools.")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
