"""Chooses the embedding model from settings."""

from app.core.config import Settings
from app.rag.embeddings import Embedder, HashingEmbedder, OllamaEmbedder


def create_embedder(settings: Settings) -> Embedder:
    if settings.resolved_embedding_provider == "ollama":
        return OllamaEmbedder(settings.ollama_base_url, settings.ollama_embedding_model)
    return HashingEmbedder()
