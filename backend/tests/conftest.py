from collections.abc import AsyncIterator
from dataclasses import dataclass

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from app.ai.llm.fake import FakeLLMProvider
from app.api.deps import get_health_checker
from app.core.auth import DevTokenVerifier
from app.core.config import Settings
from app.main import create_app
from app.repositories.memory import InMemoryConversationRepository, InMemoryUserRepository
from app.services.chat_service import ChatService
from app.services.titles import TitleGenerator


class FakeHealthChecker:
    """Stands in for the real database so unit tests need no Postgres."""

    def __init__(self, result: dict[str, str] | None = None, error: Exception | None = None):
        self.result = result or {"database": "ok", "pgvector": "0.8.0"}
        self.error = error

    async def check(self) -> dict[str, str]:
        if self.error:
            raise self.error
        return self.result


@dataclass
class Harness:
    app: FastAPI
    provider: FakeLLMProvider
    users: InMemoryUserRepository
    conversations: InMemoryConversationRepository
    titles: TitleGenerator


def build_test_app(
    provider: FakeLLMProvider | None = None, history_messages: int = 20, titles: bool = False
) -> Harness:
    """The real app, wired to in-memory repositories, dev auth and a fake model."""
    provider = provider or FakeLLMProvider("Hello from Keystone", delay_s=0)
    app = create_app(Settings(app_env="test", log_level="WARNING", auth_mode="dev"))
    users, conversations = InMemoryUserRepository(), InMemoryConversationRepository()
    title_generator = TitleGenerator(provider, conversations)
    app.state.token_verifier = DevTokenVerifier()
    app.state.users = users
    app.state.conversations = conversations
    app.state.titles = title_generator if titles else None
    app.state.chat_service = ChatService(
        provider,
        conversations,
        history_messages=history_messages,
        max_output_tokens=256,
        titles=title_generator if titles else None,
    )
    return Harness(app, provider, users, conversations, title_generator)


@pytest.fixture
def settings() -> Settings:
    return Settings(app_env="test", log_level="WARNING")


@pytest.fixture
def app(settings: Settings) -> FastAPI:
    return create_app(settings)


@pytest.fixture
async def client(app: FastAPI) -> AsyncIterator[AsyncClient]:
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        yield c


def use_health_checker(app: FastAPI, checker: FakeHealthChecker) -> None:
    app.dependency_overrides[get_health_checker] = lambda: checker


def as_user(uid: str, email: str = "") -> dict[str, str]:
    """Dev-mode auth header for a given test user."""
    return {"Authorization": f"Bearer dev:{uid}" + (f":{email}" if email else "")}
