"""Embedding providers: turn text into vectors whose distance reflects meaning.

- `OllamaEmbedder`: a real model running locally (default `nomic-embed-text`, 768 dims).
- `HashingEmbedder`: deterministic, offline, instant. It hashes words and word pairs into
  a fixed-size vector, so texts sharing vocabulary end up close. It captures no real
  meaning (no synonyms), but it makes tests and demos work with no model at all.

Both produce unit-length vectors, so cosine similarity is just the dot product.
"""

import hashlib
import itertools
import math
import re
from collections.abc import Sequence
from typing import Protocol

import httpx

EMBEDDING_DIMS = 768  # fixed by the database column (vector(768))


class EmbeddingError(Exception):
    """The embedding model is unavailable or answered with something unusable."""


class Embedder(Protocol):
    name: str
    model: str
    dims: int
    # Below this cosine similarity a vector match is treated as unrelated. Each model has
    # its own scale, so this lives with the model, not the retriever.
    min_similarity: float

    async def embed_documents(self, texts: Sequence[str]) -> list[list[float]]: ...

    async def embed_query(self, text: str) -> list[float]: ...


_WORD = re.compile(r"[a-z0-9]+")
STOPWORDS = frozenset(
    "a an and are as at be by can do does for from has have how i if in is it its my of on "
    "or our so that the their them there this to up was we what when where which who why "
    "will with you your me am any about after before than then into per".split()
)


def tokenize(text: str) -> list[str]:
    """Lowercase words without stopwords, with a light plural strip (days → day)."""
    words = []
    for word in _WORD.findall(text.lower()):
        if word in STOPWORDS:
            continue
        if len(word) > 4 and word.endswith("ies"):
            word = word[:-3] + "y"
        elif len(word) > 3 and word.endswith("s") and not word.endswith("ss"):
            word = word[:-1]
        words.append(word)
    return words


class HashingEmbedder:
    name = "hashing"
    model = "hashing-v1"
    dims = EMBEDDING_DIMS
    min_similarity = 0.12

    async def embed_documents(self, texts: Sequence[str]) -> list[list[float]]:
        return [self._embed(t) for t in texts]

    async def embed_query(self, text: str) -> list[float]:
        return self._embed(text)

    def _embed(self, text: str) -> list[float]:
        vector = [0.0] * self.dims
        words = tokenize(text)
        features = words + [f"{a}_{b}" for a, b in itertools.pairwise(words)]
        for feature in features:
            digest = hashlib.blake2b(feature.encode(), digest_size=8).digest()
            slot = int.from_bytes(digest[:4], "little") % self.dims
            sign = 1.0 if digest[4] & 1 else -1.0
            vector[slot] += sign * (0.5 if "_" in feature else 1.0)
        return _normalise(vector)


