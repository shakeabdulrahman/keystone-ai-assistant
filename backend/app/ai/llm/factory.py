"""Builds the configured provider. The only place that knows concrete provider classes."""

from app.ai.llm.base import LLMProvider
from app.ai.llm.fake import FakeLLMProvider
from app.ai.llm.ollama import OllamaProvider
from app.core.config import Settings


def create_llm_provider(settings: Settings) -> LLMProvider:
    match settings.llm_provider:
        case "fake":
            # demo=True: picks tools by keyword so the app can be explored without a model.
            return FakeLLMProvider(delay_s=settings.fake_llm_delay_s, demo=True)
        case "ollama":
            return OllamaProvider(
                base_url=settings.ollama_base_url,
                model=settings.ollama_model,
                timeout_s=settings.llm_timeout_s,
            )
        case "gemini" | "groq":
            raise NotImplementedError(
                f"LLM_PROVIDER={settings.llm_provider} arrives in a later phase; use fake or ollama"
            )
