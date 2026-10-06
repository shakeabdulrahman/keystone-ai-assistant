"""Liveness, readiness and service metadata endpoints."""

import logging
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Response
from pydantic import BaseModel

from app.api.deps import HealthChecker, get_health_checker, get_settings_dep
from app.core.config import Settings

router = APIRouter(tags=["ops"])
logger = logging.getLogger("keystone.health")


class LivenessResponse(BaseModel):
    status: Literal["ok"] = "ok"


class ReadinessResponse(BaseModel):
    status: Literal["ready", "not_ready"]
    checks: dict[str, str]


class MetaResponse(BaseModel):
    name: str
    version: str
    environment: str
    api_version: Literal["v1"] = "v1"


@router.get("/healthz", response_model=LivenessResponse)
async def healthz() -> LivenessResponse:
    """Liveness: the process is up. Never touches dependencies, so it stays cheap."""
    return LivenessResponse()


@router.get(
    "/readyz",
    response_model=ReadinessResponse,
    responses={503: {"model": ReadinessResponse}},
)
async def readyz(
    response: Response, checker: Annotated[HealthChecker, Depends(get_health_checker)]
) -> ReadinessResponse:
    """Readiness: dependencies are reachable. Load balancers stop routing when this fails."""
    try:
        checks = await checker.check()
    except Exception as exc:
        # Log the cause; the client only learns that the database is unavailable.
        logger.warning("readiness_failed", extra={"error_type": type(exc).__name__})
        response.status_code = 503
        return ReadinessResponse(status="not_ready", checks={"database": "error"})

    ready = checks.get("database") == "ok" and checks.get("pgvector") != "missing"
    if not ready:
        response.status_code = 503
    return ReadinessResponse(status="ready" if ready else "not_ready", checks=checks)


@router.get("/v1/meta", response_model=MetaResponse)
async def meta(settings: Annotated[Settings, Depends(get_settings_dep)]) -> MetaResponse:
    """Non-sensitive service info. The mobile app uses it to confirm connectivity."""
    return MetaResponse(
        name=settings.app_name, version=settings.app_version, environment=settings.app_env
    )
