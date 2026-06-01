"""
Kafka producer for match results.

Partitioning strategy
---------------------
We partition by candidate_id (not job_id) so that all match notifications for
a given candidate land on the same partition / consumer.  This makes it trivial
to enforce per-candidate rate limits (e.g. max 3 job emails/day) inside the
consumer without cross-partition coordination.

Throughput target
-----------------
5 B match records / 24 hours = ~58 000 records/s peak.
With snappy compression, 65 KB batches, and 1 000 partitions each carrying
~58 records/s, a single broker can absorb this easily.  At 3× replication the
cluster needs ~3 × 5 B × avg_record_size bytes of disk per sweep cycle.
Estimated record size ≈ 500 bytes → ~7.5 TB total Kafka storage per cycle.
"""

from __future__ import annotations

import json
import logging
from typing import List, Optional

from config.settings import settings
from models.schemas import MatchResult

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Optional kafka-python import — fall back to an in-memory stub.
# ---------------------------------------------------------------------------
try:
    from kafka import KafkaProducer as _KafkaProducer
    from kafka.errors import KafkaError

    USING_KAFKA = True
except ImportError:
    _KafkaProducer = None  # type: ignore[assignment,misc]
    KafkaError = Exception  # type: ignore[assignment,misc]
    USING_KAFKA = False
    logger.warning(
        "kafka-python not installed — using in-memory stub producer. "
        "Install with: pip install kafka-python"
    )


# ---------------------------------------------------------------------------
# In-memory stub (for testing without a Kafka broker)
# ---------------------------------------------------------------------------

class _InMemoryProducer:
    """Thread-safe stub that accumulates messages in a list."""

    def __init__(self):
        import threading
        self._lock = threading.Lock()
        self.sent: List[dict] = []

    def send(self, topic: str, key: bytes, value: bytes) -> None:
        with self._lock:
            self.sent.append({"topic": topic, "key": key, "value": json.loads(value)})

    def flush(self) -> None:
        pass

    def close(self) -> None:
        pass


# ---------------------------------------------------------------------------
# Real Kafka producer wrapper
# ---------------------------------------------------------------------------

class MatchProducer:
    """
    Wraps a KafkaProducer with automatic serialisation and error handling.

    Usage
    -----
    producer = MatchProducer()
    producer.publish(match_results)   # list of MatchResult
    producer.flush()
    """

    def __init__(self, brokers: Optional[str] = None):
        brokers = brokers or settings.kafka.brokers
        if USING_KAFKA:
            self._producer = _KafkaProducer(
                bootstrap_servers=brokers.split(","),
                key_serializer=lambda k: k.encode("utf-8") if isinstance(k, str) else k,
                value_serializer=lambda v: json.dumps(v).encode("utf-8"),
                compression_type=settings.kafka.compression,
                batch_size=settings.kafka.batch_size,
                linger_ms=settings.kafka.linger_ms,
                # Idempotent producer prevents duplicate records on retries.
                enable_idempotence=True,
                acks="all",
                retries=5,
            )
            logger.info("KafkaProducer connected to %s", brokers)
        else:
            self._producer = _InMemoryProducer()
            logger.info("Using in-memory stub producer.")

        self._topic = settings.kafka.match_topic

    def publish(self, results: List[MatchResult]) -> None:
        """
        Serialise and publish a batch of match results.

        Each record is keyed by candidate_id for partition affinity.
        On error the failed record is logged and skipped (not re-raised) so
        one bad record does not stall the batch.
        """
        for result in results:
            try:
                self._producer.send(
                    topic=self._topic,
                    key=result.candidate_id,
                    value=result.to_dict(),
                )
            except Exception as exc:  # noqa: BLE001
                logger.error(
                    "Failed to publish match %s for candidate %s: %s",
                    result.match_id,
                    result.candidate_id,
                    exc,
                )

    def flush(self) -> None:
        self._producer.flush()
        logger.debug("Producer flushed.")

    def close(self) -> None:
        self._producer.flush()
        self._producer.close()
        logger.info("Producer closed.")

    def __enter__(self) -> "MatchProducer":
        return self

    def __exit__(self, *_) -> None:
        self.close()
