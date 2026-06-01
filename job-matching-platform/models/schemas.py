"""
Core data models for the job-candidate matching platform.

Design notes:
- Candidate and Job both carry a pre-computed embedding vector.
  At 100 B candidates × 256 floats × 4 bytes = ~100 TB total, so embeddings
  are stored in a sharded vector store (FAISS / Milvus / Pinecone) and only
  the metadata rows live in the relational DB.
- MatchResult is the unit of work written to Kafka and consumed by the email
  worker.  It is intentionally small to keep partition throughput high.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import List, Optional


# ---------------------------------------------------------------------------
# Enumerations
# ---------------------------------------------------------------------------

class ExperienceLevel(str, Enum):
    INTERN     = "intern"
    JUNIOR     = "junior"
    MID        = "mid"
    SENIOR     = "senior"
    LEAD       = "lead"
    PRINCIPAL  = "principal"
    EXECUTIVE  = "executive"


class EmploymentType(str, Enum):
    FULL_TIME  = "full_time"
    PART_TIME  = "part_time"
    CONTRACT   = "contract"
    FREELANCE  = "freelance"
    INTERNSHIP = "internship"


class MatchStatus(str, Enum):
    PENDING   = "pending"
    EMAIL_QUEUED   = "email_queued"
    EMAIL_SENT     = "email_sent"
    EMAIL_FAILED   = "email_failed"
    OPTED_OUT      = "opted_out"


# ---------------------------------------------------------------------------
# Core domain objects
# ---------------------------------------------------------------------------

@dataclass
class Candidate:
    candidate_id: str
    email: str
    full_name: str
    skills: List[str]
    experience_years: float
    experience_level: ExperienceLevel
    preferred_locations: List[str]        # ["remote", "New York", "San Francisco"]
    preferred_employment_types: List[EmploymentType]
    current_title: Optional[str] = None
    education: Optional[str] = None       # highest degree
    preferred_salary_min: Optional[int] = None
    preferred_salary_max: Optional[int] = None
    # Shard key — determines which FAISS index shard holds this candidate.
    shard_id: int = 0
    created_at: datetime = field(default_factory=datetime.utcnow)
    updated_at: datetime = field(default_factory=datetime.utcnow)

    def __post_init__(self):
        if not self.candidate_id:
            self.candidate_id = str(uuid.uuid4())


@dataclass
class Job:
    job_id: str
    employer_id: str
    title: str
    description: str
    required_skills: List[str]
    preferred_skills: List[str]
    experience_level: ExperienceLevel
    employment_type: EmploymentType
    location: str
    is_remote: bool = False
    salary_min: Optional[int] = None
    salary_max: Optional[int] = None
    posted_at: datetime = field(default_factory=datetime.utcnow)
    expires_at: Optional[datetime] = None
    is_active: bool = True

    def __post_init__(self):
        if not self.job_id:
            self.job_id = str(uuid.uuid4())


@dataclass
class MatchResult:
    """Written to Kafka topic `job-candidate-matches`."""
    match_id: str
    job_id: str
    candidate_id: str
    candidate_email: str
    candidate_name: str
    job_title: str
    employer_id: str
    similarity_score: float           # cosine similarity [0, 1]
    match_reasons: List[str]          # human-readable explanations
    status: MatchStatus = MatchStatus.PENDING
    created_at: datetime = field(default_factory=datetime.utcnow)

    def __post_init__(self):
        if not self.match_id:
            self.match_id = str(uuid.uuid4())

    def to_dict(self) -> dict:
        return {
            "match_id":        self.match_id,
            "job_id":          self.job_id,
            "candidate_id":    self.candidate_id,
            "candidate_email": self.candidate_email,
            "candidate_name":  self.candidate_name,
            "job_title":       self.job_title,
            "employer_id":     self.employer_id,
            "similarity_score": self.similarity_score,
            "match_reasons":   self.match_reasons,
            "status":          self.status.value,
            "created_at":      self.created_at.isoformat(),
        }

    @classmethod
    def from_dict(cls, data: dict) -> "MatchResult":
        data = dict(data)
        data["status"] = MatchStatus(data.get("status", "pending"))
        if isinstance(data.get("created_at"), str):
            data["created_at"] = datetime.fromisoformat(data["created_at"])
        return cls(**data)


@dataclass
class EmailRecord:
    """Tracks every outbound email for audit and deduplication."""
    record_id: str
    match_id: str
    candidate_email: str
    job_id: str
    sent_at: Optional[datetime] = None
    provider_message_id: Optional[str] = None
    attempt_count: int = 0
    last_error: Optional[str] = None
    status: MatchStatus = MatchStatus.PENDING

    def __post_init__(self):
        if not self.record_id:
            self.record_id = str(uuid.uuid4())
