"""SSE helpers: keep-alive heartbeats around an async stream."""

import asyncio
import contextlib
from collections.abc import AsyncIterator

import anyio
from fastapi.responses import StreamingResponse
from starlette.types import Receive, Scope, Send

from app.ai.events import SSE_KEEPALIVE


async def with_heartbeat(source: AsyncIterator[str], interval_s: float) -> AsyncIterator[str]:
    """Yield items from `source`, inserting an SSE comment whenever it is quiet for `interval_s`.

    Why: while a local model loads, no bytes flow for many seconds. Proxies, load
    balancers and mobile networks drop idle connections; heartbeats keep them open.
    The pending `__anext__` is never cancelled by the timeout, only by shutdown.
    """
    iterator = aiter(source)
    pending: asyncio.Future[str] = asyncio.ensure_future(anext(iterator))
    try:
        while True:
            done, _ = await asyncio.wait({pending}, timeout=interval_s)
            if not done:
                yield SSE_KEEPALIVE
                continue
            try:
                item = pending.result()
            except StopAsyncIteration:
                return
            yield item
            pending = asyncio.ensure_future(anext(iterator))
    finally:
        # Client gone or stream finished: cancel the in-flight step so the source's own
        # cleanup runs (it saves the partial answer and closes the model connection).
        #
        # The shield matters. On disconnect the server cancels this task through an anyio
        # cancel scope, which re-cancels *every* await here. Without the shield, the awaits
        # below are cancelled instantly, cleanup never finishes, and the model keeps
        # generating tokens nobody will read (found in an end-to-end test, not in unit tests).
        with anyio.CancelScope(shield=True):
            pending.cancel()
            with contextlib.suppress(asyncio.CancelledError, StopAsyncIteration):
                await pending
            aclose = getattr(source, "aclose", None)
            if aclose is not None:
                await aclose()


class ClosingStreamingResponse(StreamingResponse):
    """A StreamingResponse that always closes its body iterator.

    A client can disconnect two ways from the server's point of view:
      1. while we're waiting for the model: the response task is cancelled;
      2. while we're writing a chunk: `send` fails and Starlette simply stops iterating.
    In case 2 Starlette abandons the async generator without closing it, so none of our
    cleanup runs (no partial answer saved, model connection left open) until garbage
    collection, which may be never. Closing it explicitly makes both paths identical.
    """

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        try:
            await super().__call__(scope, receive, send)
        finally:
            aclose = getattr(self.body_iterator, "aclose", None)
            if aclose is not None:
                with anyio.CancelScope(shield=True):
                    await aclose()
