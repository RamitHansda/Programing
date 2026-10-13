"""ServiceLane agent orchestrator — plans, calls tools, and replies."""

from __future__ import annotations

from typing import Any, Optional

from agent.intents import Intent, ParsedUtterance, parse_utterance
from agent.session import SessionState
from domain.store import ServiceStore
from tools.registry import ToolRegistry


class ServiceAgent:
    """
    Car-servicing AI agent.

    Uses a ReAct-style loop:
      observe utterance → parse intent → call tools → update session → respond

    Runs fully offline with a deterministic planner. When OPENAI_API_KEY is set,
    an optional LLM can rewrite the final reply for tone (tools remain authoritative).
    """

    def __init__(self, store: ServiceStore, tools: Optional[ToolRegistry] = None) -> None:
        self.store = store
        self.tools = tools or ToolRegistry(store)
        self._last_slots: list[dict[str, Any]] = []

    def handle(self, message: str, state: SessionState) -> dict[str, Any]:
        state.remember("user", message)
        parsed = parse_utterance(message)
        self._absorb_entities(parsed, state)

        # If the user just picked a slot and all booking fields are ready, propose.
        if (
            parsed.slot_hint
            and state.booking_ready()
            and not state.pending_confirmation
            and parsed.intent in (Intent.FIND_SLOTS, Intent.BOOK_SERVICE, Intent.CONFIRM, Intent.UNKNOWN)
        ):
            reply = self._propose_booking(state)
            state.remember("assistant", reply["message"])
            return {
                "message": reply["message"],
                "session_id": state.session_id,
                "state": state.snapshot(),
                "intent": parsed.intent.value,
                "tool_trace": list(state.tool_trace[-8:]),
                "suggestions": reply.get("suggestions", []),
            }

        # Confirmation of a pending booking
        if state.pending_confirmation and parsed.intent in (Intent.CONFIRM, Intent.DENY):
            reply = self._handle_confirmation(parsed, state)
        elif (
            parsed.intent in (Intent.CONFIRM, Intent.BOOK_SERVICE)
            and state.booking_ready()
            and not state.pending_confirmation
        ):
            # All slots already collected (possibly across turns) → show confirm card
            reply = self._propose_booking(state)
        else:
            reply = self._dispatch(parsed, state)

        state.remember("assistant", reply["message"])
        return {
            "message": reply["message"],
            "session_id": state.session_id,
            "state": state.snapshot(),
            "intent": parsed.intent.value,
            "tool_trace": list(state.tool_trace[-8:]),
            "suggestions": reply.get("suggestions", []),
        }

    # ---- entity absorption -----------------------------------------------

    def _absorb_entities(self, parsed: ParsedUtterance, state: SessionState) -> None:
        if parsed.symptoms:
            state.symptoms = parsed.symptoms
        if parsed.city:
            state.city = parsed.city
        if parsed.package_query:
            state.package_id = parsed.package_query
        if parsed.center_query:
            state.center_id = parsed.center_query
        if parsed.appointment_id:
            state.last_appointment_id = parsed.appointment_id

        if parsed.customer_query and not state.customer_id:
            result = self._tool(state, "identify_customer", query=parsed.customer_query)
            if result.get("ok"):
                cust = result["customer"]
                state.customer_id = cust["id"]
                state.customer_name = cust["name"]
                state.city = state.city or cust["city"]
                vehicles = result.get("vehicles") or []
                if len(vehicles) == 1:
                    state.vehicle_id = vehicles[0]["id"]

        if parsed.vehicle_query:
            result = self._tool(
                state,
                "select_vehicle",
                query=parsed.vehicle_query,
                customer_id=state.customer_id,
            )
            if result.get("ok"):
                state.vehicle_id = result["vehicle"]["id"]

        if parsed.slot_hint and self._last_slots:
            chosen = self._match_slot_hint(parsed.slot_hint, self._last_slots)
            if chosen:
                state.slot_id = chosen["slot_id"]
                state.center_id = chosen["center_id"]

    def _match_slot_hint(self, hint: str, slots: list[dict[str, Any]]) -> Optional[dict[str, Any]]:
        if hint.isdigit():
            idx = int(hint) - 1
            if 0 <= idx < len(slots):
                return slots[idx]
        hint_l = hint.lower().replace(" ", "")
        for s in slots:
            label = s.get("label", "").lower().replace(" ", "")
            if hint_l in label or hint_l.replace("am", "").replace("pm", "") in label:
                # Prefer exact hour match when possible
                if hint_l in label:
                    return s
        for s in slots:
            label = s.get("label", "").lower().replace(" ", "")
            hour = hint_l.replace("am", "").replace("pm", "")
            if hour and hour in label:
                return s
        return None

    # ---- dispatch --------------------------------------------------------

    def _dispatch(self, parsed: ParsedUtterance, state: SessionState) -> dict[str, Any]:
        intent = parsed.intent
        if intent == Intent.GREETING:
            return self._greeting(state)
        if intent == Intent.HELP:
            return self._help()
        if intent == Intent.IDENTIFY:
            return self._identify(parsed, state)
        if intent == Intent.LIST_PACKAGES:
            return self._list_packages(state)
        if intent == Intent.RECOMMEND:
            return self._recommend(state)
        if intent == Intent.FIND_SLOTS:
            return self._find_slots(state)
        if intent == Intent.BOOK_SERVICE:
            return self._book_flow(parsed, state)
        if intent == Intent.CHECK_STATUS:
            return self._check_status(parsed, state)
        if intent == Intent.LIST_APPOINTMENTS:
            return self._list_appointments(state)
        if intent == Intent.CANCEL:
            return self._cancel(parsed, state)
        if intent == Intent.HISTORY:
            return self._history(state)
        if intent == Intent.ESTIMATE:
            return self._estimate(state)
        if intent == Intent.ESCALATE:
            return self._escalate(parsed, state)
        if intent == Intent.CONFIRM:
            if state.booking_ready():
                return self._propose_booking(state)
            return {
                "message": "Nothing is waiting for confirmation yet. Tell me what you'd like to book.",
                "suggestions": ["Book a service", "Check status", "Recommend a service"],
            }
        return self._fallback(parsed, state)

    # ---- intent handlers -------------------------------------------------

    def _greeting(self, state: SessionState) -> dict[str, Any]:
        if state.customer_name:
            msg = (
                f"Welcome back, {state.customer_name}. I'm ServiceLane — your car service agent. "
                "I can book servicing, recommend packages, check workshop status, or pull history."
            )
        else:
            msg = (
                "Hi — I'm **ServiceLane**, your car service AI agent. "
                "I can identify your account, recommend the right service, pick a workshop slot, "
                "and book it end-to-end.\n\n"
                "Try: *I am Ananya Sharma* or *Book a service for my City*."
            )
        return {
            "message": msg,
            "suggestions": [
                "I am Ananya Sharma",
                "Book a service",
                "What services do you offer?",
                "Check status for appt_demo_rahul",
            ],
        }

    def _help(self) -> dict[str, Any]:
        return {
            "message": (
                "Here's what I can do for you:\n"
                "• Identify your account (name / phone / email)\n"
                "• Recommend service from mileage & symptoms\n"
                "• Show packages & price estimates\n"
                "• Find nearby centers & open slots\n"
                "• Book, track, or cancel appointments\n"
                "• Pull vehicle service history\n"
                "• Escalate to a human advisor\n\n"
                "Demo customers: **Ananya Sharma**, **Rahul Mehta**, **Priya Nair**."
            ),
            "suggestions": [
                "I am Ananya Sharma",
                "Book a service",
                "My brakes are squeaking",
                "Talk to an advisor",
            ],
        }

    def _identify(self, parsed: ParsedUtterance, state: SessionState) -> dict[str, Any]:
        if not state.customer_id:
            query = parsed.customer_query or parsed.raw
            result = self._tool(state, "identify_customer", query=query)
            if not result.get("ok"):
                return {
                    "message": result.get("error", "I couldn't find that customer.")
                    + f"\n{result.get('hint', '')}",
                    "suggestions": ["Ananya Sharma", "Rahul Mehta", "Priya Nair"],
                }
            cust = result["customer"]
            state.customer_id = cust["id"]
            state.customer_name = cust["name"]
            state.city = state.city or cust["city"]
            vehicles = result.get("vehicles") or []
            if len(vehicles) == 1:
                state.vehicle_id = vehicles[0]["id"]

        vehicles = self._tool(state, "list_vehicles", customer_id=state.customer_id)
        lines = [
            f"Found you, **{state.customer_name}** ({state.city}).",
            "Vehicles on your account:",
        ]
        suggestions = []
        for v in vehicles.get("vehicles", []):
            lines.append(
                f"• {v['year']} {v['make']} {v['model']} — {v['registration']} "
                f"({v['mileage_km']:,} km)"
            )
            suggestions.append(f"Service my {v['model']}")
        lines.append("\nWhich vehicle should I help with, or shall I recommend a service?")
        suggestions.extend(["Recommend a service", "Book a service"])
        return {"message": "\n".join(lines), "suggestions": suggestions[:4]}

    def _list_packages(self, state: SessionState) -> dict[str, Any]:
        result = self._tool(state, "list_packages")
        lines = ["ServiceLane packages:"]
        for p in result["packages"]:
            lines.append(
                f"• **{p['name']}** — ₹{p['base_price_inr']:,} · {p['duration_hours']}h — {p['description']}"
            )
        lines.append("\nTell me a package name, or ask me to recommend based on your car.")
        return {
            "message": "\n".join(lines),
            "suggestions": ["Recommend a service", "Book comprehensive service", "How much is AC service?"],
        }

    def _ensure_customer_vehicle(self, state: SessionState) -> Optional[str]:
        if not state.customer_id:
            return "First, tell me who you are (name, phone, or email). Demo: *I am Ananya Sharma*."
        if not state.vehicle_id:
            vehicles = self._tool(state, "list_vehicles", customer_id=state.customer_id).get("vehicles", [])
            if not vehicles:
                return "I don't see a vehicle on your account."
            if len(vehicles) == 1:
                state.vehicle_id = vehicles[0]["id"]
                return None
            names = ", ".join(f"{v['model']} ({v['registration']})" for v in vehicles)
            return f"Which vehicle? You have: {names}."
        return None

    def _recommend(self, state: SessionState) -> dict[str, Any]:
        missing = self._ensure_customer_vehicle(state)
        if missing:
            return {
                "message": missing,
                "suggestions": ["I am Ananya Sharma", "I am Priya Nair"],
            }
        result = self._tool(
            state,
            "recommend_services",
            vehicle_id=state.vehicle_id,
            symptoms=state.symptoms,
        )
        lines = [
            f"Recommendations for **{result['vehicle']}** ({result['mileage_km']:,} km):",
        ]
        suggestions = []
        for r in result["recommendations"]:
            lines.append(
                f"• **{r['package_name']}** — ₹{r['estimated_cost_inr']:,} · "
                f"{r['urgency']} urgency — {r['reason']}"
            )
            suggestions.append(f"Book {r['package_name']}")
        if not state.package_id and result["recommendations"]:
            state.package_id = result["recommendations"][0]["package_id"]
        lines.append("\nWant me to book the top recommendation?")
        suggestions.append("Show available slots")
        return {"message": "\n".join(lines), "suggestions": suggestions[:4]}

    def _find_slots(self, state: SessionState) -> dict[str, Any]:
        city = state.city
        if not city and state.customer_id:
            cust = self.store.customers.get(state.customer_id)
            city = cust.city if cust else None
        if not city and not state.center_id:
            return {
                "message": "Which city should I search? We currently cover **Bengaluru** and **Mumbai**.",
                "suggestions": ["Bengaluru", "Mumbai"],
            }
        result = self._tool(
            state,
            "find_slots",
            center_id=state.center_id,
            city=city,
            limit=6,
        )
        if not result.get("slots"):
            return {
                "message": "No open slots in that area right now. Try another center or city.",
                "suggestions": ["Koramangala", "Indiranagar", "Mumbai"],
            }
        self._last_slots = result["slots"]
        lines = ["Open slots:"]
        suggestions = []
        for i, s in enumerate(result["slots"], start=1):
            lines.append(f"{i}. {s['label']} — {s['center_name']} ({s['area']}, {s['bay']})")
            if i <= 3:
                suggestions.append(f"Book slot {i}")
        lines.append("\nReply with a slot number (e.g. *slot 1*) or ask me to book.")
        return {"message": "\n".join(lines), "suggestions": suggestions}

    def _book_flow(self, parsed: ParsedUtterance, state: SessionState) -> dict[str, Any]:
        missing = self._ensure_customer_vehicle(state)
        if missing:
            return {"message": missing, "suggestions": ["I am Ananya Sharma", "I am Rahul Mehta"]}

        # Package
        if not state.package_id:
            rec = self._tool(
                state,
                "recommend_services",
                vehicle_id=state.vehicle_id,
                symptoms=state.symptoms,
            )
            if rec.get("recommendations"):
                top = rec["recommendations"][0]
                state.package_id = top["package_id"]
                return {
                    "message": (
                        f"I'd suggest **{top['package_name']}** (₹{top['estimated_cost_inr']:,}) — "
                        f"{top['reason']}\n\nShall I use this package and find slots?"
                    ),
                    "suggestions": [
                        "Yes, find slots",
                        "Show all packages",
                        f"Book {top['package_name']}",
                    ],
                }
            return {
                "message": "Which package should I book? Say *show packages* to browse.",
                "suggestions": ["Show packages", "Recommend a service"],
            }

        # Center / slots
        if not state.slot_id:
            if not state.center_id and not state.city:
                cust = self.store.customers.get(state.customer_id)
                state.city = cust.city if cust else "Bengaluru"
            slot_reply = self._find_slots(state)
            if state.slot_id:
                # user may have provided slot in same turn via absorb
                pass
            else:
                pkg = self.store.packages[state.package_id]
                prefix = f"Booking **{pkg.name}** for your vehicle.\n\n"
                return {
                    "message": prefix + slot_reply["message"],
                    "suggestions": slot_reply.get("suggestions", []),
                }

        # Ready to confirm
        return self._propose_booking(state)

    def _propose_booking(self, state: SessionState) -> dict[str, Any]:
        if not state.booking_ready():
            return self._book_flow(parse_utterance("book"), state)

        cust = self.store.customers[state.customer_id]
        veh = self.store.vehicles[state.vehicle_id]
        pkg = self.store.packages[state.package_id]
        center = self.store.centers[state.center_id]
        slot = self.store.slots[state.slot_id]
        state.pending_confirmation = True
        msg = (
            "Please confirm this booking:\n"
            f"• Customer: **{cust.name}**\n"
            f"• Vehicle: **{veh.display_name()}**\n"
            f"• Package: **{pkg.name}** (₹{pkg.base_price_inr:,})\n"
            f"• Center: **{center.name}** — {center.address}\n"
            f"• Slot: **{slot.start.strftime('%a %d %b, %I:%M %p')}**\n"
        )
        if state.symptoms:
            msg += f"• Notes: {state.symptoms}\n"
        msg += "\nReply **yes** to confirm or **no** to cancel."
        return {"message": msg, "suggestions": ["Yes, confirm", "No", "Different slot"]}

    def _handle_confirmation(self, parsed: ParsedUtterance, state: SessionState) -> dict[str, Any]:
        if parsed.intent == Intent.DENY:
            state.pending_confirmation = False
            state.slot_id = None
            return {
                "message": "Okay — booking discarded. Want a different slot or package?",
                "suggestions": ["Show available slots", "Recommend a service", "Talk to an advisor"],
            }
        if not state.booking_ready():
            state.pending_confirmation = False
            return {
                "message": "I'm missing booking details. Let's restart the booking flow.",
                "suggestions": ["Book a service"],
            }
        result = self._tool(
            state,
            "book_appointment",
            customer_id=state.customer_id,
            vehicle_id=state.vehicle_id,
            center_id=state.center_id,
            package_id=state.package_id,
            slot_id=state.slot_id,
            symptoms=state.symptoms,
        )
        state.pending_confirmation = False
        if not result.get("ok"):
            state.slot_id = None
            return {
                "message": f"Couldn't complete the booking: {result.get('error')}. Pick another slot?",
                "suggestions": ["Show available slots"],
            }
        appt = result["appointment"]
        state.last_appointment_id = appt["id"]
        state.slot_id = None  # consumed
        return {
            "message": (
                f"Booked. Your appointment id is **{appt['id']}**.\n"
                f"• {appt['package_name']} for {appt['vehicle']}\n"
                f"• {appt['center_name']} at {appt['scheduled_start'].replace('T', ' ')}\n"
                f"• Estimated cost ₹{appt['estimated_cost_inr']:,}\n\n"
                "I'll keep this ready for status checks anytime."
            ),
            "suggestions": [
                f"Status for {appt['id']}",
                "Book another service",
                "Service history",
            ],
        }

    def _check_status(self, parsed: ParsedUtterance, state: SessionState) -> dict[str, Any]:
        appt_id = parsed.appointment_id or state.last_appointment_id
        if not appt_id and state.customer_id:
            listed = self._tool(state, "list_appointments", customer_id=state.customer_id)
            appts = listed.get("appointments") or []
            active = [a for a in appts if a["status"] in ("booked", "in_progress", "ready_for_pickup")]
            if len(active) == 1:
                appt_id = active[0]["id"]
            elif active:
                lines = ["You have a few active appointments:"]
                for a in active:
                    lines.append(
                        f"• **{a['id']}** — {a['package_name']} · {a['status_label']} · {a['scheduled_start']}"
                    )
                lines.append("\nTell me an appointment id to inspect.")
                return {
                    "message": "\n".join(lines),
                    "suggestions": [a["id"] for a in active[:3]],
                }
        if not appt_id:
            return {
                "message": "Share an appointment id (e.g. *appt_demo_rahul*), or identify yourself first.",
                "suggestions": ["Check status for appt_demo_rahul", "I am Rahul Mehta"],
            }
        result = self._tool(state, "get_appointment_status", appointment_id=appt_id)
        if not result.get("ok"):
            return {"message": result.get("error", "Not found."), "suggestions": []}
        a = result["appointment"]
        state.last_appointment_id = a["id"]
        msg = (
            f"Appointment **{a['id']}** — {a['status_label']}\n"
            f"• Vehicle: {a['vehicle']}\n"
            f"• Package: {a['package_name']}\n"
            f"• Center: {a['center_name']}\n"
            f"• When: {a['scheduled_start'].replace('T', ' ')}\n"
            f"• Estimate: ₹{a['estimated_cost_inr']:,}"
        )
        if a.get("notes"):
            msg += f"\n• Workshop notes: {a['notes']}"
        return {
            "message": msg,
            "suggestions": ["Cancel this appointment", "Talk to an advisor"],
        }

    def _list_appointments(self, state: SessionState) -> dict[str, Any]:
        if not state.customer_id:
            return {
                "message": "Identify yourself first so I can list your appointments.",
                "suggestions": ["I am Ananya Sharma", "I am Rahul Mehta"],
            }
        result = self._tool(state, "list_appointments", customer_id=state.customer_id)
        appts = result.get("appointments") or []
        if not appts:
            return {
                "message": "No appointments on file yet. Want to book one?",
                "suggestions": ["Book a service"],
            }
        lines = ["Your appointments:"]
        suggestions = []
        for a in appts:
            lines.append(
                f"• **{a['id']}** — {a['package_name']} · {a['status_label']} · "
                f"{a['scheduled_start'].replace('T', ' ')}"
            )
            suggestions.append(f"Status for {a['id']}")
        return {"message": "\n".join(lines), "suggestions": suggestions[:4]}

    def _cancel(self, parsed: ParsedUtterance, state: SessionState) -> dict[str, Any]:
        appt_id = parsed.appointment_id or state.last_appointment_id
        if not appt_id:
            return {
                "message": "Which appointment should I cancel? Share the appointment id.",
                "suggestions": ["Show my appointments"],
            }
        result = self._tool(state, "cancel_appointment", appointment_id=appt_id)
        if not result.get("ok"):
            return {"message": result.get("error", "Cancel failed."), "suggestions": []}
        a = result["appointment"]
        return {
            "message": f"Cancelled **{a['id']}** ({a['package_name']} for {a['vehicle']}). Slot released.",
            "suggestions": ["Book a service", "Show my appointments"],
        }

    def _history(self, state: SessionState) -> dict[str, Any]:
        missing = self._ensure_customer_vehicle(state)
        if missing:
            return {"message": missing, "suggestions": ["I am Ananya Sharma"]}
        result = self._tool(state, "get_service_history", vehicle_id=state.vehicle_id)
        history = result.get("history") or []
        if not history:
            return {
                "message": "No prior service history on file for this vehicle.",
                "suggestions": ["Recommend a service", "Book a service"],
            }
        veh = self.store.vehicles[state.vehicle_id]
        lines = [f"Service history for **{veh.display_name()}**:"]
        for h in history:
            lines.append(
                f"• {h['service_date']} — {h['package_name']} at {h['center_name']} "
                f"(₹{h['cost_inr']:,}, {h['mileage_km']:,} km)\n  {h['summary']}"
            )
        return {
            "message": "\n".join(lines),
            "suggestions": ["Recommend a service", "Book a service"],
        }

    def _estimate(self, state: SessionState) -> dict[str, Any]:
        if not state.package_id:
            return {
                "message": "Which package should I price? Say a name or ask for recommendations.",
                "suggestions": ["Show packages", "Recommend a service", "How much is AC service?"],
            }
        result = self._tool(state, "estimate_cost", package_id=state.package_id)
        if not result.get("ok"):
            return {"message": result.get("error", "Could not estimate."), "suggestions": []}
        pkg = result["package"]
        return {
            "message": (
                f"**{pkg['name']}** estimate: **₹{result['estimated_total_inr']:,}** "
                f"({pkg['duration_hours']}h).\n{result['note']}"
            ),
            "suggestions": ["Book this package", "Show packages"],
        }

    def _escalate(self, parsed: ParsedUtterance, state: SessionState) -> dict[str, Any]:
        summary = (
            f"Customer={state.customer_name or 'unknown'}; "
            f"vehicle={state.vehicle_id}; package={state.package_id}; "
            f"symptoms={state.symptoms}; last_msg={parsed.raw}"
        )
        result = self._tool(
            state,
            "escalate_to_human",
            reason=parsed.raw or "Customer requested human advisor",
            summary=summary,
        )
        return {
            "message": (
                f"I've raised **{result['escalation_id']}** to a ServiceLane advisor. "
                f"{result['message']}"
            ),
            "suggestions": ["Book a service", "Check status"],
        }

    def _fallback(self, parsed: ParsedUtterance, state: SessionState) -> dict[str, Any]:
        # If mid-booking, nudge forward
        if state.customer_id and state.vehicle_id and not state.slot_id:
            return self._book_flow(parsed, state)
        if state.customer_id and not state.vehicle_id:
            return self._identify(parsed, state)
        return {
            "message": (
                "I can help with booking, recommendations, status, history, or pricing. "
                "Try *Book a service* or *I am Ananya Sharma*."
            ),
            "suggestions": [
                "I am Ananya Sharma",
                "Book a service",
                "What can you do?",
                "Check status for appt_demo_rahul",
            ],
        }

    # ---- tool helper -----------------------------------------------------

    def _tool(self, state: SessionState, name: str, **kwargs: Any) -> dict[str, Any]:
        # Drop None kwargs for cleaner traces
        clean = {k: v for k, v in kwargs.items() if v is not None}
        result = self.tools.call(name, **clean)
        state.record_tool(name, clean, result)
        return result
