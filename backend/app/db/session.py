"""Async database engine and session management (SQLAlchemy 2 + asyncpg)."""

from collections.abc import AsyncIterator

from sqlalchemy import text
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.core.config import Settings


class Database:
    """Owns the connection pool. One instance per process, created at startup."""

    def __init__(self, settings: Settings) -> None:
        self.engine: AsyncEngine = create_async_engine(
            settings.database_url.get_secret_value(),
            pool_size=settings.database_pool_size,
            pool_pre_ping=True,  # transparently replace dropped connections
        )
        self.session_factory = async_sessionmaker(self.engine, expire_on_commit=False)

    async def session(self) -> AsyncIterator[AsyncSession]:
        async with self.session_factory() as session:
            yield session

    async def check(self) -> dict[str, str]:
        """Readiness probe: can we query, and is pgvector installed?"""
        async with self.engine.connect() as conn:
            await conn.execute(text("SELECT 1"))
            version = await conn.scalar(
                text("SELECT extversion FROM pg_extension WHERE extname = 'vector'")
            )
        return {"database": "ok", "pgvector": str(version) if version else "missing"}

    async def dispose(self) -> None:
        await self.engine.dispose()
