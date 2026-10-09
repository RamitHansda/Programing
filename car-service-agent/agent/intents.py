"""Intent parsing and slot extraction for the car service agent."""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from enum import Enum
from typing import Optional


class Intent(str, Enum):
    GREETING = "greeting"
    IDENTIFY = "identify"
    BOOK_SERVICE = "book_service"
    RECOMMEND = "recommend"
    CHECK_STATUS = "check_status"
    LIST_APPOINTMENTS = "list_appointments"
    CANCEL = "cancel"
    HISTORY = "history"
    LIST_PACKAGES = "list_packages"
    FIND_SLOTS = "find_slots"
    ESTIMATE = "estimate"
    CONFIRM = "confirm"
    DENY = "deny"
    ESCALATE = "escalate"
    HELP = "help"
    UNKNOWN = "unknown"


@dataclass
class ParsedUtterance:
    intent: Intent
    customer_query: Optional[str] = None
    vehicle_query: Optional[str] = None
    package_query: Optional[str] = None
    center_query: Optional[str] = None
    city: Optional[str] = None
    symptoms: Optional[str] = None
    appointment_id: Optional[str] = None
    slot_hint: Optional[str] = None
    raw: str = ""
    confidence: float = 0.0
    entities: dict[str, str] = field(default_factory=dict)


PHONE_RE = re.compile(r"(\+?\d[\d\s\-]{8,}\d)")
EMAIL_RE = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
APPT_RE = re.compile(r"\b(appt_[a-z0-9_]+)\b", re.I)
REG_RE = re.compile(r"\b([A-Z]{2}\d{2}[A-Z]{1,2}\d{4})\b", re.I)

CITY_ALIASES = {
    "bangalore": "Bengaluru",
    "bengaluru": "Bengaluru",
    "blr": "Bengaluru",
    "mumbai": "Mumbai",
    "bombay": "Mumbai",
}

VEHICLE_HINTS = [
    "city",
    "creta",
    "nexon",
    "swift",
    "honda",
    "hyundai",
    "tata",
    "maruti",
]

PACKAGE_HINTS = {
    "basic": "pkg_basic",
    "periodic": "pkg_basic",
    "comprehensive": "pkg_comprehensive",
    "full service": "pkg_comprehensive",
    "diagnostic": "pkg_diagnostic",
    "obd": "pkg_diagnostic",
    "scan": "pkg_diagnostic",
    "brake": "pkg_brake",
    "ac": "pkg_ac",
    "air conditioning": "pkg_ac",
    "tire": "pkg_tires",
    "tyre": "pkg_tires",
    "balancing": "pkg_tires",
    "detail": "pkg_detail",
    "wash": "pkg_detail",
}

CENTER_HINTS = {
    "koramangala": "ctr_koramangala",
    "indiranagar": "ctr_indiranagar",
    "andheri": "ctr_andheri",
}

SYMPTOM_KEYWORDS = [
    "brake",
    "squeak",
    "grinding",
    "ac",
    "cooling",
    "hot air",
    "warning",
    "check engine",
    "light",
    "vibration",
    "noise",
    "range",
    "battery",
    "overheat",
    "leak",
    "pulling",
]


