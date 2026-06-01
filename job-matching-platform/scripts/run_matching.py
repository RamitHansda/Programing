"""
Matching service entry point — processes all active jobs and emits match
results to Kafka.

This runs as a Kubernetes Job (nightly batch) or a long-running Deployment
(continuous mode, re-processes jobs as they are posted).

Usage
-----
  # Nightly full sweep (all active jobs, all shards):
  python -m scripts.run_matching

  # Continuous mode — poll for new jobs every 60 s:
  python -m scripts.run_matching --mode continuous --poll-interval 60

  # Local smoke test with stub data:
  python -m scripts.run_matching --dry-run --stub
"""

from __future__ import annotations

import argparse
import logging
import sys
import time
from pathlib import Path
from typing import List

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from config.settings import settings
from matching.faiss_index import ShardedFaissIndex
from matching.matcher import CandidateStore, MatchingEngine
from models.schemas import (
    Candidate,
    EmploymentType,
    ExperienceLevel,
    Job,
    MatchResult,
)
from messaging.kafka_producer import MatchProducer

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-8s %(name)s — %(message)s",
)
logger = logging.getLogger("run_matching")


# ---------------------------------------------------------------------------
# Stub implementations (replace with real DB/cache in production)
# ---------------------------------------------------------------------------

class StubCandidateStore(CandidateStore):
    """Returns a tiny in-memory dataset for smoke testing."""

    _CANDIDATES: List[Candidate] = [
        Candidate(
            candidate_id="cand-001",
            email="alice@example.com",
            full_name="Alice Smith",
            skills=["python", "machine learning", "sql", "pytorch"],
            experience_years=5.0,
            experience_level=ExperienceLevel.SENIOR,
            preferred_locations=["remote", "San Francisco"],
            preferred_employment_types=[EmploymentType.FULL_TIME],
            preferred_salary_min=120_000,
        ),
        Candidate(
            candidate_id="cand-002",
            email="bob@example.com",
            full_name="Bob Jones",
            skills=["java", "spring boot", "kafka", "kubernetes"],
            experience_years=7.0,
            experience_level=ExperienceLevel.LEAD,
            preferred_locations=["New York", "remote"],
            preferred_employment_types=[EmploymentType.FULL_TIME, EmploymentType.CONTRACT],
        ),
        Candidate(
            candidate_id="cand-003",
            email="carol@example.com",
            full_name="Carol Wu",
            skills=["python", "fastapi", "postgresql", "docker"],
            experience_years=3.0,
            experience_level=ExperienceLevel.MID,
            preferred_locations=["remote"],
            preferred_employment_types=[EmploymentType.FULL_TIME],
        ),
    ]

    def get_candidates_by_local_ids(self, shard_id: int, local_ids: List[int]) -> List[Candidate]:
        return [self._CANDIDATES[i % len(self._CANDIDATES)] for i in local_ids]


class _CollectingSink:
    """Collects results instead of writing to Kafka — used in dry-run mode."""

    def __init__(self):
        self.results: List[MatchResult] = []

    def __call__(self, batch: List[MatchResult]) -> None:
        self.results.extend(batch)
        for r in batch:
            logger.info(
                "  MATCH: job=%s candidate=%s (%s) score=%.2f",
                r.job_id, r.candidate_name, r.candidate_email, r.similarity_score,
            )


def _stub_jobs() -> List[Job]:
    return [
        Job(
            job_id="job-001",
            employer_id="emp-A",
            title="Senior ML Engineer",
            description="Build and productionise machine learning models using Python and PyTorch.",
            required_skills=["python", "machine learning", "pytorch"],
            preferred_skills=["sql", "spark", "kubernetes"],
            experience_level=ExperienceLevel.SENIOR,
            employment_type=EmploymentType.FULL_TIME,
            location="San Francisco",
            is_remote=True,
            salary_min=130_000,
            salary_max=200_000,
        ),
        Job(
            job_id="job-002",
            employer_id="emp-B",
            title="Backend Engineer (Java / Kafka)",
            description="Design and operate high-throughput event-driven microservices.",
            required_skills=["java", "kafka", "spring boot"],
            preferred_skills=["kubernetes", "postgresql"],
            experience_level=ExperienceLevel.LEAD,
            employment_type=EmploymentType.FULL_TIME,
            location="New York",
            is_remote=False,
        ),
    ]


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def run(args: argparse.Namespace) -> None:
    # ----- Vector index -----
    faiss_index = ShardedFaissIndex(
        num_shards=settings.vector.num_shards,
        dim=settings.vector.embedding_dim,
        index_dir=settings.vector.index_dir,
        nprobe=settings.vector.nprobe,
        top_k=settings.vector.top_k,
    )

    if args.stub:
        # Load a tiny in-memory index with the stub candidates.
        from matching.embedder import embed_candidates
        import numpy as np

        stub_store = StubCandidateStore()
        vecs = embed_candidates(stub_store._CANDIDATES, dim=settings.vector.embedding_dim)
        ids  = np.arange(len(stub_store._CANDIDATES), dtype=np.int64)
        faiss_index.add_candidates(vecs, ids, shard_id=0)
    else:
        stub_store = None  # type: ignore[assignment]

    candidate_store = stub_store or StubCandidateStore()

    # ----- Sink -----
    if args.dry_run:
        sink = _CollectingSink()
        result_sink = sink
    else:
        producer = MatchProducer()
        result_sink = producer.publish  # type: ignore[assignment]

    # ----- Engine -----
    # In stub mode, hash-based embeddings have no semantic meaning so we
    # disable the score threshold to ensure results flow end-to-end.
    effective_min_score = 0.0 if args.stub else settings.vector.min_score
    engine = MatchingEngine(
        faiss_index=faiss_index,
        candidate_store=candidate_store,
        result_sink=result_sink,
        top_k=settings.vector.top_k,
        min_score=effective_min_score,
        shard_workers=settings.matching.shard_workers,
    )

    # ----- Job source -----
    jobs = _stub_jobs() if args.stub else _stub_jobs()  # replace with DB reader

    if args.mode == "batch":
        logger.info("Running batch matching for %d jobs.", len(jobs))
        total = engine.process_jobs(jobs)
        logger.info("Batch complete: %d total matches emitted.", total)

    elif args.mode == "continuous":
        logger.info("Running in continuous mode (poll every %ds).", args.poll_interval)
        while True:
            total = engine.process_jobs(jobs)
            logger.info("Sweep complete: %d matches. Sleeping %ds.", total, args.poll_interval)
            time.sleep(args.poll_interval)

    if args.dry_run and isinstance(result_sink, _CollectingSink):
        logger.info("Dry-run summary: %d matches collected.", len(result_sink.results))


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the job-candidate matching service.")
    parser.add_argument("--mode", choices=["batch", "continuous"], default="batch")
    parser.add_argument("--poll-interval", type=int, default=60,
                        help="Seconds between sweeps in continuous mode.")
    parser.add_argument("--dry-run", action="store_true",
                        help="Do not write to Kafka; print matches instead.")
    parser.add_argument("--stub", action="store_true",
                        help="Use in-memory stub data (no DB/Kafka required).")
    args = parser.parse_args()
    run(args)


if __name__ == "__main__":
    main()
