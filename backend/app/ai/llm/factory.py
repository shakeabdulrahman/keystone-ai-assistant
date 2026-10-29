"""Builds the configured provider. The only place that knows concrete provider classes."""

from app.ai.llm.base import LLMProvider
from app.ai.llm.fake import FakeLLMProvider
from app.ai.llm.fallback import FallbackProvider
from app.ai.llm.gemini import GeminiProvider
from app.ai.llm.ollama import OllamaProvider
from app.ai.llm.openai_compat import OpenAICompatProvider
from app.core.config import Settings


def create_llm_provider(settings: Settings) -> LLMProvider:
    primary = _create(settings.llm_provider, settings)
    names = [n for n in settings.fallback_provider_names if n != settings.llm_provider]
    if not names:
        return primary
    # LLM_FALLBACK_PROVIDERS: other vendors to try when the main one can't answer.
    return FallbackProvider([primary, *(_create(n, settings) for n in names)])


def _create(name: str, settings: Settings) -> LLMProvider:
    match name:
        case "fake":
            # demo=True: picks tools by keyword so the app can be explored without a model.
            return FakeLLMProvider(delay_s=settings.fake_llm_delay_s, demo=True)
        case "ollama":
            return OllamaProvider(
                base_url=settings.ollama_base_url,
                model=settings.ollama_model,
                timeout_s=settings.llm_timeout_s,
            )
        case "gemini":
            assert settings.gemini_api_key is not None  # enforced by Settings validation
            return GeminiProvider(
                settings.gemini_api_key,
                settings.gemini_model,
                timeout_s=settings.llm_timeout_s,
                thinking_budget=settings.gemini_thinking_budget,
                fallback_models=tuple(
                    m.strip() for m in settings.gemini_fallback_models.split(",") if m.strip()
                ),
            )
        case "groq":
            assert settings.groq_api_key is not None  # enforced by Settings validation
            return OpenAICompatProvider(
                name="groq",
                base_url=settings.groq_base_url,
                api_key=settings.groq_api_key,
                model=settings.groq_model,
                timeout_s=settings.llm_timeout_s,
            )
        case _:
            raise ValueError(f"Unknown LLM provider {name!r}")
