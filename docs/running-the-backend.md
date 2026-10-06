# Running the backend (step by step, for first-timers)

## The big picture

- The **backend** is a program that runs on your Mac and waits for requests on port **8000**. The Android app calls it the same way it would call any REST API.
- The backend stores data in **PostgreSQL** (a database) with the **pgvector** extension (used later for document search).
- **Docker** runs both of them in isolated "containers", so you don't have to install Python or Postgres yourself.
  - An **image** is a packaged program (like an APK). A **container** is a running copy of an image (like the app running).
  - **Docker Compose** starts several containers together, described in `docker-compose.yml`: here `db` (Postgres) and `api` (our FastAPI server).

## One-time setup

1. **Check your Mac's chip:** Apple menu → About This Mac → "Chip" (Apple M1/M2/M3/M4) or "Processor" (Intel).
2. **Install Docker Desktop** from https://www.docker.com/products/docker-desktop/ (choose Apple Silicon or Intel). It's free for personal use.
3. **Open Docker Desktop** and wait until it says it's running (the whale icon in the menu bar stops animating).
4. **Open Terminal:** press Cmd+Space, type "Terminal", press Enter.
5. **Check Docker works:**
   ```bash
   docker --version
   docker compose version
   ```
   Both should print a version. If you see "command not found", Docker Desktop isn't installed or isn't open.

## Start the backend

6. **Go to the project folder:**
   ```bash
   cd ~/Documents/AI-Project/keystone-ai-assistant
   ls
   ```
   You should see `backend`, `mobile`, `docs`, `docker-compose.yml`.
7. **Create your local config file** (only once):
   ```bash
   cp backend/.env.example backend/.env
   ```
   `.env` holds settings (and later API keys) for your machine only. It is in `.gitignore`, so it never goes to GitHub.
8. **Start everything:**
   ```bash
   docker compose up --build
   ```
   What happens: Docker downloads the Postgres image, builds the API image (installs the Python libraries), starts the database, waits until it is healthy, applies database migrations, then starts the API server. The first run takes a few minutes; later runs take seconds.

   You're ready when you see a log line containing `"msg": "startup"` and `Uvicorn running on http://0.0.0.0:8000`.
   **Leave this Terminal window open** — it *is* the running server, and it shows live logs.

## Check it works

9. Open a **new Terminal tab** (Cmd+T) and run:
   ```bash
   curl -i http://localhost:8000/healthz
   ```
   Expect `HTTP/1.1 200 OK`, an `x-request-id` header, and `{"status":"ok"}` — the server is alive.
   ```bash
   curl http://localhost:8000/readyz
   ```
   Expect `{"status":"ready","checks":{"database":"ok","pgvector":"0.8.x"}}` — the server can reach the database and pgvector is installed.
   ```bash
   curl http://localhost:8000/v1/meta
   curl http://localhost:8000/does-not-exist
   ```
   The first returns service info (what the app shows). The second returns a 404 in our standard error format, including the `request_id`.
10. **Use the browser:** open http://localhost:8000/docs. This is the interactive API documentation FastAPI generates automatically. Click an endpoint → "Try it out" → "Execute".
11. **Watch the logs** in the first tab. Every request prints one JSON line with method, path, status, duration and request ID — that's how you trace a request.

## Stop, restart, reset

| I want to… | Command |
| --- | --- |
| Stop the server (first tab) | `Ctrl+C` |
| Start again (no code changes) | `docker compose up` |
| Start again after code changes | `docker compose up --build` |
| Run in the background | `docker compose up -d` then `docker compose logs -f api` to watch logs |
| Stop background containers | `docker compose down` (database data is kept) |
| Wipe the database and start fresh | `docker compose down -v` |
| See what's running | `docker compose ps` |

## Connect the Android app

With the backend running, run the app on the **emulator**. It calls `http://10.0.2.2:8000`, which is the emulator's address for your Mac. The home screen should show **Connected**.
On a **real phone** connected by USB: run `adb reverse tcp:8000 tcp:8000`, and build with `-Pkeystone.apiBaseUrl=http://localhost:8000`.

## Troubleshooting

| You see | Meaning and fix |
| --- | --- |
| `Cannot connect to the Docker daemon` | Docker Desktop isn't running. Open it and wait. |
| `port is already allocated` (5432) | Another Postgres is running on your Mac. Stop it (`brew services stop postgresql`), or change `"5432:5432"` to `"5433:5432"` in `docker-compose.yml`. |
| `port is already allocated` (8000) | Something else uses port 8000. Find it with `lsof -i :8000` and quit it. |
| `/readyz` says `not_ready` | The database isn't up yet or the migration failed. Look at the first tab's logs for red errors. |
| Build fails while installing packages | Usually a network blip. Run `docker compose up --build` again. |
| App shows "Unreachable" | Backend not running, or the app is on a real phone without `adb reverse`. |

## Optional: run the tests without Docker

The unit tests don't need a database.
```bash
brew install uv        # or: curl -LsSf https://astral.sh/uv/install.sh | sh
cd backend
uv sync                # creates .venv and installs dependencies
uv run pytest          # 14 passed, 1 skipped (the skipped one needs a real DB)
uv run ruff check .    # lint
uv run mypy app        # type check
```
