"""Typed application settings, loaded from environment variables (and `.env` locally).

Secrets are `SecretStr` so they never appear in logs, reprs or error pages.
"""

from functools import lru_cache
from typing import Literal

from pydantic import SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

Environment = Literal["local", "test", "staging", "production"]
LlmProviderName = Literal["fake", "ollama", "gemini", "groq"]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    app_name: str = "keystone"
    app_version: str = "0.1.0"
    app_env: Environment = "local"
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"

    database_url: SecretStr = SecretStr(
        "postgresql+asyncpg://keystone:keystone@localhost:5432/keystone"
    )
    database_pool_size: int = 5

    # --- Authentication -------------------------------------------------------------
    # firebase: verify Firebase ID tokens (production). dev: accept "dev:<user>" tokens and
    # treat requests without a token as a local test user. dev is refused in production.
    auth_mode: Literal["firebase", "dev"] = "dev"
    firebase_project_id: str | None = None

    # --- AI provider -------------------------------------------------------------
    llm_provider: LlmProviderName = "fake"
    llm_timeout_s: float = 120.0
    fake_llm_delay_s: float = 0.03  # makes the fake provider look like real streaming
    ollama_base_url: str = "http://localhost:11434"
    ollama_model: str = "qwen2.5:7b"

    # --- Chat limits ----------------------------------------------------------------
    chat_max_input_chars: int = 4000
    chat_history_messages: int = 20  # recent messages sent to the model as context
    chat_max_output_tokens: int = 1024
    sse_heartbeat_s: float = 15.0
    title_generation_enabled: bool = True

    # --- Tools / agent loop -------------------------------------------------------------
    agent_max_steps: int = 4  # model calls per user message (each may request tools)
    tool_timeout_s: float = 5.0

    @model_validator(mode="after")
    def _check_auth(self) -> "Settings":
        if self.is_production and self.auth_mode == "dev":
            raise ValueError("AUTH_MODE=dev is not allowed in production")
        if self.auth_mode == "firebase" and not self.firebase_project_id:
            raise ValueError("AUTH_MODE=firebase requires FIREBASE_PROJECT_ID")
        return self

    @property
    def is_production(self) -> bool:
        return self.app_env == "production"

    @property
    def docs_enabled(self) -> bool:
        # Interactive API docs are useful locally but are attack surface in production.
        return not self.is_production


@lru_cache
def get_settings() -> Settings:
    return Settings()
