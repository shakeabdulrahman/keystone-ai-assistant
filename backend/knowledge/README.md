# Knowledge base

Sample HR policies for **Keystone Group**, a fictional company. The assistant answers policy questions from these files (Phase 4, RAG).

- One Markdown file per policy, with front matter: `id`, `title`, `version`, `effective`, `owner`.
- `##` headings become sections. Search finds sections, and citations point to them.
- To load changes into the database, run `uv run python -m app.rag.ingest`. Docker Compose runs it on start. Unchanged files are skipped.
- This README is not ingested.
