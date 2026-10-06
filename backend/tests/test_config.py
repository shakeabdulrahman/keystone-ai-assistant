from app.core.config import Settings


def test_database_url_is_secret() -> None:
    settings = Settings(database_url="postgresql+asyncpg://u:topsecret@db/keystone")  # type: ignore[arg-type]
    assert "topsecret" not in repr(settings)
    assert "topsecret" not in str(settings.model_dump())


def test_docs_disabled_in_production() -> None:
    assert Settings(app_env="production").docs_enabled is False
    assert Settings(app_env="local").docs_enabled is True


def test_settings_read_from_environment(monkeypatch) -> None:  # type: ignore[no-untyped-def]
    monkeypatch.setenv("LLM_PROVIDER", "ollama")
    monkeypatch.setenv("APP_ENV", "staging")
    settings = Settings()
    assert settings.llm_provider == "ollama"
    assert settings.app_env == "staging"
