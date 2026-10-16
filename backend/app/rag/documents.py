"""Policy documents: parsing Markdown with front matter, and splitting it into chunks.

Chunking strategy (simple, and easy to explain):
- One section per `##` heading; text before the first `##` becomes "Overview".
- A section longer than `max_chars` is split on paragraph boundaries, with the last
  paragraph repeated as overlap, so an answer that straddles the cut isn't lost.
- Each chunk is embedded together with its document title and heading ("contextual chunk
  header"), so a chunk that says only "up to 5 days" still matches "carry over leave".
"""

import hashlib
import re
from dataclasses import dataclass
from datetime import date
from pathlib import Path

MAX_CHUNK_CHARS = 1200


class DocumentFormatError(ValueError):
    pass


@dataclass(frozen=True)
class Section:
    index: int
    heading: str
    body: str  # Markdown, without the heading line


@dataclass(frozen=True)
class SourceDocument:
    id: str  # stable slug, used in URLs and citations
    title: str
    version: str
    effective: date
    owner: str
    body: str  # Markdown after the front matter

    @property
    def content_hash(self) -> str:
        raw = "\n".join([self.id, self.title, self.version, self.effective.isoformat(), self.owner])
        return hashlib.sha256((raw + "\n" + self.body).encode()).hexdigest()

    def sections(self) -> list[Section]:
        return split_sections(self.body)


@dataclass(frozen=True)
class Chunk:
    section_index: int
    chunk_index: int  # position within the section
    heading: str
    content: str

    def embedding_text(self, title: str) -> str:
        return f"{title} > {self.heading}\n{self.content}"


_FRONT_MATTER = re.compile(r"\A---\n(.*?)\n---\n", re.DOTALL)
_SLUG = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
_REQUIRED = ("id", "title", "version", "effective", "owner")


def parse_document(text: str, *, source: str = "<text>") -> SourceDocument:
    match = _FRONT_MATTER.match(text.replace("\r\n", "\n"))
    if match is None:
        raise DocumentFormatError(f"{source}: missing front matter")
    meta: dict[str, str] = {}
    for line in match.group(1).splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, sep, value = line.partition(":")
        if not sep:
            raise DocumentFormatError(f"{source}: bad front matter line {line!r}")
        meta[key.strip()] = value.strip().strip("\"'")
    missing = [k for k in _REQUIRED if not meta.get(k)]
    if missing:
        raise DocumentFormatError(f"{source}: front matter needs {', '.join(missing)}")
    if not _SLUG.match(meta["id"]):
        raise DocumentFormatError(f"{source}: id must be a lowercase slug")
    try:
        effective = date.fromisoformat(meta["effective"])
    except ValueError as exc:
        raise DocumentFormatError(f"{source}: effective must be YYYY-MM-DD") from exc
    body = text.replace("\r\n", "\n")[match.end() :].strip() + "\n"
    return SourceDocument(
        meta["id"], meta["title"], meta["version"], effective, meta["owner"], body
    )


def load_documents(directory: Path) -> list[SourceDocument]:
    """Every `*.md` in the directory except README.md. Duplicate ids are an error."""
    documents: list[SourceDocument] = []
    for path in sorted(directory.glob("*.md")):
        if path.name.lower() == "readme.md":
            continue
        documents.append(parse_document(path.read_text(encoding="utf-8"), source=path.name))
    ids = [d.id for d in documents]
    duplicates = sorted({i for i in ids if ids.count(i) > 1})
    if duplicates:
        raise DocumentFormatError(f"duplicate document ids: {', '.join(duplicates)}")
    return documents


def split_sections(body: str) -> list[Section]:
    sections: list[Section] = []
    heading = "Overview"
    lines: list[str] = []

    def flush() -> None:
        text = "\n".join(lines).strip()
        if text:
            sections.append(Section(len(sections), heading, text))

    for line in body.splitlines():
        if line.startswith("# "):  # the document title; already in front matter
            continue
        if line.startswith("## "):
            flush()
            heading, lines = line[3:].strip(), []
        else:
            lines.append(line)
    flush()
    return sections


def chunk_document(doc: SourceDocument, max_chars: int = MAX_CHUNK_CHARS) -> list[Chunk]:
    chunks: list[Chunk] = []
    for section in doc.sections():
        for i, piece in enumerate(_split_long(section.body, max_chars)):
            chunks.append(Chunk(section.index, i, section.heading, piece))
    return chunks


def _split_long(text: str, max_chars: int) -> list[str]:
    if len(text) <= max_chars:
        return [text]
    paragraphs = [p.strip() for p in text.split("\n\n") if p.strip()]
    pieces: list[str] = []
    current: list[str] = []
    for paragraph in paragraphs:
        if current and len("\n\n".join([*current, paragraph])) > max_chars:
            pieces.append("\n\n".join(current))
            current = [current[-1]]  # one paragraph of overlap
            if len("\n\n".join([*current, paragraph])) > max_chars:
                current = []
        current.append(paragraph)
    if current:
        pieces.append("\n\n".join(current))
    return pieces
