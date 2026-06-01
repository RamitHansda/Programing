"""
Email worker entry point.

Usage
-----
  # Normal operation (requires Kafka + Redis + email provider):
  python -m scripts.run_email_worker

  # Local smoke test with stub data:
  python -m scripts.run_email_worker --stub
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from config.settings import settings
from notifications.dedup import DedupManager, _InMemoryRedis
from notifications.sender import StubProvider, create_provider
from notifications.worker import EmailWorker
from models.schemas import (
    EmploymentType,
    ExperienceLevel,
    MatchResult,
    MatchStatus,
)
from messaging.kafka_consumer import MatchConsumer

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-8s %(name)s — %(message)s",
)
logger = logging.getLogger("run_email_worker")


def _stub_match_results():
    return [
        MatchResult(
            match_id="m-001",
            job_id="job-001",
            candidate_id="cand-001",
            candidate_email="alice@example.com",
            candidate_name="Alice Smith",
            job_title="Senior ML Engineer",
            employer_id="emp-A",
            similarity_score=0.92,
            match_reasons=[
                "Matched skills: python, machine learning, pytorch",
                "Experience level matches: senior",
                "Remote position matches preference",
                "Overall profile similarity: 92%",
            ],
            status=MatchStatus.PENDING,
        ).to_dict(),
        MatchResult(
            match_id="m-002",
            job_id="job-002",
            candidate_id="cand-002",
            candidate_email="bob@example.com",
            candidate_name="Bob Jones",
            job_title="Backend Engineer (Java / Kafka)",
            employer_id="emp-B",
            similarity_score=0.87,
            match_reasons=[
                "Matched skills: java, kafka, spring boot",
                "Experience level matches: lead",
                "Location match: New York",
                "Overall profile similarity: 87%",
            ],
            status=MatchStatus.PENDING,
        ).to_dict(),
    ]


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the email notification worker.")
    parser.add_argument("--stub", action="store_true",
                        help="Use in-memory stub data and stub email provider.")
    parser.add_argument("--concurrency", type=int,
                        default=settings.email.worker_concurrency)
    parser.add_argument("--batch-size", type=int, default=250)
    args = parser.parse_args()

    provider = StubProvider() if args.stub else create_provider()
    # In stub mode use the in-memory Redis so no live Redis connection is needed.
    dedup    = DedupManager(_redis_client=_InMemoryRedis() if args.stub else None)
    worker   = EmailWorker(
        provider=provider,
        dedup=dedup,
        concurrency=args.concurrency,
        max_retries=settings.email.max_retries,
        backoff_base=settings.email.retry_backoff_base,
    )

    if args.stub:
        consumer = MatchConsumer(_stub_messages=_stub_match_results())
    else:
        consumer = MatchConsumer()

    logger.info(
        "Starting email worker (provider=%s, concurrency=%d).",
        type(provider).__name__, args.concurrency,
    )
    worker.run(consumer=consumer, batch_size=args.batch_size)
    logger.info("Worker stats: %s", worker.stats)


if __name__ == "__main__":
    main()
