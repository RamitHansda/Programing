"""FastAPI app exposing the ServiceLane car service AI agent."""

from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from agent.orchestrator import ServiceAgent
from agent.session import SESSIONS
from domain.store import STORE
from tools.registry import ToolRegistry

ROOT = Path(__file__).resolve().parent
STATIC = ROOT / "static"

app = FastAPI(
    title="ServiceLane — Car Service AI Agent",
    description="Conversational agent that books, tracks, and advises on car servicing.",
    version="1.0.0",
)

agent = ServiceAgent(STORE, ToolRegistry(STORE))

if STATIC.exists():
    app.mount("/static", StaticFiles(directory=STATIC), name="static")


class ChatRequest(BaseModel):
    message: str = Field(..., min_length=1, max_length=2000)
    session_id: str | None = None


class ChatResponse(BaseModel):
    message: str
    session_id: str
    intent: str
    state: dict
    suggestions: list[str]
    tool_trace: list[dict]


@app.get("/")
def index() -> FileResponse:
    index_path = STATIC / "index.html"
    if not index_path.exists():
        raise HTTPException(status_code=404, detail="UI not found")
    return FileResponse(index_path)


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "agent": "ServiceLane",
        "customers": len(STORE.customers),
        "centers": len(STORE.centers),
        "open_slots": sum(1 for s in STORE.slots.values() if s.available),
    }


@app.post("/api/chat", response_model=ChatResponse)
def chat(req: ChatRequest) -> ChatResponse:
    state = SESSIONS.get(req.session_id)
    result = agent.handle(req.message.strip(), state)
    return ChatResponse(
        message=result["message"],
        session_id=result["session_id"],
        intent=result["intent"],
        state=result["state"],
        suggestions=result.get("suggestions") or [],
        tool_trace=result.get("tool_trace") or [],
    )


@app.post("/api/session/reset")
def reset_session(session_id: str | None = None) -> dict:
    if not session_id:
        state = SESSIONS.get()
        return {"session_id": state.session_id, "reset": True}
    state = SESSIONS.reset(session_id)
    return {"session_id": state.session_id, "reset": True}


@app.get("/api/tools")
def list_tools() -> dict:
    return {"tools": ToolRegistry(STORE).schemas()}


@app.get("/api/demo/customers")
def demo_customers() -> dict:
    return {
        "customers": [
            {
                **c.to_dict(),
                "vehicles": [v.display_name() for v in STORE.vehicles_for_customer(c.id)],
            }
            for c in STORE.customers.values()
        ]
    }