class OllamaEmbedder:
    name = "ollama"
    # Typical nomic-embed-text scores: related ≈ 0.6-0.8, unrelated ≈ 0.3-0.45.
    # Verify on your data with `python -m app.rag.eval`; override with RAG_MIN_SIMILARITY.
    min_similarity = 0.5

    def __init__(
        self,
        base_url: str,
        model: str = "nomic-embed-text",
        *,
        timeout_s: float = 60.0,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.model = model
        self.dims = EMBEDDING_DIMS
        self._client = client or httpx.AsyncClient(base_url=base_url, timeout=timeout_s)
        # nomic-embed-text was trained with task prefixes; using them improves retrieval.
        self._prefixed = model.startswith("nomic-embed")

    async def embed_documents(self, texts: Sequence[str]) -> list[list[float]]:
        prefix = "search_document: " if self._prefixed else ""
        vectors: list[list[float]] = []
        for start in range(0, len(texts), 16):  # small batches keep requests quick
            vectors += await self._embed([prefix + t for t in texts[start : start + 16]])
        return vectors

    async def embed_query(self, text: str) -> list[float]:
        prefix = "search_query: " if self._prefixed else ""
        return (await self._embed([prefix + text]))[0]

    async def _embed(self, inputs: list[str]) -> list[list[float]]:
        try:
            response = await self._client.post(
                "/api/embed", json={"model": self.model, "input": inputs}
            )
        except httpx.HTTPError as exc:
            raise EmbeddingError(f"Ollama unreachable: {type(exc).__name__}") from exc
        if response.status_code == 404:
            raise EmbeddingError(
                f"Embedding model '{self.model}' not found. Run: ollama pull {self.model}"
            )
        if response.status_code >= 400:
            raise EmbeddingError(f"Ollama returned HTTP {response.status_code}")
        try:
            vectors = response.json()["embeddings"]
        except (ValueError, KeyError, TypeError) as exc:
            raise EmbeddingError("Unexpected embedding response") from exc
        if len(vectors) != len(inputs) or any(len(v) != self.dims for v in vectors):
            raise EmbeddingError(f"Expected {len(inputs)} vectors of {self.dims} dimensions")
        return [_normalise([float(x) for x in v]) for v in vectors]

    async def aclose(self) -> None:
        await self._client.aclose()


def _normalise(vector: list[float]) -> list[float]:
    norm = math.sqrt(sum(x * x for x in vector))
    return [x / norm for x in vector] if norm > 0 else vector


def cosine(a: Sequence[float], b: Sequence[float]) -> float:
    """For unit vectors (all of ours) cosine similarity is the dot product."""
    return sum(x * y for x, y in zip(a, b, strict=True))


class GeminiEmbedder:
    """Gemini embeddings (free tier). `gemini-embedding-001` is asked for 768 dimensions,
    matching our vector column, and uses task types for documents vs queries."""

    name = "gemini"
    # Starting point; check with `python -m app.rag.eval` and adjust RAG_MIN_SIMILARITY.
    min_similarity = 0.5

    def __init__(
        self,
        api_key: str,
        model: str = "gemini-embedding-001",
        *,
        base_url: str = "https://generativelanguage.googleapis.com/v1beta",
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.model = model
        self.dims = EMBEDDING_DIMS
        self._client = client or httpx.AsyncClient(
            base_url=base_url, headers={"x-goog-api-key": api_key}, timeout=60.0
        )

    async def embed_documents(self, texts: Sequence[str]) -> list[list[float]]:
        vectors: list[list[float]] = []
        for start in range(0, len(texts), 100):  # the API takes up to 100 per batch
            vectors += await self._embed(list(texts[start : start + 100]), "RETRIEVAL_DOCUMENT")
        return vectors

    async def embed_query(self, text: str) -> list[float]:
        return (await self._embed([text], "RETRIEVAL_QUERY"))[0]

    async def _embed(self, texts: list[str], task: str) -> list[list[float]]:
        body = {
            "requests": [
                {
                    "model": f"models/{self.model}",
                    "content": {"parts": [{"text": t}]},
                    "taskType": task,
                    "outputDimensionality": self.dims,
                }
                for t in texts
            ]
        }
        try:
            response = await self._client.post(
                f"/models/{self.model}:batchEmbedContents", json=body
            )
        except httpx.HTTPError as exc:
            raise EmbeddingError(f"Gemini unreachable: {type(exc).__name__}") from exc
        if response.status_code >= 400:
            raise EmbeddingError(f"Gemini embeddings returned HTTP {response.status_code}")
        try:
            vectors = [e["values"] for e in response.json()["embeddings"]]
        except (ValueError, KeyError, TypeError) as exc:
            raise EmbeddingError("Unexpected embedding response") from exc
        if len(vectors) != len(texts) or any(len(v) != self.dims for v in vectors):
            raise EmbeddingError(f"Expected {len(texts)} vectors of {self.dims} dimensions")
        # Shortened (768 of 3072) vectors aren't unit length: normalise for cosine.
        return [_normalise([float(x) for x in v]) for v in vectors]

    async def aclose(self) -> None:
        await self._client.aclose()
