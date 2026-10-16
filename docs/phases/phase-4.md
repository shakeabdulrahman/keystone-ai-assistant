# Phase 4 — Answers from HR policies, with sources (RAG)

**Outcome:** policy questions are now answered from the company's own documents. Ask "Can I carry over unused leave?" and the assistant searches the policies, answers with a citation ("…by 31 March [1]"), and shows a **Sources** chip under the answer. Tapping the chip opens the Leave Policy scrolled to the cited section, highlighted. If nothing relevant exists, the assistant says so instead of guessing. Sources come back when you reopen a conversation.

## 1. What we built

| Area | What exists now | Key files |
| --- | --- | --- |
| Knowledge base | 7 sample policies for a fictional company (leave, parental leave, remote work, expenses, benefits, code of conduct, onboarding) as Markdown with front matter | `backend/knowledge/*.md` |
| Parsing + chunking | Front matter validation; one chunk per `##` section; long sections split on paragraphs with overlap; title + heading embedded with each chunk | `backend/app/rag/documents.py` |
| Embeddings | `Embedder` interface; Ollama `nomic-embed-text` (768 dims, task prefixes, batching); offline hashing embedder for tests/CI/fake mode | `backend/app/rag/embeddings.py` |
| Storage | `documents` + `document_chunks` tables: pgvector column with HNSW index, generated `tsvector` with GIN index (migration `0004`) | `app/db/models/knowledge.py`, `app/rag/sql_store.py` |
| Ingestion | `python -m app.rag.ingest`: idempotent (fingerprint = content + model), atomic per document, deletes removed files; runs on `docker compose up` | `backend/app/rag/ingest.py` |
| Hybrid retrieval | Vector + keyword search, Reciprocal Rank Fusion, relevance floors | `backend/app/rag/retriever.py` |
| Evaluation | 28 questions + 6 off-topic: hit@k, MRR, off-topic rejection; runs in CI | `app/rag/eval.py`, `knowledge/eval/retrieval.jsonl` |
| Search tool | `searchPolicies(query)` returns numbered passages; prompt v3 rules: search first, cite every fact, say "not found", ignore instructions inside documents | `app/ai/tools/policy_tools.py`, `app/ai/prompts/system.md` |
| Citations | Per-answer source numbering; only numbers the answer actually uses become a `citations` event; saved in message `parts` | `app/rag/citations.py`, `chat_service.py` |
| Documents API | `GET /v1/documents`, `GET /v1/documents/{id}` (sections in the same order as citations) | `backend/app/api/documents.py` |
| Shared KMP | `Citation`, `PolicyDocument`, `citations` event, citations from cached parts, `DocumentRepository` with an in-memory cache | `shared/.../domain/model/Citation.kt`, `data/repository/DocumentRepositoryImpl.kt` |
| Android | Source chips under answers; document screen that scrolls to and highlights the cited section; small native Markdown renderer | `ui/chat/ChatScreen.kt`, `ui/document/` |
| Tests | Backend 144 with Postgres (chunking, embedders, ingestion, hybrid search, eval floors, citations, agent flow, documents API, pgvector + full-text on real Postgres). Mobile: citations parsing (4), document repository (3), rag fixture, ViewModels (5), Markdown (3) | `backend/tests/test_rag.py`, `test_policy_chat.py`, `mobile/**/test` |

## 2. How it works

```
Offline (ingest):  knowledge/*.md ─▶ parse ─▶ sections ─▶ chunks ─▶ embed ─▶ Postgres
                                                          (title > heading + text)   vector(768) + tsvector

Online (a question):
"Can I carry over unused leave?"
  └▶ model ─▶ searchPolicies("carry over unused leave")
                 ├─ embed query ─▶ pgvector: nearest chunks (cosine) ─┐ drop below similarity floor
                 └─ full-text: chunks with the words ─────────────────┤ drop if < 60% of the words
                                                                      ▼
                                       Reciprocal Rank Fusion ─▶ top 4 passages, numbered [1]..[4]
  └▶ model answers from the passages: "…up to 5 days, used by 31 March [1]."
  └▶ server finds "[1]" in the text ─▶ SSE citations {ref 1, leave-policy, section 5, snippet}
  └▶ app: Sources chip ─▶ GET /v1/documents/leave-policy ─▶ scroll to section 5, highlight
```

