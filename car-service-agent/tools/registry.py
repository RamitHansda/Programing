"""Tool definitions the ServiceLane agent can call."""

from __future__ import annotations

from datetime import date, datetime
from typing import Any, Callable, Optional

from domain.models import AppointmentStatus
from domain.store import ServiceStore


ToolFn = Callable[..., dict[str, Any]]


class ToolRegistry:
    """Named, typed tools exposed to the orchestrator."""

    def __init__(self, store: ServiceStore) -> None:
        self.store = store
        self._tools: dict[str, ToolFn] = {
            "identify_customer": self.identify_customer,
            "list_vehicles": self.list_vehicles,
            "select_vehicle": self.select_vehicle,
            "recommend_services": self.recommend_services,
            "list_packages": self.list_packages,
            "find_centers": self.find_centers,
            "find_slots": self.find_slots,
            "book_appointment": self.book_appointment,
            "get_appointment_status": self.get_appointment_status,
            "list_appointments": self.list_appointments,
            "cancel_appointment": self.cancel_appointment,
            "get_service_history": self.get_service_history,
            "estimate_cost": self.estimate_cost,
            "escalate_to_human": self.escalate_to_human,
        }

    def names(self) -> list[str]:
        return list(self._tools.keys())

    def call(self, name: str, **kwargs: Any) -> dict[str, Any]:
        if name not in self._tools:
            return {"ok": False, "error": f"Unknown tool: {name}"}
        try:
            return self._tools[name](**kwargs)
        except TypeError as exc:
            return {"ok": False, "error": f"Bad arguments for {name}: {exc}"}
        except Exception as exc:  # noqa: BLE001 — surface tool errors to agent
            return {"ok": False, "error": str(exc)}

    def schemas(self) -> list[dict[str, Any]]:
        return [
            {
                "name": "identify_customer",
                "description": "Look up a customer by name, phone, email, or customer id.",
                "parameters": {"query": "string"},
            },
            {
                "name": "list_vehicles",
                "description": "List vehicles registered to a customer.",
                "parameters": {"customer_id": "string"},
            },
            {
                "name": "select_vehicle",
                "description": "Resolve a vehicle by registration, model, or id for a customer.",
                "parameters": {"query": "string", "customer_id": "string?"},
            },
            {
                "name": "recommend_services",
                "description": "Recommend service packages from mileage, history, and symptoms.",
                "parameters": {"vehicle_id": "string", "symptoms": "string?"},
            },
            {
                "name": "list_packages",
                "description": "List all available service packages and prices.",
                "parameters": {},
            },
            {
                "name": "find_centers",
                "description": "Find service centers in a city.",
                "parameters": {"city": "string"},
            },
            {
                "name": "find_slots",
                "description": "Find available appointment slots.",
                "parameters": {
                    "center_id": "string?",
                    "city": "string?",
                    "day": "YYYY-MM-DD?",
                    "limit": "int?",
                },
            },
            {
                "name": "book_appointment",
                "description": "Book a service appointment once all slots are collected.",
                "parameters": {
                    "customer_id": "string",
                    "vehicle_id": "string",
                    "center_id": "string",
                    "package_id": "string",
                    "slot_id": "string",
                    "symptoms": "string?",
                    "notes": "string?",
                },
            },
            {
                "name": "get_appointment_status",
                "description": "Get status for an appointment id.",
                "parameters": {"appointment_id": "string"},
            },
            {
                "name": "list_appointments",
                "description": "List appointments for a customer.",
                "parameters": {"customer_id": "string"},
            },
            {
                "name": "cancel_appointment",
                "description": "Cancel a booked or in-progress appointment.",
                "parameters": {"appointment_id": "string"},
            },
            {
                "name": "get_service_history",
                "description": "Fetch past service history for a vehicle.",
                "parameters": {"vehicle_id": "string"},
            },
            {
                "name": "estimate_cost",
                "description": "Estimate cost for a package, optionally with add-ons.",
                "parameters": {"package_id": "string", "addon_package_ids": "list[string]?"},
            },
            {
                "name": "escalate_to_human",
                "description": "Hand off to a human service advisor with context.",
                "parameters": {"reason": "string", "summary": "string?"},
            },
        ]

    # ---- implementations -------------------------------------------------

    def identify_customer(self, query: str) -> dict[str, Any]:
        customer = self.store.find_customer(query)
        if not customer:
            return {
                "ok": False,
                "error": "Customer not found. Try phone, email, or full name.",
                "hint": "Demo customers: Ananya Sharma, Rahul Mehta, Priya Nair",
            }
        vehicles = [v.to_dict() for v in self.store.vehicles_for_customer(customer.id)]
        return {"ok": True, "customer": customer.to_dict(), "vehicles": vehicles}

    def list_vehicles(self, customer_id: str) -> dict[str, Any]:
        vehicles = self.store.vehicles_for_customer(customer_id)
        if not vehicles:
            return {"ok": False, "error": "No vehicles found for this customer."}
        return {"ok": True, "vehicles": [v.to_dict() for v in vehicles]}

    def select_vehicle(self, query: str, customer_id: Optional[str] = None) -> dict[str, Any]:
        vehicle = self.store.find_vehicle(query, customer_id=customer_id)
        if not vehicle:
            return {"ok": False, "error": f"No vehicle matched '{query}'."}
        return {"ok": True, "vehicle": vehicle.to_dict()}

    def recommend_services(
        self, vehicle_id: str, symptoms: Optional[str] = None
    ) -> dict[str, Any]:
        vehicle = self.store.vehicles.get(vehicle_id)
        if not vehicle:
            return {"ok": False, "error": "Vehicle not found."}
        recs = self.store.recommend_for_vehicle(vehicle, symptoms)
        return {
            "ok": True,
            "vehicle": vehicle.display_name(),
            "mileage_km": vehicle.mileage_km,
            "recommendations": [r.to_dict() for r in recs],
        }

    def list_packages(self) -> dict[str, Any]:
        return {
            "ok": True,
            "packages": [p.to_dict() for p in self.store.packages.values()],
        }

    def find_centers(self, city: str) -> dict[str, Any]:
        centers = self.store.centers_in_city(city)
        if not centers:
            return {
                "ok": False,
                "error": f"No ServiceLane centers found in {city}.",
                "available_cities": sorted({c.city for c in self.store.centers.values()}),
            }
        return {"ok": True, "centers": [c.to_dict() for c in centers]}

    def find_slots(
        self,
        center_id: Optional[str] = None,
        city: Optional[str] = None,
        day: Optional[str] = None,
        limit: int = 8,
    ) -> dict[str, Any]:
        day_obj: Optional[date] = None
        if day:
            day_obj = date.fromisoformat(day)
        slots = self.store.available_slots(
            center_id=center_id, city=city, day=day_obj, limit=limit
        )
        enriched = []
        for s in slots:
            center = self.store.centers[s.center_id]
            item = s.to_dict()
            item["center_name"] = center.name
            item["area"] = center.area
            enriched.append(item)
        return {"ok": True, "count": len(enriched), "slots": enriched}

    def book_appointment(
        self,
        customer_id: str,
        vehicle_id: str,
        center_id: str,
        package_id: str,
        slot_id: str,
        symptoms: Optional[str] = None,
        notes: str = "",
    ) -> dict[str, Any]:
        appt = self.store.book_appointment(
            customer_id=customer_id,
            vehicle_id=vehicle_id,
            center_id=center_id,
            package_id=package_id,
            slot_id=slot_id,
            symptoms=symptoms,
            notes=notes,
        )
        return {"ok": True, "appointment": self._enrich_appointment(appt)}

    def get_appointment_status(self, appointment_id: str) -> dict[str, Any]:
        appt = self.store.appointments.get(appointment_id)
        if not appt:
            return {"ok": False, "error": "Appointment not found."}
        return {"ok": True, "appointment": self._enrich_appointment(appt)}

    def list_appointments(self, customer_id: str) -> dict[str, Any]:
        appts = self.store.appointments_for_customer(customer_id)
        return {
            "ok": True,
            "appointments": [self._enrich_appointment(a) for a in appts],
        }

    def cancel_appointment(self, appointment_id: str) -> dict[str, Any]:
        appt = self.store.cancel_appointment(appointment_id)
        return {"ok": True, "appointment": self._enrich_appointment(appt)}

    def get_service_history(self, vehicle_id: str) -> dict[str, Any]:
        history = self.store.history_for_vehicle(vehicle_id)
        return {"ok": True, "history": [h.to_dict() for h in history]}

    def estimate_cost(
        self, package_id: str, addon_package_ids: Optional[list[str]] = None
    ) -> dict[str, Any]:
        pkg = self.store.packages.get(package_id)
        if not pkg:
            return {"ok": False, "error": "Package not found."}
        addons = []
        total = pkg.base_price_inr
        for aid in addon_package_ids or []:
            a = self.store.packages.get(aid)
            if a:
                addons.append({"id": a.id, "name": a.name, "price_inr": a.base_price_inr})
                total += a.base_price_inr
        return {
            "ok": True,
            "package": pkg.to_dict(),
            "addons": addons,
            "estimated_total_inr": total,
            "currency": "INR",
            "note": "Final invoice may vary after inspection.",
        }

    def escalate_to_human(self, reason: str, summary: Optional[str] = None) -> dict[str, Any]:
        ticket = f"ESC-{datetime.now().strftime('%Y%m%d%H%M%S')}"
        return {
            "ok": True,
            "escalation_id": ticket,
            "reason": reason,
            "summary": summary or reason,
            "message": "A ServiceLane advisor will call you within 15 minutes.",
        }

    def _enrich_appointment(self, appt) -> dict[str, Any]:
        data = appt.to_dict()
        customer = self.store.customers.get(appt.customer_id)
        vehicle = self.store.vehicles.get(appt.vehicle_id)
        center = self.store.centers.get(appt.center_id)
        package = self.store.packages.get(appt.package_id)
        data["customer_name"] = customer.name if customer else None
        data["vehicle"] = vehicle.display_name() if vehicle else None
        data["center_name"] = center.name if center else None
        data["package_name"] = package.name if package else None
        data["status_label"] = {
            AppointmentStatus.BOOKED: "Booked",
            AppointmentStatus.IN_PROGRESS: "In progress at workshop",
            AppointmentStatus.COMPLETED: "Completed",
            AppointmentStatus.CANCELLED: "Cancelled",
            AppointmentStatus.READY_FOR_PICKUP: "Ready for pickup",
        }[appt.status]
        return data
