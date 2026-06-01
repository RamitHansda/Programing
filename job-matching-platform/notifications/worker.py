"""
Email worker — consumes match results from Kafka and sends emails.

Concurrency model
-----------------
Each worker process uses a ThreadPoolExecutor with `concurrency` threads.
Threads call `send_match_email`, which is I/O-bound (HTTPS to SendGrid/SES),
so threading is appropriate here.

With 50 worker replicas × 100 threads each = 5 000 concurrent in-flight
email API calls across the cluster.

Throughput estimate
-------------------
Assuming 100 ms avg round-trip per email API call:
  5 000 concurrent × 10 calls/s per thread = 50 000 emails/s
  Per day: 50 000 × 86 400 = 4.32 B emails — well above the 5 B total.
  (The global Redis rate limiter caps this at settings.redis.rate_limit_rps.)

Dead-letter queue
-----------------
Messages that fail all retries are published to `candidate-emails-dlq` for
manual review or re-processing.
"""

from __future__ import annotations

import json
import logging
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import List, Optional

from config.settings import settings
from notifications.dedup import DedupManager
from notifications.sender import EmailProvider, SendResult, create_provider, send_match_email
from models.schemas import MatchResult, MatchStatus
from messaging.kafka_consumer import MatchConsumer

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# DLQ producer (optional — only if kafka-python is installed)
# ---------------------------------------------------------------------------

try:
    from kafka import KafkaProducer as _KP
    _dlq_producer: Optional[_KP] = None

    def _get_dlq_producer() -> _KP:
        global _dlq_producer
        if _dlq_producer is None:
            _dlq_producer = _KP(
                bootstrap_servers=settings.kafka.brokers.split(","),
                value_serializer=lambda v: json.dumps(v).encode(),
            )
        return _dlq_producer

    def _send_to_dlq(match: MatchResult, error: str) -> None:
        try:
            payload = match.to_dict()
            payload["dlq_error"] = error
            _get_dlq_producer().send(settings.kafka.dlq_topic, value=payload)
        except Exception as exc:
            logger.error("Failed to write to DLQ: %s", exc)

except ImportError:
    def _send_to_dlq(match: MatchResult, error: str) -> None:  # type: ignore[misc]
        logger.error("DLQ [no Kafka]: match=%s error=%s", match.match_id, error)


# ---------------------------------------------------------------------------
# Worker
# ---------------------------------------------------------------------------

class EmailWorker:
    """
    Stateful worker that processes a stream of MatchResult batches.

    Parameters
    ----------
    provider    : EmailProvider implementation (SendGrid, SES, or Stub).
    dedup       : DedupManager instance.
    concurrency : Number of parallel email threads per worker process.
    """

    def __init__(
        self,
        provider: Optional[EmailProvider] = None,
        dedup: Optional[DedupManager] = None,
        concurrency: int = 100,
        max_retries: int = 3,
        backoff_base: float = 2.0,
    ):
        self.provider = provider or create_provider()
        self.dedup = dedup or DedupManager()
        self.concurrency = concurrency
        self.max_retries = max_retries
        self.backoff_base = backoff_base

        # Metrics counters (in-process; expose via Prometheus in production).
        self._sent     = 0
        self._skipped  = 0
        self._failed   = 0

    # ------------------------------------------------------------------
    def _process_one(self, match: MatchResult) -> SendResult:
        """
        Execute the full pipeline for a single match:
          dedup check → rate limit → send → mark sent / DLQ
        """
        # Rate-limit backoff: spin until a slot is available.
        while not self.dedup.acquire_rate_slot():
            time.sleep(0.01)

        result = send_match_email(
            match,
            self.provider,
            max_retries=self.max_retries,
            backoff_base=self.backoff_base,
        )

        if result.success:
            self.dedup.mark_sent(match)
            self._sent += 1
        else:
            self._failed += 1
            _send_to_dlq(match, result.error or "unknown")

        return result

    def process_batch(self, batch: List[MatchResult]) -> None:
        """
        Process a batch of match results concurrently.

        Dedup / cap filtering runs first (single-threaded, fast Redis ops),
        then email sending runs in the thread pool.
        """
        eligible = self.dedup.filter_batch(batch)
        self._skipped += len(batch) - len(eligible)

        if not eligible:
            return

        with ThreadPoolExecutor(max_workers=self.concurrency) as pool:
            futures = {pool.submit(self._process_one, m): m for m in eligible}
            for fut in as_completed(futures):
                match = futures[fut]
                try:
                    fut.result()
                except Exception as exc:
                    logger.error("Unexpected error processing %s: %s", match.match_id, exc)
                    self._failed += 1

        logger.info(
            "Batch done: sent=%d skipped=%d failed=%d (cumulative: sent=%d skipped=%d failed=%d)",
            len(eligible) - self._failed, len(batch) - len(eligible), self._failed,
            self._sent, self._skipped, self._failed,
        )

    def run(
        self,
        consumer: Optional[MatchConsumer] = None,
        batch_size: int = 250,
    ) -> None:
        """
        Blocking run loop.  Consumes from Kafka until interrupted.

        consumer : If None, creates a new MatchConsumer with default settings.
        """
        consumer = consumer or MatchConsumer()
        logger.info("EmailWorker starting (concurrency=%d, batch_size=%d).", self.concurrency, batch_size)
        try:
            for batch in consumer.iter_batches(batch_size=batch_size):
                self.process_batch(batch)
        except KeyboardInterrupt:
            logger.info("EmailWorker shutting down.")
        finally:
            consumer.close()

    @property
    def stats(self) -> dict:
        return {
            "sent":    self._sent,
            "skipped": self._skipped,
            "failed":  self._failed,
        }
