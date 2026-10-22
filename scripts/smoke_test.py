#!/usr/bin/env python3
"""Post-deploy smoke test. Standard library only, so it runs anywhere.

    python scripts/smoke_test.py https://keystone-api.onrender.com
    METRICS_TOKEN=… ID_TOKEN=… python scripts/smoke_test.py https://…   # deeper checks

ID_TOKEN is a Firebase ID token for a test user (from the app's debug log, or the
Firebase Auth REST API). With it, the test also loads /v1/home and asks one question.
Exit code 0 = all checks passed. Nothing secret is printed.
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

FAILURES: list[str] = []


def call(base: str, path: str, *, token: str | None = None, body: dict | None = None,
         timeout: float = 90.0) -> tuple[int, dict[str, str], bytes]:
    headers = {"accept": "application/json"}
    if token:
        headers["authorization"] = f"Bearer {token}"
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["content-type"] = "application/json"
    req = urllib.request.Request(base + path, data=data, headers=headers,
                                 method="POST" if body is not None else "GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, {k.lower(): v for k, v in r.headers.items()}, r.read()
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}, e.read()


def check(name: str, ok: bool, detail: str = "") -> None:
    print(f"  {'PASS' if ok else 'FAIL'}  {name}{f'  ({detail})' if detail and not ok else ''}")
    if not ok:
        FAILURES.append(name)


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    base = sys.argv[1].rstrip("/")
    print(f"Smoke testing {base}")

    # Free instances sleep when idle; the first request wakes them (can take ~1 minute).
    start = time.monotonic()
    status, headers, _ = call(base, "/healthz", timeout=120)
    check("liveness /healthz", status == 200, f"HTTP {status}")
    print(f"        (answered in {time.monotonic() - start:.1f}s)")

    status, _, raw = call(base, "/readyz")
    check("readiness /readyz (database + pgvector)", status == 200, raw.decode()[:200])

    status, _, raw = call(base, "/v1/meta")
    meta = json.loads(raw) if status == 200 else {}
    check("environment is production", meta.get("environment") == "production", str(meta))

    check("security headers", headers.get("x-content-type-options") == "nosniff"
          and "strict-transport-security" in headers, ", ".join(sorted(headers)))
    check("API docs hidden in production", call(base, "/docs")[0] == 404)
    check("unauthenticated request refused", call(base, "/v1/home")[0] == 401)
    check("dev tokens refused", call(base, "/v1/home", token="dev:abdul")[0] == 401)

    metrics_status = call(base, "/metrics")[0]
    check("/metrics needs the token", metrics_status in (401, 404), f"HTTP {metrics_status}")
    if token := os.environ.get("METRICS_TOKEN"):
        status, _, raw = call(base, "/metrics", token=token)
        check("/metrics with token", status == 200 and b"keystone_http_requests_total" in raw)

    if id_token := os.environ.get("ID_TOKEN"):
        status, _, raw = call(base, "/v1/home", token=id_token)
        check("home dashboard", status == 200, raw.decode()[:200])
        status, _, raw = call(base, "/v1/chat", token=id_token, body={
            "client_message_id": str(uuid.uuid4()),
            "content": "How many annual leave days do I have?",
        }, timeout=120)
        text = raw.decode(errors="replace")
        check("chat answers (SSE)", status == 200 and "message.completed" in text,
              f"HTTP {status}: {text[-300:]}")

    print("All checks passed." if not FAILURES else f"{len(FAILURES)} check(s) failed.")
    return 1 if FAILURES else 0


if __name__ == "__main__":
    sys.exit(main())
