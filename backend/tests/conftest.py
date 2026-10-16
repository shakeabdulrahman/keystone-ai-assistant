import asyncio
from collections.abc import AsyncIterator
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import date
from functools import cache
from pathlib import Path

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from app.ai.llm.fake import FakeLLMProvider
from app.ai.tools.hr_tools import build_hr_registry
from app.api.deps import get_health_checker
from app.core.auth import DevTokenVerifier
from app.core.config import Settings
from app.integrations.hris import DemoHrisClient
from app.main import create_app
from app.rag.embeddings import HashingEmbedder
from app.rag.ingest import ingest
from app.rag.retriever import Retriever
from app.rag.store import InMemoryKnowledgeStore
from app.repositories.memory import (
    InMemoryConversationRepository,
    InMemoryToolAuditRepository,
    InMemoryUserRepository,
)
from app.services.chat_service import AgentTools, ChatService
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
    audit: InMemoryToolAuditRepository
    knowledge: InMemoryKnowledgeStore


KNOWLEDGE_DIR = Path(__file__).parent.parent / "knowledge"


@cache
def knowledge_store() -> InMemoryKnowledgeStore:
    """The real policy documents, embedded once per test run with the offline embedder.

    Built in a worker thread because tests already run inside an event loop.
    """
    store = InMemoryKnowledgeStore()
    with ThreadPoolExecutor(1) as pool:
        pool.submit(asyncio.run, ingest(KNOWLEDGE_DIR, store, HashingEmbedder())).result()
    return store


TODAY = date(2026, 10, 15)  # fixed so demo HR data is identical on every run


def build_test_app(
    provider: FakeLLMProvider | None = None,
    history_messages: int = 20,
    titles: bool = False,
    max_steps: int = 4,
    retriever: Retriever | None = None,
) -> Harness:
    """The real app, wired to in-memory repositories, dev auth, demo HR data and a fake model."""
    provider = provider or FakeLLMProvider("Hello from Keystone", delay_s=0)
    app = create_app(Settings(app_env="test", log_level="WARNING", auth_mode="dev"))
    users, conversations = InMemoryUserRepository(), InMemoryConversationRepository()
    audit = InMemoryToolAuditRepository()
    title_generator = TitleGenerator(provider, conversations)
    app.state.token_verifier = DevTokenVerifier()
    app.state.users = users
    app.state.conversations = conversations
    app.state.titles = title_generator if titles else None
    knowledge = knowledge_store()
    app.state.knowledge_store = knowledge
    app.state.chat_service = ChatService(
        provider,
        conversations,
        history_messages=history_messages,
        max_output_tokens=256,
        titles=title_generator if titles else None,
        tools=AgentTools(
            registry=build_hr_registry(),
            hris=DemoHrisClient(today=TODAY),
            audit=audit,
            max_steps=max_steps,
            knowledge=retriever or Retriever(knowledge, HashingEmbedder()),
        ),
    )
    return Harness(app, provider, users, conversations, title_generator, audit, knowledge)


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
