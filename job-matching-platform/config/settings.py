"""
Centralised configuration via environment variables.

All tuneable knobs live here so operators can adjust them without touching
application code.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import List


def _env(key: str, default: str = "") -> str:
    return os.environ.get(key, default)


def _env_int(key: str, default: int) -> int:
    return int(os.environ.get(key, default))


def _env_float(key: str, default: float) -> float:
    return float(os.environ.get(key, default))


def _env_list(key: str, default: str = "") -> List[str]:
    val = os.environ.get(key, default)
    return [v.strip() for v in val.split(",") if v.strip()]


# ---------------------------------------------------------------------------
# Kafka
# ---------------------------------------------------------------------------
@dataclass
class KafkaConfig:
    brokers: str           = field(default_factory=lambda: _env("KAFKA_BROKERS", "kafka:9092"))
    match_topic: str       = field(default_factory=lambda: _env("KAFKA_MATCH_TOPIC", "job-candidate-matches"))
    email_topic: str       = field(default_factory=lambda: _env("KAFKA_EMAIL_TOPIC", "candidate-emails"))
    dlq_topic: str         = field(default_factory=lambda: _env("KAFKA_DLQ_TOPIC", "candidate-emails-dlq"))
    consumer_group: str    = field(default_factory=lambda: _env("KAFKA_CONSUMER_GROUP", "email-workers"))
    # Number of partitions for the match topic.
    # With 100 B candidates spread across 1 000 partitions each worker handles
    # ~100 M records — a comfortable batch for parallel email dispatch.
    num_partitions: int    = field(default_factory=lambda: _env_int("KAFKA_NUM_PARTITIONS", 1000))
    replication_factor: int = field(default_factory=lambda: _env_int("KAFKA_REPLICATION_FACTOR", 3))
    batch_size: int        = field(default_factory=lambda: _env_int("KAFKA_PRODUCER_BATCH_SIZE", 65536))
    linger_ms: int         = field(default_factory=lambda: _env_int("KAFKA_PRODUCER_LINGER_MS", 10))
    compression: str       = field(default_factory=lambda: _env("KAFKA_COMPRESSION", "snappy"))


# ---------------------------------------------------------------------------
# Redis
# ---------------------------------------------------------------------------
@dataclass
class RedisConfig:
    url: str               = field(default_factory=lambda: _env("REDIS_URL", "redis://redis:6379/0"))
    # Dedup key TTL — keep sent-email keys for 30 days to prevent duplicates
    # if the same job is re-indexed.
    dedup_ttl_seconds: int = field(default_factory=lambda: _env_int("REDIS_DEDUP_TTL", 86400 * 30))
    # Rate-limit: max emails per second across all workers.
    rate_limit_rps: int    = field(default_factory=lambda: _env_int("REDIS_RATE_LIMIT_RPS", 5000))


# ---------------------------------------------------------------------------
# PostgreSQL
# ---------------------------------------------------------------------------
@dataclass
class DatabaseConfig:
    url: str               = field(default_factory=lambda: _env(
        "DATABASE_URL",
        "postgresql://postgres:postgres@postgres:5432/jobmatch"
    ))
    pool_size: int         = field(default_factory=lambda: _env_int("DB_POOL_SIZE", 20))
    max_overflow: int      = field(default_factory=lambda: _env_int("DB_MAX_OVERFLOW", 10))


# ---------------------------------------------------------------------------
# Vector store (FAISS shards managed by the matching service)
# ---------------------------------------------------------------------------
@dataclass
class VectorConfig:
    # Directory (or object-store prefix) holding FAISS index shards.
    index_dir: str         = field(default_factory=lambda: _env("FAISS_INDEX_DIR", "/data/faiss"))
    embedding_dim: int     = field(default_factory=lambda: _env_int("EMBEDDING_DIM", 256))
    # Number of shards.  Each shard holds ~100 M candidates.
    num_shards: int        = field(default_factory=lambda: _env_int("FAISS_NUM_SHARDS", 1000))
    # FAISS nprobe — higher = more accurate, slower.
    nprobe: int            = field(default_factory=lambda: _env_int("FAISS_NPROBE", 64))
    # Top-K candidates per job.
    top_k: int             = field(default_factory=lambda: _env_int("MATCHING_TOP_K", 500))
    # Minimum similarity score to emit a match.
    min_score: float       = field(default_factory=lambda: _env_float("MATCHING_MIN_SCORE", 0.70))


# ---------------------------------------------------------------------------
# Email provider
# ---------------------------------------------------------------------------
@dataclass
class EmailConfig:
    provider: str          = field(default_factory=lambda: _env("EMAIL_PROVIDER", "sendgrid"))
    api_key: str           = field(default_factory=lambda: _env("EMAIL_API_KEY", ""))
    from_address: str      = field(default_factory=lambda: _env("EMAIL_FROM", "no-reply@jobmatch.example.com"))
    from_name: str         = field(default_factory=lambda: _env("EMAIL_FROM_NAME", "JobMatch"))
    # Max retries before moving message to DLQ.
    max_retries: int       = field(default_factory=lambda: _env_int("EMAIL_MAX_RETRIES", 3))
    retry_backoff_base: float = field(default_factory=lambda: _env_float("EMAIL_RETRY_BACKOFF", 2.0))
    # Concurrency per worker process.
    worker_concurrency: int = field(default_factory=lambda: _env_int("EMAIL_WORKER_CONCURRENCY", 100))
    # Total email worker replicas (set by Kubernetes HPA / docker-compose scale).
    num_workers: int       = field(default_factory=lambda: _env_int("EMAIL_NUM_WORKERS", 50))


# ---------------------------------------------------------------------------
# Matching service
# ---------------------------------------------------------------------------
@dataclass
class MatchingConfig:
    # How many jobs to process in one batch when doing a full re-index sweep.
    job_batch_size: int    = field(default_factory=lambda: _env_int("MATCHING_JOB_BATCH_SIZE", 100))
    # Parallel shard workers per matching-service replica.
    shard_workers: int     = field(default_factory=lambda: _env_int("MATCHING_SHARD_WORKERS", 16))
    # Number of matching-service replicas (informational — actual scaling via K8s).
    num_replicas: int      = field(default_factory=lambda: _env_int("MATCHING_NUM_REPLICAS", 200))


# ---------------------------------------------------------------------------
# Top-level settings object
# ---------------------------------------------------------------------------
@dataclass
class Settings:
    kafka: KafkaConfig     = field(default_factory=KafkaConfig)
    redis: RedisConfig     = field(default_factory=RedisConfig)
    database: DatabaseConfig = field(default_factory=DatabaseConfig)
    vector: VectorConfig   = field(default_factory=VectorConfig)
    email: EmailConfig     = field(default_factory=EmailConfig)
    matching: MatchingConfig = field(default_factory=MatchingConfig)
    log_level: str         = field(default_factory=lambda: _env("LOG_LEVEL", "INFO"))
    environment: str       = field(default_factory=lambda: _env("ENV", "production"))


# Singleton — imported by all modules.
settings = Settings()
