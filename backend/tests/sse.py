"""Minimal SSE parser for tests (mirrors what the mobile client does)."""

import json
from dataclasses import dataclass
from typing import Any


@dataclass
class Frame:
    event: str
    data: dict[str, Any]


def parse_sse(text: str) -> list[Frame]:
    frames: list[Frame] = []
    event, data_lines = "message", []
    for line in text.splitlines():
        if line == "":
            if data_lines:
                frames.append(Frame(event, json.loads("\n".join(data_lines))))
            event, data_lines = "message", []
        elif line.startswith(":"):
            continue
        elif line.startswith("event:"):
            event = line[len("event:") :].strip()
        elif line.startswith("data:"):
            data_lines.append(line[len("data:") :].strip())
    return frames
