"""Chooses the embedding model from settings."""

from app.core.config import Settings
from app.rag.embeddings import Embedder, GeminiEmbedder, HashingEmbedder, OllamaEmbedder


def create_embedder(settings: Settings) -> Embedder:
    match settings.resolved_embedding_provider:
        case "ollama":
            return OllamaEmbedder(settings.ollama_base_url, settings.ollama_embedding_model)
        case "gemini":
            assert settings.gemini_api_key is not None  # enforced by Settings validation
            return GeminiEmbedder(
                settings.gemini_api_key.get_secret_value(), settings.gemini_embedding_model
            )
        case _:
            return HashingEmbedder()
