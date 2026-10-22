"""Registering phones for push notifications."""

from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Request, Response
from pydantic import BaseModel, Field

from app.api.deps import CurrentUser
from app.repositories.base import DeviceRepository

router = APIRouter(prefix="/v1/devices", tags=["devices"])


def get_devices(request: Request) -> DeviceRepository:
    devices: DeviceRepository = request.app.state.devices
    return devices


Devices = Annotated[DeviceRepository, Depends(get_devices)]


class DeviceIn(BaseModel):
    token: str = Field(min_length=20, max_length=4096, description="FCM registration token")
    platform: Literal["android", "ios"] = "android"


class TokenIn(BaseModel):
    token: str = Field(min_length=20, max_length=4096)


@router.post("", status_code=204, response_class=Response)
async def register_device(body: DeviceIn, user: CurrentUser, devices: Devices) -> Response:
    """Called after sign-in and whenever the push token changes. Idempotent."""
    await devices.register(user.id, body.token, body.platform)
    return Response(status_code=204)


@router.post("/unregister", status_code=204, response_class=Response)
async def unregister_device(body: TokenIn, user: CurrentUser, devices: Devices) -> Response:
    """Called on sign-out, so the next person on this phone doesn't get your pushes.
    (A POST with a body, because tokens are long and don't belong in URLs or logs.)"""
    await devices.unregister(user.id, body.token)
    return Response(status_code=204)
