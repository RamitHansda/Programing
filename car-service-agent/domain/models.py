"""Domain models for the car service AI agent."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import date, datetime
from enum import Enum
from typing import Any, Optional


class ServiceCategory(str, Enum):
    PERIODIC = "periodic"
    REPAIR = "repair"
    DIAGNOSTIC = "diagnostic"
    DETAILING = "detailing"
    TIRES = "tires"
    AC = "ac"


class AppointmentStatus(str, Enum):
    BOOKED = "booked"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    CANCELLED = "cancelled"
    READY_FOR_PICKUP = "ready_for_pickup"


class Urgency(str, Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


@dataclass
class Customer:
    id: str
    name: str
    phone: str
    email: str
    city: str

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class Vehicle:
    id: str
    customer_id: str
    make: str
    model: str
    year: int
    registration: str
    vin: str
    mileage_km: int
    fuel_type: str
    last_service_date: Optional[date] = None
    last_service_mileage: Optional[int] = None

    def display_name(self) -> str:
        return f"{self.year} {self.make} {self.model} ({self.registration})"

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        if self.last_service_date:
            data["last_service_date"] = self.last_service_date.isoformat()
        return data


@dataclass
class ServiceCenter:
    id: str
    name: str
    city: str
    area: str
    address: str
    phone: str
    rating: float
    open_hour: int = 9
    close_hour: int = 19

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class ServicePackage:
    id: str
    name: str
    category: ServiceCategory
    description: str
    duration_hours: float
    base_price_inr: int
    includes: list[str] = field(default_factory=list)
    recommended_every_km: Optional[int] = None
    recommended_every_months: Optional[int] = None

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["category"] = self.category.value
        return data


@dataclass
class TimeSlot:
    center_id: str
    slot_id: str
    start: datetime
    end: datetime
    bay: str
    available: bool = True

    def to_dict(self) -> dict[str, Any]:
        return {
            "center_id": self.center_id,
            "slot_id": self.slot_id,
            "start": self.start.isoformat(timespec="minutes"),
            "end": self.end.isoformat(timespec="minutes"),
            "bay": self.bay,
            "available": self.available,
            "label": self.start.strftime("%a %d %b, %I:%M %p"),
        }


@dataclass
class Appointment:
    id: str
    customer_id: str
    vehicle_id: str
    center_id: str
    package_id: str
    slot_id: str
    scheduled_start: datetime
    status: AppointmentStatus
    symptoms: Optional[str] = None
    estimated_cost_inr: int = 0
    notes: str = ""
    created_at: datetime = field(default_factory=datetime.now)

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "customer_id": self.customer_id,
            "vehicle_id": self.vehicle_id,
            "center_id": self.center_id,
            "package_id": self.package_id,
            "slot_id": self.slot_id,
            "scheduled_start": self.scheduled_start.isoformat(timespec="minutes"),
            "status": self.status.value,
            "symptoms": self.symptoms,
            "estimated_cost_inr": self.estimated_cost_inr,
            "notes": self.notes,
            "created_at": self.created_at.isoformat(timespec="seconds"),
        }


@dataclass
class ServiceHistoryEntry:
    id: str
    vehicle_id: str
    package_name: str
    center_name: str
    service_date: date
    mileage_km: int
    cost_inr: int
    summary: str

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["service_date"] = self.service_date.isoformat()
        return data


@dataclass
class Recommendation:
    package_id: str
    package_name: str
    reason: str
    urgency: Urgency
    estimated_cost_inr: int
    duration_hours: float

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["urgency"] = self.urgency.value
        return data
