"""Shared FastAPI dependencies. Tests override these instead of patching globals."""

from typing import Protocol

from fastapi import Request

from app.core.config import Settings


class HealthChecker(Protocol):
    async def check(self) -> dict[str, str]: ...


def get_settings_dep(request: Request) -> Settings:
    settings: Settings = request.app.state.settings
    return settings


def get_health_checker(request: Request) -> HealthChecker:
    checker: HealthChecker = request.app.state.db
    return checker
