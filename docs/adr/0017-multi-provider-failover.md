# ADR 0017: Chat on Groq with automatic failover to Gemini

- Status: Accepted (refines ADR 0007 and 0016)
- Date: 2026-10-29

## Context
The cloud deployment used Gemini for chat. On the free tier, the current Flash model was retired for new users (404), and its successor answered "high demand" (503) for hours. Retrying the same model and other Gemini models didn't help. One vendor was a single point of failure for the whole assistant.

## Decision
- **One OpenAI-compatible adapter** (`OpenAICompatProvider`). Groq, OpenRouter, Mistral, Together, Cerebras and OpenAI share this API, so a new vendor is configuration, not code.
- **Groq is the primary chat provider in the cloud**: fast open models with tool calling and a free tier.
- **A `FallbackProvider` tries vendors in order** (`LLM_FALLBACK_PROVIDERS=gemini`). It switches only if a provider fails *before* producing output. Switching mid-answer would garble it.
- **Embeddings stay on Gemini.** Groq has no embeddings API, and the stored vectors must come from the same model.
- **Every failure is logged with the vendor's reason** (`llm_provider_skipped`, `llm_fallback_used`), never with keys or user text.

## Consequences
- An outage or rate limit at one vendor no longer stops the assistant.
- Different models behave differently with the same prompt and tools, so answer quality can vary when failing over. The tool layer validates every argument, so a weaker model can't do anything unsafe.
- Two free keys to manage.

## Alternatives considered
- **Retrying harder on one vendor:** doesn't help with long overloads or retirements.
- **A gateway service (LiteLLM, OpenRouter) in front of all models:** a good option at scale, but another dependency. Our adapter plus the failover wrapper is around 200 lines and fully tested.
- **Self-hosting a model:** not possible on free hosting.
