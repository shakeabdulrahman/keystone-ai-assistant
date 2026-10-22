"""GET /v1/home: the dashboard, assembled server-side in one request."""

from typing import Annotated, Any

from fastapi import APIRouter, Depends, Request

from app.api.deps import CurrentUser
from app.services.home import HomeService

router = APIRouter(prefix="/v1", tags=["home"])


def get_home_service(request: Request) -> HomeService:
    service: HomeService = request.app.state.home_service
    return service


@router.get("/home")
async def home(
    user: CurrentUser, service: Annotated[HomeService, Depends(get_home_service)]
) -> dict[str, Any]:
    return await service.build(user)
