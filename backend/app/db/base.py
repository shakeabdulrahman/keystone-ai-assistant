"""Declarative base for ORM models. Tables arrive from Phase 2 onwards."""

from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    pass
