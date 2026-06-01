"""
Core matching engine.

Flow for a single job
---------------------
1. Embed job text → query_vec (256-dim, L2-normalised).
2. Fan out query to all relevant FAISS shards in parallel.
3. Collect top-K (score, shard_id, local_id) tuples.
4. Hydrate candidate metadata from the DB using the (shard_id, local_id)
   mapping to resolve UUIDs, then fetch name/email.
5. Apply hard filters (salary, location, employment type) on the hydrated set.
6. Emit MatchResult objects to Kafka.

Throughput math
---------------
- 10 M jobs × top_K=500 candidates = 5 B match records to emit.
- With 200 matching-service replicas each processing 50 000 jobs, and
  fanout across 1 000 shards taking ~500 ms / job, total wall time ≈
  50 000 × 500 ms / (200 replicas × 16 threads) ≈ ~780 seconds ≈ 13 minutes
  for the full sweep.  Acceptable for a nightly batch.
"""

from __future__ import annotations

import logging
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Callable, Dict, Iterable, List, Optional, Tuple

import numpy as np

from config.settings import settings
from matching.embedder import embed_single_job
from matching.faiss_index import ShardedFaissIndex
from models.schemas import (
    Candidate,
    Job,
    MatchResult,
    MatchStatus,
)

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Match-reason generator
# ---------------------------------------------------------------------------

def _build_match_reasons(job: Job, candidate: Candidate, score: float) -> List[str]:
    reasons: List[str] = []
    matched_skills = set(s.lower() for s in job.required_skills) & set(
        s.lower() for s in candidate.skills
    )
    if matched_skills:
        reasons.append(f"Matched skills: {', '.join(sorted(matched_skills))}")
    if job.experience_level == candidate.experience_level:
        reasons.append(f"Experience level matches: {job.experience_level.value}")
    if job.is_remote and "remote" in [loc.lower() for loc in candidate.preferred_locations]:
        reasons.append("Remote position matches preference")
    if job.location in candidate.preferred_locations:
        reasons.append(f"Location match: {job.location}")
    if job.employment_type in candidate.preferred_employment_types:
        reasons.append(f"Employment type match: {job.employment_type.value}")
    reasons.append(f"Overall profile similarity: {score:.0%}")
    return reasons


# ---------------------------------------------------------------------------
# Hard-filter: apply structured constraints not captured by the embedding
# ---------------------------------------------------------------------------

def _passes_hard_filter(job: Job, candidate: Candidate) -> bool:
    # Employment-type filter.
    if candidate.preferred_employment_types and job.employment_type not in candidate.preferred_employment_types:
        return False
    # Location filter — skip only if candidate has explicit preferences AND
    # neither remote nor job location is preferred.
    if candidate.preferred_locations:
        candidate_locs_lower = [loc.lower() for loc in candidate.preferred_locations]
        if (
            not (job.is_remote and "remote" in candidate_locs_lower)
            and job.location.lower() not in candidate_locs_lower
        ):
            return False
    # Salary filter — job must fit within candidate's expected range.
    if (
        candidate.preferred_salary_min is not None
        and job.salary_max is not None
        and job.salary_max < candidate.preferred_salary_min
    ):
        return False
    return True


# ---------------------------------------------------------------------------
# CandidateStore interface
# ---------------------------------------------------------------------------

class CandidateStore:
    """
    Abstraction over the candidate metadata store.

    In production this is backed by PostgreSQL + a local cache.
    The interface is kept simple so tests can inject a mock.
    """

    def get_candidates_by_local_ids(
        self,
        shard_id: int,
        local_ids: List[int],
    ) -> List[Candidate]:
        raise NotImplementedError

    def iter_candidate_batches(
        self,
        shard_id: int,
        batch_size: int = 10_000,
    ) -> Iterable[Tuple[List[Candidate], np.ndarray]]:
        """
        Yield (candidates, vectors) batches for building / refreshing the index.
        vectors shape: (batch_size, dim), dtype float32.
        """
        raise NotImplementedError


# ---------------------------------------------------------------------------
# Main matching engine
# ---------------------------------------------------------------------------