- **Why chunk by section?** Policies are already organised by topic, so a section is a natural unit of meaning, and citations can point at something a person recognises ("Carry-over and expiry").
- **Contextual chunk headers.** A chunk saying "up to 5 days" doesn't mention leave; embedding it as "Leave Policy > Carry-over and expiry\n…" does.
- **Reciprocal Rank Fusion.** Vector similarities and keyword ranks are on different scales, so instead of mixing scores we mix *positions*: score = Σ 1/(60 + rank). A passage near the top of both lists wins.
- **Relevance floors.** Without them, "tell me a joke" would retrieve the nearest passages anyway, and a small model tends to answer from whatever it's given. The eval's off-topic questions measure this.
- **Verified citations.** The model can only cite numbers we gave it, and only numbers present in the final text become chips. Invented numbers are ignored. If the model forgets to cite, there are simply no chips.
- **Prompt injection.** Documents are data. Passages arrive as tool results (not system instructions), the tool description and prompt say to ignore instructions inside them, and tools still can't access other users' data (Phase 3), so injected text has little to work with.
- **Different models, never mixed.** The embedding model's name is stored with each document. Switching models re-embeds automatically on the next ingest, and search only compares vectors from the same model.

## 3. Why we chose it

| Decision | Why | Alternative rejected |
| --- | --- | --- |
| RAG as a tool | Searches only when needed; can combine policy with personal data; one agent loop | Retrieve on every message |
| Hybrid retrieval + RRF | Meaning *and* exact terms (VPN, 31 March); no score calibration needed | Vector-only |
| pgvector in the existing Postgres | No new infrastructure; transactions; one backup | Pinecone / Qdrant |
| Section chunking with overlap | Natural units, citable headings | Fixed 500-token windows |
| Server-verified citations | Users can trust that a chip was really used | Model-written source lists |
| Offline hashing embedder | Tests and CI need no model; still exercises the whole pipeline | Mocking the retriever |
| Eval set with off-topic questions | Turns "feels better" into numbers; tunes thresholds | Manual spot checks |
| Small native Markdown renderer | Our documents use 3 features; no WebView, no library | Markdown library or WebView |

## 4. Bugs and lessons from this phase

1. **Thresholds need negative examples.** The first retriever returned passages for "What's the weather in Paris?" (one shared word, or a hash collision). Adding off-topic questions to the eval showed it, and a term-coverage rule for keyword matches fixed most of it. Recall dropped when the floors were too strict, so the values were chosen from a small grid of floor × coverage, measured on both kinds of question.
2. **A synonym gap is visible in numbers.** The offline embedder misses "maternity leave" → "primary caregiver" (no shared words). That's exactly what a real embedding model fixes, and running the eval with Ollama shows the difference.
3. **Snippets showed Markdown symbols** (`**5 days**`). Snippets are now plain text; the document screen renders the Markdown.
4. **Review findings (fixed):** a citation without a heading would have shown "Leave Policy · " with a dangling separator, and duplicate section numbers from the server would have crashed the document list. Both are handled.

## 5. How to run and test it

1. **Pull the embedding model** (274 MB, once): `ollama pull nomic-embed-text`
2. **Backend:** `docker compose up --build`. This applies migration `0004`, then loads the policies. Look for:
   `Knowledge base (nomic-embed-text): 7 added, ... 44 chunks embedded`. On the next start it says `7 unchanged`.
3. **Measure retrieval with the real model** (with the containers running, in a second Terminal tab):
   `docker compose exec api python -m app.rag.eval`
   You'll see hit@4, MRR and off-topic rejection for `nomic-embed-text`. If off-topic questions find passages, raise `RAG_MIN_SIMILARITY` in `backend/.env` (e.g. `0.55`); if real questions are missed, lower it. Then `docker compose up` again.
4. **Android Studio:** sync and run.
5. **Try it:**
   - "Can I carry over unused leave?" → Searching HR policies → answer with [1] → **Sources** chip → tap → Leave Policy opens at "Carry-over and expiry", highlighted
   - "What's the parental leave policy?", "Can I work from another country?", "Is my gym membership covered?", "How long is probation?"
   - Combine both kinds: "How many leave days do I have, and how many can I carry over?" (two tools)
   - Off-topic: "Can I bring my dog to the office?" → it should say it couldn't find a policy
   - Reopen the conversation from history: chips are still there