def parse_utterance(text: str) -> ParsedUtterance:
    raw = text.strip()
    lower = raw.lower()
    parsed = ParsedUtterance(intent=Intent.UNKNOWN, raw=raw, confidence=0.4)

    # Entities first
    phone = PHONE_RE.search(raw)
    email = EMAIL_RE.search(raw)
    appt = APPT_RE.search(raw)
    reg = REG_RE.search(raw)

    if phone:
        parsed.customer_query = phone.group(1).replace(" ", "")
        parsed.entities["phone"] = parsed.customer_query
    elif email:
        parsed.customer_query = email.group(0)
        parsed.entities["email"] = parsed.customer_query

    if appt:
        parsed.appointment_id = appt.group(1).lower()
        parsed.entities["appointment_id"] = parsed.appointment_id

    if reg:
        parsed.vehicle_query = reg.group(1).upper()
        parsed.entities["registration"] = parsed.vehicle_query
    else:
        for hint in VEHICLE_HINTS:
            if re.search(rf"\b{re.escape(hint)}\b", lower):
                parsed.vehicle_query = hint
                break

    for alias, city in CITY_ALIASES.items():
        if re.search(rf"\b{re.escape(alias)}\b", lower):
            parsed.city = city
            break

    for hint, center_id in CENTER_HINTS.items():
        if hint in lower:
            parsed.center_query = center_id
            break

    for hint, pkg_id in PACKAGE_HINTS.items():
        if hint in lower:
            parsed.package_query = pkg_id
            break

    symptom_hits = [k for k in SYMPTOM_KEYWORDS if k in lower]
    if symptom_hits:
        parsed.symptoms = raw

    # Name after "I am" / "this is" / "my name is"
    name_match = re.search(
        r"(?:\bi am\b|\bi'm\b|\bthis is\b|\bmy name is\b)\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)",
        raw,
        re.I,
    )
    if name_match and not parsed.customer_query:
        parsed.customer_query = name_match.group(1)

    # Intent classification (ordered by specificity)
    if _any(lower, ["hi", "hello", "hey", "good morning", "good evening"]) and len(lower.split()) <= 4:
        parsed.intent = Intent.GREETING
        parsed.confidence = 0.95
    elif _any(lower, ["help", "what can you do", "capabilities", "menu"]):
        parsed.intent = Intent.HELP
        parsed.confidence = 0.9
    elif _any(lower, ["yes", "yeah", "yep", "confirm", "book it", "go ahead", "please book", "sure"]):
        parsed.intent = Intent.CONFIRM
        parsed.confidence = 0.9
    elif _any(lower, ["no", "nope", "cancel that", "don't", "do not", "not now"]):
        # Prefer cancel appointment if appointment context words present
        if _any(lower, ["appointment", "booking", "cancel my"]):
            parsed.intent = Intent.CANCEL
        else:
            parsed.intent = Intent.DENY
        parsed.confidence = 0.85
    elif _any(lower, ["human", "advisor", "agent", "talk to someone", "escalate", "manager"]):
        parsed.intent = Intent.ESCALATE
        parsed.confidence = 0.9
    elif _any(lower, ["cancel"]):
        parsed.intent = Intent.CANCEL
        parsed.confidence = 0.9
    elif _any(lower, ["status", "where is my car", "progress", "ready for pickup", "track"]):
        parsed.intent = Intent.CHECK_STATUS
        parsed.confidence = 0.9
    elif _any(lower, ["my appointments", "upcoming booking", "list booking", "show appointments"]):
        parsed.intent = Intent.LIST_APPOINTMENTS
        parsed.confidence = 0.85
    elif _any(lower, ["history", "past service", "previous service", "last service"]):
        parsed.intent = Intent.HISTORY
        parsed.confidence = 0.85
    elif _any(lower, ["price", "cost", "how much", "estimate", "quote"]):
        parsed.intent = Intent.ESTIMATE
        parsed.confidence = 0.85
    elif _any(lower, ["packages", "services you offer", "what services", "catalogue", "catalog"]):
        parsed.intent = Intent.LIST_PACKAGES
        parsed.confidence = 0.85
    elif _any(lower, ["slot", "availability", "available time", "when can", "openings"]):
        parsed.intent = Intent.FIND_SLOTS
        parsed.confidence = 0.85
    elif _any(lower, ["recommend", "suggest", "what do i need", "due for service", "overdue"]):
        parsed.intent = Intent.RECOMMEND
        parsed.confidence = 0.85
    elif _any(
        lower,
        [
            "book",
            "schedule",
            "appointment",
            "service my",
            "get my car serviced",
            "need a service",
            "want a service",
        ],
    ):
        parsed.intent = Intent.BOOK_SERVICE
        parsed.confidence = 0.9
    elif parsed.customer_query or _any(lower, ["i am", "i'm", "my name", "this is", "looking up"]):
        parsed.intent = Intent.IDENTIFY
        parsed.confidence = 0.8
    elif symptom_hits:
        parsed.intent = Intent.RECOMMEND
        parsed.confidence = 0.7
    else:
        # Soft identify if a capitalized name appears alone-ish
        lone_name = re.fullmatch(r"[A-Z][a-z]+(?:\s+[A-Z][a-z]+)?", raw)
        if lone_name:
            parsed.intent = Intent.IDENTIFY
            parsed.customer_query = raw
            parsed.confidence = 0.75

    # Slot selection phrases: "first slot", "slot 2", "11 am"
    slot_num = re.search(r"\b(?:slot\s*)?#?(\d)\b", lower)
    if slot_num:
        parsed.slot_hint = slot_num.group(1)
    time_hint = re.search(r"\b(\d{1,2})\s*(am|pm)\b", lower)
    if time_hint:
        parsed.slot_hint = f"{time_hint.group(1)}{time_hint.group(2)}"

    return parsed


def _any(text: str, phrases: list[str]) -> bool:
    return any(p in text for p in phrases)