class MatchingEngine:
    """
    Orchestrates embedding → ANN search → hard-filter → result emission.

    Parameters
    ----------
    faiss_index     : Pre-loaded ShardedFaissIndex (or compatible).
    candidate_store : CandidateStore implementation.
    result_sink     : Callable that receives a list of MatchResult objects.
                      In production this writes to Kafka.
    """

    def __init__(
        self,
        faiss_index: ShardedFaissIndex,
        candidate_store: CandidateStore,
        result_sink: Callable[[List[MatchResult]], None],
        top_k: int = 500,
        min_score: float = 0.70,
        shard_workers: int = 16,
    ):
        self.faiss_index = faiss_index
        self.candidate_store = candidate_store
        self.result_sink = result_sink
        self.top_k = top_k
        self.min_score = min_score
        self.shard_workers = shard_workers

    def _match_job(self, job: Job) -> List[MatchResult]:
        """Run the full matching pipeline for one job."""
        query_vec = embed_single_job(job, dim=settings.vector.embedding_dim)

        # ANN search across all shards.
        raw_hits = self.faiss_index.search_all_shards(query_vec, top_k=self.top_k)

        # Group hits by shard for efficient bulk hydration.
        by_shard: Dict[int, List[Tuple[float, int]]] = {}
        for score, shard_id, local_id in raw_hits:
            if score >= self.min_score:
                by_shard.setdefault(shard_id, []).append((score, local_id))

        match_results: List[MatchResult] = []

        def _hydrate_shard(shard_id: int, hits: List[Tuple[float, int]]) -> List[MatchResult]:
            score_map = {lid: sc for sc, lid in hits}
            local_ids = list(score_map.keys())
            candidates = self.candidate_store.get_candidates_by_local_ids(shard_id, local_ids)
            shard_results = []
            for candidate in candidates:
                score = score_map.get(local_ids[candidates.index(candidate)], 0.0)
                if not _passes_hard_filter(job, candidate):
                    continue
                shard_results.append(
                    MatchResult(
                        match_id=str(uuid.uuid4()),
                        job_id=job.job_id,
                        candidate_id=candidate.candidate_id,
                        candidate_email=candidate.email,
                        candidate_name=candidate.full_name,
                        job_title=job.title,
                        employer_id=job.employer_id,
                        similarity_score=score,
                        match_reasons=_build_match_reasons(job, candidate, score),
                        status=MatchStatus.PENDING,
                    )
                )
            return shard_results

        with ThreadPoolExecutor(max_workers=self.shard_workers) as pool:
            futures = {
                pool.submit(_hydrate_shard, sid, hits): sid
                for sid, hits in by_shard.items()
            }
            for fut in as_completed(futures):
                try:
                    match_results.extend(fut.result())
                except Exception as exc:
                    logger.error("Shard hydration failed for shard %s: %s", futures[fut], exc)

        match_results.sort(key=lambda r: r.similarity_score, reverse=True)
        return match_results

    def process_jobs(self, jobs: Iterable[Job]) -> int:
        """
        Process an iterable of jobs, emitting match results to the sink.
        Returns total number of matches emitted.
        """
        total = 0
        for job in jobs:
            if not job.is_active:
                continue
            try:
                results = self._match_job(job)
                if results:
                    self.result_sink(results)
                    total += len(results)
                    logger.info(
                        "Job %s (%s): %d matches emitted.", job.job_id, job.title, len(results)
                    )
            except Exception as exc:
                logger.error("Failed to process job %s: %s", job.job_id, exc)
        return total

    def process_jobs_parallel(self, jobs: List[Job], max_workers: int = 8) -> int:
        """Process multiple jobs in parallel using a thread pool."""
        total = 0
        with ThreadPoolExecutor(max_workers=max_workers) as pool:
            futures = {pool.submit(self._match_job, job): job for job in jobs if job.is_active}
            for fut in as_completed(futures):
                job = futures[fut]
                try:
                    results = fut.result()
                    if results:
                        self.result_sink(results)
                        total += len(results)
                except Exception as exc:
                    logger.error("Parallel job processing failed for %s: %s", job.job_id, exc)
        return total