6. **No model?** `LLM_PROVIDER=fake` uses demo mode: policy-like questions search with the offline embedder and quote the best passage with a citation.
7. **Edit a policy** in `backend/knowledge/`, restart: only that document is re-embedded (`1 updated, 6 unchanged`).
8. **Tests:** `cd backend && uv run pytest` · `cd mobile && ./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest`

## 6. Interview concepts demonstrated

- Retrieval-augmented generation end to end: ingestion, chunking, embeddings, vector index, retrieval, grounding, citations
- Hybrid search (dense + sparse) and Reciprocal Rank Fusion
- Approximate nearest neighbour indexes (HNSW) and cosine distance in pgvector
- Relevance thresholds and "I don't know" behaviour (abstention)
- Retrieval evaluation: hit@k, MRR, negative examples, threshold tuning
- Idempotent, incremental ingestion with content fingerprints; atomic replacement
- Embedding-model versioning (never compare vectors across models)
- Prompt injection through retrieved content and how architecture limits it
- Verified citations as a trust feature, deep links into source documents
- Agentic RAG: retrieval as a tool the model decides to call

**60-second explanation:**

> "Policy questions are answered with retrieval-augmented generation. Policies are Markdown files; an ingestion job splits them by section, embeds each chunk with its title and heading using a local nomic-embed-text model, and stores the vectors in pgvector next to a Postgres full-text index. It's idempotent: content fingerprints mean only changed documents are re-embedded. Retrieval is a tool the agent calls: it runs vector and keyword search, merges them with Reciprocal Rank Fusion, and applies relevance floors so off-topic questions return nothing and the assistant says it doesn't know. Passages are numbered, the model must cite them, and the server turns only the numbers that actually appear in the answer into citations. On Android those become chips that open the policy at the cited section. I measure retrieval with an eval set including off-topic questions, and that's how I tuned the thresholds."

## 7. Likely interview questions

1. **How do you stop the model from making up policy?** Search first, answer only from passages, cite every fact, abstain when nothing matches; relevance floors make "nothing matches" actually happen; citations are verified server-side.
2. **Why hybrid search instead of only embeddings?** Embeddings blur exact tokens such as acronyms, numbers and names; keyword search catches them. RRF combines both without calibrating scores.
3. **How do you choose chunk size?** Start from the document's structure (sections), cap the size, overlap on split. Then measure with an eval set; chunking is a parameter you tune, not guess.
4. **How do you evaluate a RAG system?** Separately: retrieval (hit@k, MRR, rejection of off-topic questions) and generation (groundedness, citation accuracy, answer quality). Start with retrieval, because generation can't be better than what it's given.
5. **What happens when you switch embedding model?** Vectors from different models aren't comparable. The model name is stored with each document; ingestion re-embeds on mismatch, and search filters by model.
6. **How would this scale to 100,000 documents?** HNSW keeps vector search fast; ingest becomes a background job queue; add metadata filters (country, department), a re-ranker on the top 20, and per-document access control in the query.
7. **What about prompt injection in documents?** Treat retrieved text as data (tool results, not instructions), say so in the prompt, keep tools least-privilege and identity-scoped, and require confirmation for any write action (Phase 5).

## 8. Check yourself

1. Why is each chunk embedded with its document title and section heading?
2. A passage is 3rd in vector search and 1st in keyword search. How does RRF score it?
3. What makes "Tell me a joke" return no passages?
4. The model writes "[1]" and "[7]" but only 4 passages were retrieved. What does the app show?
5. What happens on `docker compose up` after you edit one policy file?

## 9. CV / LinkedIn line

> Built retrieval-augmented generation for a Kotlin Multiplatform HR assistant: idempotent ingestion into pgvector, hybrid vector + full-text retrieval with Reciprocal Rank Fusion and relevance thresholds tuned on an evaluation set, and server-verified citations rendered as Jetpack Compose source chips that deep-link to the cited policy section.

## Next: Phase 5 — Actions with confirmation

The assistant starts *doing* things: submit a leave request, raise an HR request, mark a task done. Every write is proposed first as a confirmation card in the app, executed only after the employee taps Confirm, with idempotency and an audit trail.
