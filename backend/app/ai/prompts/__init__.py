"""Versioned prompt templates. Stored as files so changes are reviewable in diffs."""

from datetime import date
from functools import cache
from pathlib import Path

_DIR = Path(__file__).parent

SYSTEM_PROMPT_VERSION = "system-v3"


@cache
def _load(name: str) -> str:
    return (_DIR / name).read_text(encoding="utf-8")


def system_prompt(today: date) -> str:
    return _load("system.md").replace("{today}", today.isoformat())
