"""GET /metrics for Prometheus, behind a bearer token.

Metrics reveal usage patterns (traffic, errors, which tools people use), so they aren't
public. Disabled entirely when METRICS_TOKEN isn't set.
"""

import hmac
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, Response

from app.api.deps import get_settings_dep
from app.core.config import Settings
from app.core.metrics import render

router = APIRouter(tags=["ops"])


@router.get("/metrics", include_in_schema=False)
async def metrics(
    request: Request, settings: Annotated[Settings, Depends(get_settings_dep)]
) -> Response:
    if settings.metrics_token is None:
        raise HTTPException(status_code=404)
    expected = f"Bearer {settings.metrics_token.get_secret_value()}"
    # Constant-time comparison: no timing hints about the token.
    if not hmac.compare_digest(request.headers.get("authorization", ""), expected):
        raise HTTPException(status_code=401, headers={"WWW-Authenticate": "Bearer"})
    body, content_type = render()
    return Response(body, media_type=content_type)
