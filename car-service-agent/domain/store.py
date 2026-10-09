"""In-memory domain store with seeded demo customers, vehicles, centers, and slots."""

from __future__ import annotations

from datetime import date, datetime, timedelta
from threading import Lock
from typing import Optional
from uuid import uuid4

from domain.models import (
    Appointment,
    AppointmentStatus,
    Customer,
    Recommendation,
    ServiceCategory,
    ServiceCenter,
    ServiceHistoryEntry,
    ServicePackage,
    TimeSlot,
    Urgency,
    Vehicle,
)


def _id(prefix: str) -> str:
    return f"{prefix}_{uuid4().hex[:8]}"


class ServiceStore:
    """Thread-safe in-memory store used by agent tools."""

    def __init__(self) -> None:
        self._lock = Lock()
        self.customers: dict[str, Customer] = {}
        self.vehicles: dict[str, Vehicle] = {}
        self.centers: dict[str, ServiceCenter] = {}
        self.packages: dict[str, ServicePackage] = {}
        self.slots: dict[str, TimeSlot] = {}
        self.appointments: dict[str, Appointment] = {}
        self.history: list[ServiceHistoryEntry] = []
        self._seed()

    def _seed(self) -> None:
        customers = [
            Customer("cust_ananya", "Ananya Sharma", "+919876543210", "ananya@example.com", "Bengaluru"),
            Customer("cust_rahul", "Rahul Mehta", "+919811122233", "rahul@example.com", "Mumbai"),
            Customer("cust_priya", "Priya Nair", "+919900112233", "priya@example.com", "Bengaluru"),
        ]
        for c in customers:
            self.customers[c.id] = c

        vehicles = [
            Vehicle(
                id="veh_city",
                customer_id="cust_ananya",
                make="Honda",
                model="City",
                year=2021,
                registration="KA01AB1234",
                vin="MAK123CITY2021",
                mileage_km=42800,
                fuel_type="Petrol",
                last_service_date=date.today() - timedelta(days=210),
                last_service_mileage=36000,
            ),
            Vehicle(
                id="veh_creta",
                customer_id="cust_ananya",
                make="Hyundai",
                model="Creta",
                year=2023,
                registration="KA05CD5678",
                vin="MAK123CRETA2023",
                mileage_km=18500,
                fuel_type="Petrol",
                last_service_date=date.today() - timedelta(days=95),
                last_service_mileage=15000,
            ),
            Vehicle(
                id="veh_nexon",
                customer_id="cust_rahul",
                make="Tata",
                model="Nexon EV",
                year=2022,
                registration="MH02EF9012",
                vin="MAK123NEXON2022",
                mileage_km=31200,
                fuel_type="Electric",
                last_service_date=date.today() - timedelta(days=160),
                last_service_mileage=25000,
            ),
            Vehicle(
                id="veh_swift",
                customer_id="cust_priya",
                make="Maruti",
                model="Swift",
                year=2019,
                registration="KA03GH3456",
                vin="MAK123SWIFT2019",
                mileage_km=67400,
                fuel_type="Petrol",
                last_service_date=date.today() - timedelta(days=300),
                last_service_mileage=58000,
            ),
        ]
        for v in vehicles:
            self.vehicles[v.id] = v

        centers = [
            ServiceCenter(
                "ctr_koramangala",
                "ServiceLane Koramangala",
                "Bengaluru",
                "Koramangala",
                "12th Main, 5th Block, Koramangala",
                "+918012345001",
                4.7,
            ),
            ServiceCenter(
                "ctr_indiranagar",
                "ServiceLane Indiranagar",
                "Bengaluru",
                "Indiranagar",
                "100 Feet Road, Indiranagar",
                "+918012345002",
                4.6,
            ),
            ServiceCenter(
                "ctr_andheri",
                "ServiceLane Andheri",
                "Mumbai",
                "Andheri West",
                "Link Road, Andheri West",
                "+912212345001",
                4.5,
            ),
        ]
        for c in centers:
            self.centers[c.id] = c

        packages = [
            ServicePackage(
                "pkg_basic",
                "Basic Periodic Service",
                ServiceCategory.PERIODIC,
                "Oil change, filters, multi-point inspection, fluid top-up.",
                2.0,
                3499,
                ["Engine oil", "Oil filter", "Air filter check", "25-point inspection"],
                recommended_every_km=10000,
                recommended_every_months=6,
            ),
            ServicePackage(
                "pkg_comprehensive",
                "Comprehensive Service",
                ServiceCategory.PERIODIC,
                "Full periodic service with brake check, AC health, and wheel alignment check.",
                4.0,
                6999,
                [
                    "Everything in Basic",
                    "Cabin filter",
                    "Brake inspection",
                    "AC performance check",
                    "Wheel alignment check",
                ],
                recommended_every_km=20000,
                recommended_every_months=12,
            ),
            ServicePackage(
                "pkg_diagnostic",
                "OBD Diagnostic Scan",
                ServiceCategory.DIAGNOSTIC,
                "Computerized scan for warning lights, sensor faults, and EV battery health.",
                1.0,
                1499,
                ["OBD scan", "Fault code report", "Technician consultation"],
            ),
            ServicePackage(
                "pkg_brake",
                "Brake Service",
                ServiceCategory.REPAIR,
                "Brake pad inspection/replacement, rotor check, fluid bleed.",
                3.0,
                5499,
                ["Pad inspection", "Rotor measurement", "Brake fluid top-up/bleed"],
            ),
            ServicePackage(
                "pkg_ac",
                "AC Service & Gas Top-up",
                ServiceCategory.AC,
                "AC cleaning, gas pressure check, and refrigerant top-up if needed.",
                2.5,
                3999,
                ["Cabin filter", "Coil cleaning", "Gas pressure check"],
            ),
            ServicePackage(
                "pkg_tires",
                "Tire Rotation & Balancing",
                ServiceCategory.TIRES,
                "Rotate, balance, and inspect tire wear and pressure.",
                1.5,
                1999,
                ["Rotation", "Balancing", "Pressure set", "Tread depth check"],
            ),
            ServicePackage(
                "pkg_detail",
                "Express Detailing",
                ServiceCategory.DETAILING,
                "Exterior wash, interior vacuum, and dashboard polish.",
                2.0,
                2499,
                ["Exterior wash", "Interior vacuum", "Dashboard polish"],
            ),
        ]
        for p in packages:
            self.packages[p.id] = p

        # Generate slots for next 7 days at each center
        base = datetime.now().replace(minute=0, second=0, microsecond=0) + timedelta(hours=1)
        for center in centers:
            for day_offset in range(1, 8):
                day = (base + timedelta(days=day_offset)).replace(hour=0)
                for hour in (9, 11, 13, 15, 17):
                    start = day.replace(hour=hour)
                    end = start + timedelta(hours=2)
                    slot_id = f"slot_{center.id}_{start.strftime('%Y%m%d%H')}"
                    # Keep a couple of slots unavailable for realism
                    available = not (hour == 13 and day_offset % 3 == 0)
                    self.slots[slot_id] = TimeSlot(
                        center_id=center.id,
                        slot_id=slot_id,
                        start=start,
                        end=end,
                        bay=f"Bay {(hour // 2) % 3 + 1}",
                        available=available,
                    )

        self.history = [
            ServiceHistoryEntry(
                "hist_1",
                "veh_city",
                "Basic Periodic Service",
                "ServiceLane Koramangala",
                date.today() - timedelta(days=210),
                36000,
                3299,
                "Oil + filter replaced. Minor cabin filter dust noted.",
            ),
            ServiceHistoryEntry(
                "hist_2",
                "veh_creta",
                "Basic Periodic Service",
                "ServiceLane Indiranagar",
                date.today() - timedelta(days=95),
                15000,
                3599,
                "First year service completed. All fluids OK.",
            ),
            ServiceHistoryEntry(
                "hist_3",
                "veh_swift",
                "Comprehensive Service",
                "ServiceLane Koramangala",
                date.today() - timedelta(days=300),
                58000,
                7200,
                "Brake pads at 40%. Recommended follow-up within 5,000 km.",
            ),
        ]

        # One in-progress appointment for demo status checks
        slot = next(s for s in self.slots.values() if s.center_id == "ctr_andheri" and s.available)
        slot.available = False
        appt = Appointment(
            id="appt_demo_rahul",
            customer_id="cust_rahul",
            vehicle_id="veh_nexon",
            center_id="ctr_andheri",
            package_id="pkg_comprehensive",
            slot_id=slot.slot_id,
            scheduled_start=slot.start,
            status=AppointmentStatus.IN_PROGRESS,
            symptoms="Battery range dropped after recent trips",
            estimated_cost_inr=6999,
            notes="Technician running EV battery health diagnostics.",
        )
        self.appointments[appt.id] = appt

    # ---- lookups ---------------------------------------------------------

    def find_customer(self, query: str) -> Optional[Customer]:
        q = query.strip().lower().replace(" ", "")
        for c in self.customers.values():
            if (
                c.id.lower() == q
                or c.phone.replace(" ", "") == query.strip().replace(" ", "")
                or c.email.lower() == query.strip().lower()
                or c.name.lower().replace(" ", "") == q
                or query.strip().lower() in c.name.lower()
            ):
                return c
        return None

    def vehicles_for_customer(self, customer_id: str) -> list[Vehicle]:
        return [v for v in self.vehicles.values() if v.customer_id == customer_id]

    def find_vehicle(self, query: str, customer_id: Optional[str] = None) -> Optional[Vehicle]:
        q = query.strip().lower().replace(" ", "")
        pool = (
            self.vehicles_for_customer(customer_id)
            if customer_id
            else list(self.vehicles.values())
        )
        for v in pool:
            if (
                v.id.lower() == q
                or v.registration.lower().replace(" ", "") == q
                or v.model.lower() == q
                or f"{v.make}{v.model}".lower().replace(" ", "") == q
                or query.strip().lower() in v.display_name().lower()
            ):
                return v
        return None

    def centers_in_city(self, city: str) -> list[ServiceCenter]:
        return [c for c in self.centers.values() if c.city.lower() == city.lower()]

    def available_slots(
        self,
        center_id: Optional[str] = None,
        city: Optional[str] = None,
        day: Optional[date] = None,
        limit: int = 8,
    ) -> list[TimeSlot]:
        center_ids = set(self.centers.keys())
        if center_id:
            center_ids = {center_id}
        elif city:
            center_ids = {c.id for c in self.centers_in_city(city)}

        results: list[TimeSlot] = []
        for slot in sorted(self.slots.values(), key=lambda s: s.start):
            if not slot.available or slot.center_id not in center_ids:
                continue
            if day and slot.start.date() != day:
                continue
            if slot.start < datetime.now():
                continue
            results.append(slot)
            if len(results) >= limit:
                break
        return results

    def recommend_for_vehicle(
        self, vehicle: Vehicle, symptoms: Optional[str] = None
    ) -> list[Recommendation]:
        recs: list[Recommendation] = []
        km_since = (
            vehicle.mileage_km - vehicle.last_service_mileage
            if vehicle.last_service_mileage is not None
            else vehicle.mileage_km
        )
        days_since = (
            (date.today() - vehicle.last_service_date).days
            if vehicle.last_service_date
            else 999
        )
        text = (symptoms or "").lower()

        if any(k in text for k in ("brake", "squeak", "grinding", "pedal")):
            pkg = self.packages["pkg_brake"]
            recs.append(
                Recommendation(
                    pkg.id,
                    pkg.name,
                    "Symptoms suggest brake inspection is needed.",
                    Urgency.HIGH,
                    pkg.base_price_inr,
                    pkg.duration_hours,
                )
            )
        if any(k in text for k in ("ac", "cooling", "hot air", "gas")):
            pkg = self.packages["pkg_ac"]
            recs.append(
                Recommendation(
                    pkg.id,
                    pkg.name,
                    "AC-related symptoms reported.",
                    Urgency.MEDIUM,
                    pkg.base_price_inr,
                    pkg.duration_hours,
                )
            )
        if any(k in text for k in ("warning", "check engine", "light", "error", "range", "battery")):
            pkg = self.packages["pkg_diagnostic"]
            recs.append(
                Recommendation(
                    pkg.id,
                    pkg.name,
                    "Warning light / battery / fault symptoms — start with diagnostics.",
                    Urgency.HIGH,
                    pkg.base_price_inr,
                    pkg.duration_hours,
                )
            )
        if any(k in text for k in ("tire", "tyre", "vibration", "balancing")):
            pkg = self.packages["pkg_tires"]
            recs.append(
                Recommendation(
                    pkg.id,
                    pkg.name,
                    "Tire/vibration symptoms reported.",
                    Urgency.MEDIUM,
                    pkg.base_price_inr,
                    pkg.duration_hours,
                )
            )

        if km_since >= 15000 or days_since >= 300:
            pkg = self.packages["pkg_comprehensive"]
            recs.append(
                Recommendation(
                    pkg.id,
                    pkg.name,
                    f"Due for comprehensive service ({km_since} km / {days_since} days since last service).",
                    Urgency.HIGH if km_since >= 20000 or days_since >= 365 else Urgency.MEDIUM,
                    pkg.base_price_inr,
                    pkg.duration_hours,
                )
            )
        elif km_since >= 8000 or days_since >= 150:
            pkg = self.packages["pkg_basic"]
            recs.append(
                Recommendation(
                    pkg.id,
                    pkg.name,
                    f"Periodic service due soon ({km_since} km / {days_since} days since last service).",
                    Urgency.MEDIUM,
                    pkg.base_price_inr,
                    pkg.duration_hours,
                )
            )
        elif not recs:
            pkg = self.packages["pkg_basic"]
            recs.append(
                Recommendation(
                    pkg.id,
                    pkg.name,
                    "No urgent issues detected — basic service keeps the vehicle healthy.",
                    Urgency.LOW,
                    pkg.base_price_inr,
                    pkg.duration_hours,
                )
            )

        # Deduplicate by package_id preserving order
        seen: set[str] = set()
        unique: list[Recommendation] = []
        for r in recs:
            if r.package_id not in seen:
                seen.add(r.package_id)
                unique.append(r)
        return unique[:4]

    def book_appointment(
        self,
        *,
        customer_id: str,
        vehicle_id: str,
        center_id: str,
        package_id: str,
        slot_id: str,
        symptoms: Optional[str] = None,
        notes: str = "",
    ) -> Appointment:
        with self._lock:
            slot = self.slots.get(slot_id)
            if not slot or not slot.available:
                raise ValueError("Selected slot is no longer available.")
            if slot.center_id != center_id:
                raise ValueError("Slot does not belong to the selected service center.")
            if package_id not in self.packages:
                raise ValueError("Unknown service package.")
            if vehicle_id not in self.vehicles:
                raise ValueError("Unknown vehicle.")
            if customer_id not in self.customers:
                raise ValueError("Unknown customer.")

            slot.available = False
            pkg = self.packages[package_id]
            appt = Appointment(
                id=_id("appt"),
                customer_id=customer_id,
                vehicle_id=vehicle_id,
                center_id=center_id,
                package_id=package_id,
                slot_id=slot_id,
                scheduled_start=slot.start,
                status=AppointmentStatus.BOOKED,
                symptoms=symptoms,
                estimated_cost_inr=pkg.base_price_inr,
                notes=notes,
            )
            self.appointments[appt.id] = appt
            return appt

    def cancel_appointment(self, appointment_id: str) -> Appointment:
        with self._lock:
            appt = self.appointments.get(appointment_id)
            if not appt:
                raise ValueError("Appointment not found.")
            if appt.status in (AppointmentStatus.COMPLETED, AppointmentStatus.CANCELLED):
                raise ValueError(f"Cannot cancel appointment in status '{appt.status.value}'.")
            appt.status = AppointmentStatus.CANCELLED
            slot = self.slots.get(appt.slot_id)
            if slot:
                slot.available = True
            return appt

    def appointments_for_customer(self, customer_id: str) -> list[Appointment]:
        return sorted(
            [a for a in self.appointments.values() if a.customer_id == customer_id],
            key=lambda a: a.scheduled_start,
            reverse=True,
        )

    def history_for_vehicle(self, vehicle_id: str) -> list[ServiceHistoryEntry]:
        return [h for h in self.history if h.vehicle_id == vehicle_id]


# Singleton used by the API / CLI
STORE = ServiceStore()
