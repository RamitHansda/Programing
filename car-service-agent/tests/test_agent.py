"""Tests for ServiceLane car service AI agent."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from agent.intents import Intent, parse_utterance
from agent.orchestrator import ServiceAgent
from agent.session import SessionState
from domain.store import ServiceStore
from tools.registry import ToolRegistry


@pytest.fixture()
def store() -> ServiceStore:
    return ServiceStore()


@pytest.fixture()
def agent(store: ServiceStore) -> ServiceAgent:
    return ServiceAgent(store, ToolRegistry(store))


def test_parse_book_intent():
    parsed = parse_utterance("I need to book a service for my City in Bangalore")
    assert parsed.intent == Intent.BOOK_SERVICE
    assert parsed.vehicle_query == "city"
    assert parsed.city == "Bengaluru"


def test_parse_status_with_id():
    parsed = parse_utterance("Check status for appt_demo_rahul")
    assert parsed.intent == Intent.CHECK_STATUS
    assert parsed.appointment_id == "appt_demo_rahul"


def test_identify_and_recommend(agent: ServiceAgent):
    state = SessionState()
    r1 = agent.handle("I am Ananya Sharma", state)
    assert "Ananya" in r1["message"]
    assert state.customer_id == "cust_ananya"

    r2 = agent.handle("Recommend a service for my City", state)
    assert "Recommendations" in r2["message"] or "recommend" in r2["message"].lower()
    assert state.vehicle_id == "veh_city"
    assert any(t["tool"] == "recommend_services" for t in r2["tool_trace"])


def test_end_to_end_booking(agent: ServiceAgent, store: ServiceStore):
    state = SessionState()
    agent.handle("I am Priya Nair", state)
    agent.handle("My brakes are squeaking", state)
    # Force package + find slots
    r_slots = agent.handle("Book a service", state)
    assert "slot" in r_slots["message"].lower() or "suggest" in r_slots["message"].lower()

    # Ensure we have slots listed; pick first
    slots = store.available_slots(city="Bengaluru", limit=1)
    assert slots
    state.package_id = state.package_id or "pkg_brake"
    state.center_id = slots[0].center_id
    state.slot_id = slots[0].slot_id

    propose = agent.handle("book it", state)
    # pending confirmation path
    if state.pending_confirmation or "confirm" in propose["message"].lower():
        final = agent.handle("yes", state)
    else:
        # If already proposed via book flow
        state.pending_confirmation = True
        final = agent.handle("yes", state)

    assert "Booked" in final["message"] or "appointment" in final["message"].lower()
    assert state.last_appointment_id
    appt = store.appointments[state.last_appointment_id]
    assert appt.customer_id == "cust_priya"
    assert appt.status.value == "booked"


def test_status_lookup(agent: ServiceAgent):
    state = SessionState()
    result = agent.handle("Check status for appt_demo_rahul", state)
    assert "appt_demo_rahul" in result["message"]
    assert "In progress" in result["message"] or "in_progress" in result["message"].lower()


def test_cancel_appointment(agent: ServiceAgent, store: ServiceStore):
    state = SessionState()
    agent.handle("I am Rahul Mehta", state)
    # Book a fresh one first
    slots = store.available_slots(center_id="ctr_andheri", limit=1)
    assert slots
    booked = store.book_appointment(
        customer_id="cust_rahul",
        vehicle_id="veh_nexon",
        center_id="ctr_andheri",
        package_id="pkg_basic",
        slot_id=slots[0].slot_id,
    )
    result = agent.handle(f"Cancel {booked.id}", state)
    assert "Cancelled" in result["message"]
    assert store.appointments[booked.id].status.value == "cancelled"


def test_tool_estimate(store: ServiceStore):
    tools = ToolRegistry(store)
    result = tools.call("estimate_cost", package_id="pkg_ac", addon_package_ids=["pkg_detail"])
    assert result["ok"] is True
    assert result["estimated_total_inr"] == 3999 + 2499


def test_escalate(agent: ServiceAgent):
    state = SessionState()
    result = agent.handle("I want to talk to a human advisor", state)
    assert "ESC-" in result["message"]
