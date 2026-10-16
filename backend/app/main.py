"""Application factory for the Keystone AI gateway."""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.ai.llm.factory import create_llm_provider
from app.ai.tools.hr_tools import build_hr_registry
from app.api import actions, auth, chat, conversations, documents, health
from app.core.auth import DevTokenVerifier, FirebaseTokenVerifier, TokenVerifier
from app.core.config import Settings, get_settings
from app.core.errors import register_error_handlers
from app.core.logging import configure_logging
from app.core.middleware import RequestContextMiddleware
from app.db.session import Database
from app.integrations.hris import DemoHrisClient
from app.rag.factory import create_embedder
from app.rag.retriever import Retriever
from app.rag.sql_store import SqlKnowledgeStore
from app.repositories.sql import (
    SqlActionRepository,
    SqlConversationRepository,
    SqlToolAuditRepository,
    SqlUserRepository,
)
from app.services.actions import ActionService
from app.services.chat_service import AgentTools, ChatService
from app.services.titles import TitleGenerator

logger = logging.getLogger("keystone")


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    configure_logging(settings.log_level)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        logger.info(
            "startup",
            extra={
                "env": settings.app_env,
                "version": settings.app_version,
                "llm_provider": settings.llm_provider,
                "embedding_model": app.state.embedder.model,
            },
        )
        yield
        if app.state.titles is not None:
            await app.state.titles.drain()
        await app.state.db.dispose()
        for client in (app.state.llm, app.state.embedder):
            aclose = getattr(client, "aclose", None)
            if aclose is not None:
                await aclose()
        logger.info("shutdown")

    app = FastAPI(
        title="Keystone AI Gateway",
        version=settings.app_version,
        lifespan=lifespan,
        docs_url="/docs" if settings.docs_enabled else None,
        redoc_url=None,
        openapi_url="/openapi.json" if settings.docs_enabled else None,
    )
    app.state.settings = settings
    app.state.db = Database(settings)  # engine connects lazily on first use
    app.state.llm = create_llm_provider(settings)
    app.state.token_verifier = _token_verifier(settings)
    app.state.users = SqlUserRepository(app.state.db.session_factory)
    app.state.conversations = SqlConversationRepository(app.state.db.session_factory)
    app.state.embedder = create_embedder(settings)
    app.state.knowledge_store = SqlKnowledgeStore(app.state.db.session_factory)
    app.state.retriever = Retriever(
        app.state.knowledge_store,
        app.state.embedder,
        top_k=settings.rag_top_k,
        min_similarity=settings.rag_min_similarity,
    )
    app.state.titles = (
        TitleGenerator(app.state.llm, app.state.conversations)
        if settings.title_generation_enabled
        else None
    )
    # One HR system and one tool registry, shared by the chat (proposals) and the actions
    # API (confirmed changes), so both see the same data.
    hris = DemoHrisClient()  # stands in for a real HR system (Workday, BambooHR, ...)
    registry = build_hr_registry(settings.tool_timeout_s)
    app.state.action_service = ActionService(
        SqlActionRepository(app.state.db.session_factory), registry, hris
    )
    app.state.chat_service = ChatService(
        app.state.llm,
        app.state.conversations,
        history_messages=settings.chat_history_messages,
        max_output_tokens=settings.chat_max_output_tokens,
        titles=app.state.titles,
        tools=AgentTools(
            registry=registry,
            hris=hris,
            audit=SqlToolAuditRepository(app.state.db.session_factory),
            max_steps=settings.agent_max_steps,
            knowledge=app.state.retriever,
            actions=app.state.action_service,
        ),
    )

    app.add_middleware(RequestContextMiddleware)
    register_error_handlers(app)
    actions.register_action_errors(app)
    app.include_router(health.router)
    app.include_router(auth.router)
    app.include_router(chat.router)
    app.include_router(conversations.router)
    app.include_router(documents.router)
    app.include_router(actions.router)
    return app


def _token_verifier(settings: Settings) -> TokenVerifier:
    if settings.auth_mode == "firebase":
        assert settings.firebase_project_id  # enforced by Settings validation
        return FirebaseTokenVerifier(settings.firebase_project_id)
    logger.warning("auth_dev_mode", extra={"note": "dev tokens accepted; never use in production"})
    return DevTokenVerifier()


app = create_app()
