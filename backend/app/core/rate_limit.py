"""Rate limiting: token buckets per user (and per IP for everything).

Why: an LLM call is the most expensive thing this server does, so one user (or a stolen
token, or a buggy client in a retry loop) must not be able to exhaust the model for
everyone, or a paid provider's budget.

Token bucket: a bucket holds up to `burst` tokens and refills at `per_minute / 60`
tokens per second; each request takes one. Short bursts are fine, sustained abuse isn't.

This in-memory limiter is per process, which is right for one instance. With several
instances, the same interface would be backed by Redis (atomic INCR/expire or a Lua
token bucket) so all instances share the counts.
"""

import json
import math
import time
from collections import OrderedDict
from collections.abc import Callable
from dataclasses import dataclass
from typing import Protocol

from starlette.types import ASGIApp, Receive, Scope, Send

from app.core.logging import request_id_var
from app.core.metrics import RATE_LIMITED


@dataclass(frozen=True)
class Rule:
    name: str
    per_minute: float
    burst: int


@dataclass(frozen=True)
class Decision:
    allowed: bool
    retry_after_s: int  # 0 when allowed
    remaining: int


class RateLimiter(Protocol):
    def hit(self, rule: Rule, key: str) -> Decision: ...


class InMemoryRateLimiter:
    def __init__(
        self, *, max_keys: int = 50_000, clock: Callable[[], float] = time.monotonic
    ) -> None:
        self._buckets: OrderedDict[tuple[str, str], tuple[float, float]] = OrderedDict()
        self._max_keys = max_keys  # bounded memory: the least recently seen keys go first
        self._clock = clock

    def hit(self, rule: Rule, key: str) -> Decision:
        now = self._clock()
        bucket_key = (rule.name, key)
        rate = rule.per_minute / 60.0
        tokens, updated = self._buckets.get(bucket_key, (float(rule.burst), now))
        tokens = min(float(rule.burst), tokens + (now - updated) * rate)
        if tokens >= 1.0:
            tokens -= 1.0
            decision = Decision(True, 0, int(tokens))
        else:
            wait = (1.0 - tokens) / rate if rate > 0 else 3600.0
            decision = Decision(False, max(1, math.ceil(wait)), 0)
        self._buckets[bucket_key] = (tokens, now)
        self._buckets.move_to_end(bucket_key)
        while len(self._buckets) > self._max_keys:
            self._buckets.popitem(last=False)
        return decision


class IpRateLimitMiddleware:
    """A coarse per-IP limit on every request (health and metrics excluded), so even
    unauthenticated floods are capped before they reach token verification."""

    _EXEMPT = ("/healthz", "/readyz", "/metrics")

    def __init__(
        self, app: ASGIApp, *, limiter: RateLimiter, rule: Rule, trusted_proxy_hops: int = 0
    ) -> None:
        self.app = app
        self.limiter = limiter
        self.rule = rule
        self.trusted_proxy_hops = trusted_proxy_hops

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or scope["path"].startswith(self._EXEMPT):
            await self.app(scope, receive, send)
            return
        ip = client_ip(scope, self.trusted_proxy_hops)
        decision = self.limiter.hit(self.rule, ip)
        if not decision.allowed:
            RATE_LIMITED.labels(self.rule.name).inc()
            await send_429(send, decision.retry_after_s)
            return
        await self.app(scope, receive, send)


def client_ip(scope: Scope, trusted_proxy_hops: int) -> str:
    """The caller's IP. Behind N trusted proxies (a cloud load balancer), it's the N-th
    address from the *right* of X-Forwarded-For: each proxy appends the address it saw,
    and anything further left was written by the client and can be forged."""
    client = scope.get("client")
    direct = client[0] if client else "unknown"
    if trusted_proxy_hops <= 0:
        return direct
    header = next((v for k, v in scope.get("headers", []) if k == b"x-forwarded-for"), b"")
    hops = [h.strip() for h in header.decode("latin-1").split(",") if h.strip()]
    return hops[-trusted_proxy_hops] if len(hops) >= trusted_proxy_hops else direct


async def send_429(send: Send, retry_after_s: int) -> None:
    body = json.dumps(
        {
            "type": "https://keystone.dev/problems/rate_limited",
            "title": "Too many requests",
            "status": 429,
            "code": "rate_limited",
            "detail": f"Too many requests. Try again in {retry_after_s} seconds.",
            "retry_after_s": retry_after_s,
            "request_id": request_id_var.get(),
        }
    ).encode()
    await send(
        {
            "type": "http.response.start",
            "status": 429,
            "headers": [
                (b"content-type", b"application/problem+json"),
                (b"retry-after", str(retry_after_s).encode()),
                (b"content-length", str(len(body)).encode()),
            ],
        }
    )
    await send({"type": "http.response.body", "body": body})


class RateLimitedError(Exception):
    def __init__(self, rule: Rule, retry_after_s: int) -> None:
        super().__init__(rule.name)
        self.rule = rule
        self.retry_after_s = retry_after_s
