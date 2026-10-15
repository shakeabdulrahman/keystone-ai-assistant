"""Opaque keyset-pagination cursors.

A cursor encodes the sort key of the last item seen (timestamp + id). Keyset
pagination stays fast on large tables and doesn't skip or repeat rows when new
items arrive between page loads, unlike OFFSET.
"""

import base64
import uuid
from datetime import datetime

from fastapi import HTTPException


def encode_cursor(ts: datetime, item_id: uuid.UUID) -> str:
    raw = f"{ts.isoformat()}|{item_id}".encode()
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def decode_cursor(cursor: str) -> tuple[datetime, uuid.UUID]:
    try:
        padded = cursor + "=" * (-len(cursor) % 4)
        ts_raw, id_raw = base64.urlsafe_b64decode(padded).decode().split("|")
        return datetime.fromisoformat(ts_raw), uuid.UUID(id_raw)
    except (ValueError, UnicodeDecodeError) as exc:
        raise HTTPException(status_code=400, detail="Invalid cursor") from exc
