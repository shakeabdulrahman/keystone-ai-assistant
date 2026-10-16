"""Numbered sources for one answer, and the citations the answer actually used.

Every passage the assistant retrieves during a turn gets a number ([1], [2], ...), stable
across several searches in the same turn. The model is told to cite with those numbers.
After the answer, only the numbers that appear in the text become citations: the app
shows what the answer relied on, not everything that was searched.
"""

import re
import uuid
from dataclasses import dataclass
from typing import Any

from app.rag.store import ChunkRecord

# [1]  [1, 3]  [2][4]  — up to two digits, so "[2026]" in an answer isn't a citation.
_MARKER = re.compile(r"\[(\d{1,2}(?:\s*,\s*\d{1,2})*)\]")
SNIPPET_CHARS = 240


@dataclass(frozen=True)
class Citation:
    ref: int
    chunk: ChunkRecord

    def to_dict(self) -> dict[str, Any]:
        return {
            "ref": self.ref,
            "document_id": self.chunk.document_id,
            "title": self.chunk.document_title,
            "section_index": self.chunk.section_index,
            "heading": self.chunk.heading,
            "snippet": snippet(self.chunk.content),
        }


class SourceBook:
    def __init__(self) -> None:
        self._by_ref: dict[int, ChunkRecord] = {}
        self._ref_by_chunk: dict[uuid.UUID, int] = {}

    def add(self, chunk: ChunkRecord) -> int:
        ref = self._ref_by_chunk.get(chunk.id)
        if ref is None:
            ref = len(self._by_ref) + 1
            self._by_ref[ref] = chunk
            self._ref_by_chunk[chunk.id] = ref
        return ref

    def __len__(self) -> int:
        return len(self._by_ref)

    def cited(self, text: str) -> list[Citation]:
        """Sources referenced in `text`, in order of first mention. Unknown numbers are ignored."""
        seen: list[int] = []
        for match in _MARKER.finditer(text):
            for number in match.group(1).split(","):
                ref = int(number)
                if ref in self._by_ref and ref not in seen:
                    seen.append(ref)
        return [Citation(ref, self._by_ref[ref]) for ref in seen]


_MARKDOWN = re.compile(r"\*\*|__|`|^#+\s*|^\s*[-*]\s+", re.MULTILINE)


def snippet(text: str, limit: int = SNIPPET_CHARS) -> str:
    """Plain-text preview of a passage for the source chip (no Markdown symbols)."""
    flat = " ".join(_MARKDOWN.sub("", text).split())
    if len(flat) <= limit:
        return flat
    cut = flat[:limit].rsplit(" ", 1)[0]
    return cut.rstrip(",.;:") + "…"
