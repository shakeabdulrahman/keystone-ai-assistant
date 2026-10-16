"""Policy documents, so the app can open a citation at the section it points to."""

from datetime import date, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel

from app.api.deps import CurrentUser
from app.rag.documents import split_sections
from app.rag.store import KnowledgeStore, StoredDocument

router = APIRouter(prefix="/v1/documents", tags=["documents"])


def get_knowledge_store(request: Request) -> KnowledgeStore:
    store: KnowledgeStore = request.app.state.knowledge_store
    return store


Knowledge = Annotated[KnowledgeStore, Depends(get_knowledge_store)]


class DocumentSummaryOut(BaseModel):
    id: str
    title: str
    version: str
    effective: date
    owner: str
    updated_at: datetime

    @classmethod
    def of(cls, d: StoredDocument) -> "DocumentSummaryOut":
        return cls(
            id=d.id,
            title=d.title,
            version=d.version,
            effective=d.effective,
            owner=d.owner,
            updated_at=d.updated_at,
        )


class SectionOut(BaseModel):
    index: int  # matches `section_index` in citations
    heading: str
    body: str  # Markdown


class DocumentOut(DocumentSummaryOut):
    sections: list[SectionOut]


class DocumentListOut(BaseModel):
    items: list[DocumentSummaryOut]


@router.get("")
async def list_documents(_: CurrentUser, store: Knowledge) -> DocumentListOut:
    return DocumentListOut(items=[DocumentSummaryOut.of(d) for d in await store.list_documents()])


@router.get("/{document_id}")
async def get_document(document_id: str, _: CurrentUser, store: Knowledge) -> DocumentOut:
    doc = await store.get_document(document_id)
    if doc is None:
        raise HTTPException(status_code=404, detail="Document not found")
    # Same parser as ingestion, so section indexes line up with citations.
    sections = [
        SectionOut(index=s.index, heading=s.heading, body=s.body) for s in split_sections(doc.body)
    ]
    return DocumentOut(**DocumentSummaryOut.of(doc).model_dump(), sections=sections)
