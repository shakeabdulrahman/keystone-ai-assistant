"""Failover across AI vendors: try each provider in order until one answers.

Production AI systems don't depend on a single vendor: free tiers get overloaded, paid
APIs have outages and rate limits. A provider is skipped only if it fails *before*
producing anything; once text or a tool call has reached the user, switching vendor
mid-answer would garble it, so later errors are passed on.
"""

import logging
from collections.abc import AsyncGenerator

from app.ai.llm.base import LLMError, LLMProvider
from app.ai.llm.types import LLMEvent, LLMRequest

logger = logging.getLogger("keystone.llm.fallback")


class FallbackProvider:
    def __init__(self, providers: list[LLMProvider]) -> None:
        if not providers:
            raise ValueError("FallbackProvider needs at least one provider")
        self._providers = providers
        # Reported in logs and metrics as the primary; fallbacks are logged when used.
        self.name = providers[0].name
        self.model = providers[0].model

    async def stream(self, request: LLMRequest) -> AsyncGenerator[LLMEvent, None]:
        last_error: LLMError | None = None
        for position, provider in enumerate(self._providers):
            started = False
            try:
                async for event in provider.stream(request):
                    started = True
                    yield event
                if position > 0:
                    logger.info(
                        "llm_fallback_used",
                        extra={"provider": provider.name, "model": provider.model},
                    )
                return
            except LLMError as exc:
                if started:
                    raise
                logger.warning(
                    "llm_provider_skipped",
                    extra={
                        "provider": provider.name,
                        "model": provider.model,
                        "code": exc.code,
                        "detail": str(exc)[:300],
                    },
                )
                last_error = exc
        assert last_error is not None
        raise last_error
