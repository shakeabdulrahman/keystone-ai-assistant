# ADR 0007: LLM providers: free tiers behind an abstraction

- Status: Accepted
- Date: 2026-10-06

## Context
There is no paid AI provider budget, and the provider must be swappable.

## Decision
Business logic depends only on LLMProvider and EmbeddingProvider interfaces. Implementations: Fake (tests), Ollama (local dev), Gemini API free tier (cloud demo), Groq free tier (second adapter). Embeddings use nomic-embed-text (768 dims) everywhere.

## Consequences
Zero cost, no rate limits during development, and a live demonstration of swapping vendors via config. Free tiers may use content for product improvement, so only synthetic data is sent.

## Alternatives considered
Paid OpenAI/Anthropic keys from day one.
