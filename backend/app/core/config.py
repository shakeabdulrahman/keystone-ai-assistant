"""Typed application settings, loaded from environment variables (and `.env` locally).

Secrets are `SecretStr` so they never appear in logs, reprs or error pages.
"""

from functools import lru_cache
from typing import Literal

from pydantic import SecretStr
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

    # Selected at startup; implementations arrive in Phase 1.
    llm_provider: LlmProviderName = "fake"

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
