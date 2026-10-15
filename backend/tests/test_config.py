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
