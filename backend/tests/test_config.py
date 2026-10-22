import pytest
from pydantic import ValidationError

from app.core.config import Settings


def test_database_url_is_secret() -> None:
    settings = Settings(database_url="postgresql+asyncpg://u:topsecret@db/keystone")  # type: ignore[arg-type]
    assert "topsecret" not in repr(settings)
    assert "topsecret" not in str(settings.model_dump())


def test_docs_disabled_in_production() -> None:
    prod = Settings(app_env="production", auth_mode="firebase", firebase_project_id="p")
    assert prod.docs_enabled is False
    assert Settings(app_env="local").docs_enabled is True


def test_dev_auth_is_refused_in_production() -> None:
    with pytest.raises(ValidationError, match="not allowed in production"):
        Settings(app_env="production", auth_mode="dev")


def test_firebase_auth_requires_project_id() -> None:
    with pytest.raises(ValidationError, match="FIREBASE_PROJECT_ID"):
        Settings(auth_mode="firebase")


def test_settings_read_from_environment(monkeypatch) -> None:  # type: ignore[no-untyped-def]
    monkeypatch.setenv("LLM_PROVIDER", "ollama")
    monkeypatch.setenv("APP_ENV", "staging")
    settings = Settings()
    assert settings.llm_provider == "ollama"
    assert settings.app_env == "staging"


def test_hosted_postgres_urls_are_normalised_for_asyncpg() -> None:
    from app.core.config import Settings, normalise_database_url

    neon = "postgres://u:p@ep-x.eu-central-1.aws.neon.tech/keystone?sslmode=require&channel_binding=require"
    assert (
        normalise_database_url(neon)
        == "postgresql+asyncpg://u:p@ep-x.eu-central-1.aws.neon.tech/keystone?ssl=require"
    )
    local = "postgresql+asyncpg://keystone:keystone@localhost:5432/keystone"
    assert normalise_database_url(local) == local
    s = Settings(database_url="postgresql://u:p@h/db", _env_file=None)
    assert s.database_url.get_secret_value() == "postgresql+asyncpg://u:p@h/db"
