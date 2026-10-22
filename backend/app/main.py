"""Application factory for the Keystone AI gateway."""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.ai.llm.factory import create_llm_provider
from app.ai.tools.hr_tools import build_hr_registry
from app.api import (
    actions,
    auth,
    chat,
    conversations,
    devices,
    documents,
    health,
    home,
    metrics,
)
from app.core.app_check import AppCheckVerifier
from app.core.auth import DevTokenVerifier, FirebaseTokenVerifier, TokenVerifier
from app.core.config import Settings, get_settings
from app.core.errors import register_error_handlers
from app.core.logging import configure_logging
from app.core.middleware import RequestContextMiddleware
from app.core.rate_limit import InMemoryRateLimiter, IpRateLimitMiddleware, Rule
from app.core.security import BodySizeLimitMiddleware, SecurityHeadersMiddleware
from app.db.session import Database
from app.integrations.demo_workflow import DemoWorkflow
from app.integrations.hris import DemoHrisClient
from app.notifications.push import FcmPushSender, LogPushSender, PushSender
from app.notifications.service import NotificationService
from app.rag.factory import create_embedder
from app.rag.retriever import Retriever
from app.rag.sql_store import SqlKnowledgeStore
from app.repositories.sql import (
    SqlActionRepository,
    SqlConversationRepository,
    SqlDeviceRepository,
    SqlToolAuditRepository,
    SqlUserRepository,
)
from app.services.actions import ActionService
from app.services.chat_service import AgentTools, ChatService
from app.services.home import HomeService
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
        app.state.demo_workflow.cancel()
        if app.state.titles is not None:
            await app.state.titles.drain()
        await app.state.db.dispose()
        for client in (app.state.llm, app.state.embedder, app.state.push):
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
    app.state.devices = SqlDeviceRepository(app.state.db.session_factory)
    app.state.push = _push_sender(settings)
    app.state.notifications = NotificationService(hris, app.state.devices, app.state.push)
    app.state.demo_workflow = DemoWorkflow(
        hris, app.state.notifications, delay_s=settings.demo_workflow_delay_s
    )
    action_repository = SqlActionRepository(app.state.db.session_factory)
    app.state.action_service = ActionService(
        action_repository, registry, hris, listeners=[app.state.demo_workflow.on_action_executed]
    )
    app.state.home_service = HomeService(hris, action_repository)
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

    # Abuse protection: one limiter, several buckets (see app/core/rate_limit.py).
    limiter = InMemoryRateLimiter() if settings.rate_limit_enabled else None
    app.state.rate_limiter = limiter
    app.state.rate_rules = {
        "user": Rule("user", settings.rate_user_per_minute, burst=60),
        "chat": Rule("chat", settings.rate_chat_per_minute, burst=settings.rate_chat_burst),
        "actions": Rule("actions", settings.rate_actions_per_minute, burst=10),
    }
    app.state.app_check = (
        AppCheckVerifier(
            settings.firebase_project_number or "",
            allowed_app_ids=frozenset(
                i.strip() for i in settings.app_check_app_ids.split(",") if i.strip()
            ),
        )
        if settings.app_check_mode != "off"
        else None
    )

    # Middleware runs outermost-last: RequestContext (request IDs, crash guard) wraps all.
    if limiter is not None:
        app.add_middleware(
            IpRateLimitMiddleware,
            limiter=limiter,
            rule=Rule("ip", settings.rate_ip_per_minute, burst=100),
            trusted_proxy_hops=settings.trusted_proxy_hops,
        )
    app.add_middleware(BodySizeLimitMiddleware, max_bytes=settings.max_request_body_bytes)
    app.add_middleware(SecurityHeadersMiddleware, hsts=settings.is_production)
    app.add_middleware(RequestContextMiddleware)
    register_error_handlers(app)
    actions.register_action_errors(app)
    app.include_router(health.router)
    app.include_router(metrics.router)
    app.include_router(auth.router)
    app.include_router(chat.router)
    app.include_router(conversations.router)
    app.include_router(documents.router)
    app.include_router(actions.router)
    app.include_router(home.router)
    app.include_router(devices.router)
    return app


def _push_sender(settings: Settings) -> PushSender:
    if settings.fcm_service_account_file:
        try:
            return FcmPushSender.from_file(settings.fcm_service_account_file)
        except (OSError, ValueError, KeyError) as exc:
            # Push is optional: a missing or malformed key must not take the whole API down.
            # Logged loudly (without the file's contents) so the misconfiguration is noticed.
            logger.error(
                "push_key_unusable",
                extra={"path": settings.fcm_service_account_file, "error_type": type(exc).__name__},
            )
            return LogPushSender()
    logger.info("push_disabled", extra={"note": "FCM_SERVICE_ACCOUNT_FILE not set; logging only"})
    return LogPushSender()


def _token_verifier(settings: Settings) -> TokenVerifier:
    if settings.auth_mode == "firebase":
        assert settings.firebase_project_id  # enforced by Settings validation
        return FirebaseTokenVerifier(settings.firebase_project_id)
    logger.warning("auth_dev_mode", extra={"note": "dev tokens accepted; never use in production"})
    return DevTokenVerifier()


app = create_app()
