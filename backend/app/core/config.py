"""Typed application settings, loaded from environment variables (and `.env` locally).

Secrets are `SecretStr` so they never appear in logs, reprs or error pages.
"""

from functools import lru_cache
from typing import Literal
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

from pydantic import SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

Environment = Literal["local", "test", "staging", "production"]
LlmProviderName = Literal["fake", "ollama", "gemini"]


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
    # Gemini (cloud deployment). Key from Google AI Studio; model names change over time,
    # so check the current free models at https://ai.google.dev/gemini-api/docs/models.
    gemini_api_key: SecretStr | None = None
    gemini_model: str = "gemini-3.8-flash"
    gemini_embedding_model: str = "gemini-embedding-001"
    # Tried in order when the main model is overloaded (free-tier 503s). Comma-separated.
    gemini_fallback_models: str = ""
    # Optional: limit "thinking" tokens on models that think (0 = off, faster answers).
    gemini_thinking_budget: int | None = None

    # --- Chat limits ----------------------------------------------------------------
    chat_max_input_chars: int = 4000
    chat_history_messages: int = 20  # recent messages sent to the model as context
    chat_max_output_tokens: int = 1024
    sse_heartbeat_s: float = 15.0
    title_generation_enabled: bool = True

    # --- Tools / agent loop -------------------------------------------------------------
    agent_max_steps: int = 4  # model calls per user message (each may request tools)
    tool_timeout_s: float = 5.0

    # --- Knowledge base (RAG) ---------------------------------------------------------
    # Embeddings: "ollama" (nomic-embed-text) or "hashing" (offline, for tests and demos).
    # Unset: follow the chat provider, so LLM_PROVIDER=ollama uses Ollama for both.
    embedding_provider: Literal["ollama", "gemini", "hashing"] | None = None
    ollama_embedding_model: str = "nomic-embed-text"
    knowledge_dir: str = "knowledge"
    rag_top_k: int = 4  # passages given to the model per search
    # Override the embedder's relevance floor (cosine similarity). Tune with app.rag.eval.
    rag_min_similarity: float | None = None

    # --- Abuse protection -------------------------------------------------------------
    rate_limit_enabled: bool = True
    rate_chat_per_minute: float = 12  # model calls are the expensive part
    rate_chat_burst: int = 5
    rate_actions_per_minute: float = 20
    rate_user_per_minute: float = 120  # every authenticated request
    rate_ip_per_minute: float = 300  # every request, even unauthenticated
    # Proxies in front of the app that append to X-Forwarded-For (Render: 1). 0 = direct.
    trusted_proxy_hops: int = 0
    max_request_body_bytes: int = 64 * 1024  # chat messages are capped at 4,000 chars anyway

    # Firebase App Check: off | monitor (log only) | enforce (reject). Needs the project
    # *number* (Project settings → General), not the project ID.
    app_check_mode: Literal["off", "monitor", "enforce"] = "off"
    firebase_project_number: str | None = None
    app_check_app_ids: str = ""  # optional comma-separated allow-list of Firebase app IDs

    # --- Observability -----------------------------------------------------------------
    # Bearer token for GET /metrics. Unset: /metrics is disabled (it reveals usage patterns).
    metrics_token: SecretStr | None = None

    # --- Push notifications --------------------------------------------------------------
    # Path to a Firebase service-account key (JSON). Unset: pushes are only logged.
    # The key is a secret: mount it as a file, never commit it or put it in the image.
    fcm_service_account_file: str | None = None
    # Demo HR workflow: a "manager" approves submitted leave after this many seconds.
    # 0 turns it off.
    demo_workflow_delay_s: float = 20.0

    @field_validator("database_url", mode="before")
    @classmethod
    def _normalise_database_url(cls, value: object) -> object:
        raw = value.get_secret_value() if isinstance(value, SecretStr) else value
        return normalise_database_url(raw) if isinstance(raw, str) else value

    @model_validator(mode="after")
    def _check_auth(self) -> "Settings":
        if self.is_production and self.auth_mode == "dev":
            raise ValueError("AUTH_MODE=dev is not allowed in production")
        if self.auth_mode == "firebase" and not self.firebase_project_id:
            raise ValueError("AUTH_MODE=firebase requires FIREBASE_PROJECT_ID")
        uses_gemini = "gemini" in (self.llm_provider, self.embedding_provider)
        if uses_gemini and self.gemini_api_key is None:
            raise ValueError("LLM_PROVIDER/EMBEDDING_PROVIDER=gemini requires GEMINI_API_KEY")
        if self.app_check_mode != "off" and not self.firebase_project_number:
            raise ValueError("APP_CHECK_MODE needs FIREBASE_PROJECT_NUMBER")
        return self

    @property
    def resolved_embedding_provider(self) -> Literal["ollama", "gemini", "hashing"]:
        if self.embedding_provider is not None:
            return self.embedding_provider
        if self.llm_provider in ("ollama", "gemini"):
            return self.llm_provider
        return "hashing"

    @property
    def is_production(self) -> bool:
        return self.app_env == "production"

    @property
    def docs_enabled(self) -> bool:
        # Interactive API docs are useful locally but are attack surface in production.
        return not self.is_production


def normalise_database_url(url: str) -> str:
    """Accept the URLs hosting providers hand out (Neon, Render, Supabase:
    `postgres://…?sslmode=require&channel_binding=require`) and turn them into what
    SQLAlchemy + asyncpg expect: the `postgresql+asyncpg` scheme and `ssl=` instead of
    libpq's `sslmode=` (asyncpg rejects libpq-only options)."""
    parts = urlsplit(url)
    scheme = parts.scheme
    if scheme in ("postgres", "postgresql"):
        scheme = "postgresql+asyncpg"
    if scheme != "postgresql+asyncpg":
        return url
    query = []
    for key, val in parse_qsl(parts.query, keep_blank_values=True):
        if key == "sslmode":
            query.append(("ssl", val))
        elif key not in ("channel_binding",):
            query.append((key, val))
    return urlunsplit(parts._replace(scheme=scheme, query=urlencode(query)))


@lru_cache
def get_settings() -> Settings:
    return Settings()
