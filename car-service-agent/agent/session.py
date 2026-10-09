"""Agent session memory and booking workflow state."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any, Optional
from uuid import uuid4


@dataclass
class SessionState:
    """Mutable per-conversation state the agent updates as it collects slots."""

    session_id: str = field(default_factory=lambda: f"sess_{uuid4().hex[:10]}")
    customer_id: Optional[str] = None
    customer_name: Optional[str] = None
    vehicle_id: Optional[str] = None
    package_id: Optional[str] = None
    center_id: Optional[str] = None
    slot_id: Optional[str] = None
    city: Optional[str] = None
    symptoms: Optional[str] = None
    last_appointment_id: Optional[str] = None
    pending_confirmation: bool = False
    messages: list[dict[str, str]] = field(default_factory=list)
    tool_trace: list[dict[str, Any]] = field(default_factory=list)

    def remember(self, role: str, content: str) -> None:
        self.messages.append({"role": role, "content": content})

    def record_tool(self, name: str, args: dict[str, Any], result: Any) -> None:
        self.tool_trace.append({"tool": name, "args": args, "result": result})

    def booking_ready(self) -> bool:
        return all([self.customer_id, self.vehicle_id, self.package_id, self.center_id, self.slot_id])

    def snapshot(self) -> dict[str, Any]:
        data = asdict(self)
        # Keep response payload lean
        data["message_count"] = len(self.messages)
        data.pop("messages", None)
        return data


class SessionManager:
    def __init__(self) -> None:
        self._sessions: dict[str, SessionState] = {}

    def get(self, session_id: Optional[str] = None) -> SessionState:
        if session_id and session_id in self._sessions:
            return self._sessions[session_id]
        state = SessionState(session_id=session_id or f"sess_{uuid4().hex[:10]}")
        self._sessions[state.session_id] = state
        return state

    def reset(self, session_id: str) -> SessionState:
        state = SessionState(session_id=session_id)
        self._sessions[session_id] = state
        return state


SESSIONS = SessionManager()
