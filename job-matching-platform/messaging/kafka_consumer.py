"""
Kafka consumer for the email worker.

Consumer group `email-workers` with 1 000 partitions allows up to 1 000
parallel worker instances without any partition rebalance bottleneck.

Each worker processes messages in micro-batches (default 250 records) so it
can pipeline Redis dedup checks, email API calls, and offset commits.
"""

from __future__ import annotations

import json
import logging
from typing import Callable, List, Optional

from config.settings import settings
from models.schemas import MatchResult

logger = logging.getLogger(__name__)

try:
    from kafka import KafkaConsumer as _KafkaConsumer
    from kafka import TopicPartition
    USING_KAFKA = True
except ImportError:
    _KafkaConsumer = None  # type: ignore[assignment,misc]
    TopicPartition = None  # type: ignore[assignment,misc]
    USING_KAFKA = False
    logger.warning("kafka-python not installed — consumer stub active.")


class _InMemoryConsumer:
    """Test stub that drains a list of pre-loaded messages."""

    def __init__(self, messages: List[dict]):
        self._messages = list(messages)

    def __iter__(self):
        for msg in self._messages:
            yield _FakeMessage(msg)
        self._messages.clear()

    def commit(self):
        pass

    def close(self):
        pass


class _FakeMessage:
    def __init__(self, value: dict):
        self.value = value


class MatchConsumer:
    """
    Wraps a KafkaConsumer and yields MatchResult batches.

    Usage
    -----
    consumer = MatchConsumer()
    for batch in consumer.iter_batches(batch_size=250):
        process(batch)
    """

    def __init__(
        self,
        brokers: Optional[str] = None,
        group_id: Optional[str] = None,
        topic: Optional[str] = None,
        # Inject pre-loaded messages for testing.
        _stub_messages: Optional[List[dict]] = None,
    ):
        self._topic = topic or settings.kafka.match_topic
        brokers = brokers or settings.kafka.brokers
        group_id = group_id or settings.kafka.consumer_group

        if USING_KAFKA and _stub_messages is None:
            self._consumer = _KafkaConsumer(
                self._topic,
                bootstrap_servers=brokers.split(","),
                group_id=group_id,
                auto_offset_reset="earliest",
                enable_auto_commit=False,   # manual commit after processing
                value_deserializer=lambda b: json.loads(b.decode("utf-8")),
                max_poll_records=500,
                fetch_max_bytes=52_428_800,  # 50 MB
                session_timeout_ms=30_000,
                heartbeat_interval_ms=10_000,
            )
            logger.info("KafkaConsumer subscribed to %s (group=%s)", self._topic, group_id)
        else:
            self._consumer = _InMemoryConsumer(_stub_messages or [])
            logger.info("Using in-memory stub consumer.")

    def iter_batches(
        self,
        batch_size: int = 250,
        handler: Optional[Callable[[List[MatchResult]], None]] = None,
    ):
        """
        Yield successive batches of MatchResult objects.

        If `handler` is provided, call it on each batch and commit offsets
        only after the handler returns successfully.
        """
        batch: List[MatchResult] = []
        for msg in self._consumer:
            try:
                raw = msg.value if isinstance(msg.value, dict) else json.loads(msg.value)
                result = MatchResult.from_dict(raw)
                batch.append(result)
            except Exception as exc:
                logger.error("Failed to deserialise message: %s — %s", msg.value, exc)
                continue

            if len(batch) >= batch_size:
                if handler:
                    try:
                        handler(batch)
                        self._consumer.commit()
                    except Exception as exc:
                        logger.error("Batch handler failed: %s", exc)
                else:
                    yield batch
                    self._consumer.commit()
                batch = []

        # Flush remaining partial batch.
        if batch:
            if handler:
                try:
                    handler(batch)
                    self._consumer.commit()
                except Exception as exc:
                    logger.error("Final batch handler failed: %s", exc)
            else:
                yield batch
                self._consumer.commit()

    def close(self) -> None:
        self._consumer.close()
        logger.info("Consumer closed.")
