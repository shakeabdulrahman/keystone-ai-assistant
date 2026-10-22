"""One place that tells a user something happened: in the app, and on their phones."""

import logging
import uuid

from app.core.metrics import PUSHES
from app.integrations.hris import HrisClient
from app.notifications.push import PushMessage, PushSender
from app.repositories.base import DeviceRepository

logger = logging.getLogger("keystone.notifications")


class NotificationService:
    def __init__(self, hris: HrisClient, devices: DeviceRepository, push: PushSender) -> None:
        self._hris = hris
        self._devices = devices
        self._push = push

    async def notify(
        self,
        user_id: uuid.UUID,
        *,
        kind: str,
        ref: str,
        detail_title: str,
        detail_body: str,
        push_body: str,
    ) -> None:
        """`detail_*` goes to the in-app notification list (behind sign-in).
        `push_body` is the deliberately vague lock-screen text."""
        await self._hris.add_notification(user_id, detail_title, detail_body)
        tokens = await self._devices.tokens_for(user_id)
        if not tokens:
            return
        result = await self._push.send(
            tokens, PushMessage(title="Keystone", body=push_body, data={"kind": kind, "ref": ref})
        )
        PUSHES.labels("sent").inc(result.sent)
        PUSHES.labels("stale").inc(len(result.stale_tokens))
        failed = len(tokens) - result.sent - len(result.stale_tokens)
        if failed > 0:
            PUSHES.labels("error").inc(failed)
        if result.stale_tokens:
            await self._devices.delete_tokens(result.stale_tokens)
            logger.info("push_tokens_pruned", extra={"count": len(result.stale_tokens)})
