"""The LLMProvider port. Every model vendor is an adapter behind this interface."""

from collections.abc import AsyncGenerator
from typing import Protocol

from app.ai.llm.types import LLMEvent, LLMRequest


class LLMProvider(Protocol):
    """Streams a model response as `TextDelta` events, ending with exactly one `Done`.

    Implementations raise `LLMError` subclasses for expected failures, never
    vendor-specific exceptions, so callers can handle every provider the same way.
    """

    name: str
    model: str

    def stream(self, request: LLMRequest) -> AsyncGenerator[LLMEvent, None]: ...


class LLMError(Exception):
    """Base class for provider failures. `code` is shown to clients; the message is not."""

    code = "ai_error"
    retryable = True


class LLMUnavailableError(LLMError):
    code = "ai_unavailable"


class LLMTimeoutError(LLMError):
    code = "ai_timeout"


class LLMRateLimitedError(LLMError):
    code = "ai_rate_limited"

    def __init__(self, message: str = "", retry_after_s: float | None = None) -> None:
        super().__init__(message)
        self.retry_after_s = retry_after_s


class LLMConfigurationError(LLMError):
    """Misconfiguration (unknown model, bad key). Retrying won't help."""

    code = "ai_misconfigured"
    retryable = False
