"""
Unit tests for the matching pipeline (no external services required).

Run with:
  cd job-matching-platform
  pytest tests/ -v
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from matching.embedder import embed_candidates, embed_jobs, embed_single_job
from matching.faiss_index import FaissShardIndex, ShardedFaissIndex
from matching.matcher import CandidateStore, MatchingEngine, _passes_hard_filter
from models.schemas import (
    Candidate,
    EmploymentType,
    ExperienceLevel,
    Job,
    MatchResult,
    MatchStatus,
)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture()
def senior_ml_candidate() -> Candidate:
    return Candidate(
        candidate_id="cand-001",
        email="alice@example.com",
        full_name="Alice Smith",
        skills=["python", "machine learning", "pytorch", "sql"],
        experience_years=6.0,
        experience_level=ExperienceLevel.SENIOR,
        preferred_locations=["remote", "San Francisco"],
        preferred_employment_types=[EmploymentType.FULL_TIME],
        preferred_salary_min=120_000,
    )


@pytest.fixture()
def java_lead_candidate() -> Candidate:
    return Candidate(
        candidate_id="cand-002",
        email="bob@example.com",
        full_name="Bob Jones",
        skills=["java", "kafka", "spring boot", "kubernetes"],
        experience_years=8.0,
        experience_level=ExperienceLevel.LEAD,
        preferred_locations=["New York"],
        preferred_employment_types=[EmploymentType.FULL_TIME],
    )


@pytest.fixture()
def ml_job() -> Job:
    return Job(
        job_id="job-001",
        employer_id="emp-A",
        title="Senior ML Engineer",
        description="Build production ML pipelines with Python and PyTorch.",
        required_skills=["python", "machine learning", "pytorch"],
        preferred_skills=["sql", "spark"],
        experience_level=ExperienceLevel.SENIOR,
        employment_type=EmploymentType.FULL_TIME,
        location="San Francisco",
        is_remote=True,
        salary_min=130_000,
        salary_max=200_000,
    )


@pytest.fixture()
def java_job() -> Job:
    return Job(
        job_id="job-002",
        employer_id="emp-B",
        title="Java Backend Engineer",
        description="Event-driven microservices with Kafka.",
        required_skills=["java", "kafka"],
        preferred_skills=["spring boot"],
        experience_level=ExperienceLevel.LEAD,
        employment_type=EmploymentType.FULL_TIME,
        location="New York",
        is_remote=False,
    )


# ---------------------------------------------------------------------------
# Embedder tests
# ---------------------------------------------------------------------------

class TestEmbedder:
    def test_embed_candidates_shape(self, senior_ml_candidate, java_lead_candidate):
        vecs = embed_candidates([senior_ml_candidate, java_lead_candidate], dim=64)
        assert vecs.shape == (2, 64)
        assert vecs.dtype == np.float32

    def test_embed_candidates_normalised(self, senior_ml_candidate):
        vecs = embed_candidates([senior_ml_candidate], dim=64)
        norm = np.linalg.norm(vecs[0])
        assert abs(norm - 1.0) < 1e-4, f"Expected unit vector, got norm={norm}"

    def test_embed_jobs_shape(self, ml_job):
        vecs = embed_jobs([ml_job], dim=64)
        assert vecs.shape == (1, 64)

    def test_embed_single_job(self, ml_job):
        vec = embed_single_job(ml_job, dim=64)
        assert vec.shape == (64,)

    def test_similar_profiles_closer(self, senior_ml_candidate, java_lead_candidate, ml_job):
        from matching.embedder import USING_REAL_MODEL
        if not USING_REAL_MODEL:
            pytest.skip("Semantic similarity test requires sentence-transformers.")
        ml_vec  = embed_single_job(ml_job, dim=64)
        alice_v = embed_candidates([senior_ml_candidate], dim=64)[0]
        bob_v   = embed_candidates([java_lead_candidate], dim=64)[0]
        score_alice = float(ml_vec @ alice_v)
        score_bob   = float(ml_vec @ bob_v)
        # Alice is a better fit for the ML job than Bob.
        assert score_alice > score_bob, (
            f"Expected Alice ({score_alice:.3f}) > Bob ({score_bob:.3f}) for ML job"
        )


# ---------------------------------------------------------------------------
# FAISS index tests
# ---------------------------------------------------------------------------

class TestFaissShardIndex:
    def test_add_and_search(self):
        idx = FaissShardIndex(shard_id=0, dim=16)
        vecs = np.random.randn(50, 16).astype(np.float32)
        vecs /= np.linalg.norm(vecs, axis=1, keepdims=True)
        ids  = np.arange(50, dtype=np.int64)
        idx.add(vecs, ids)
        assert idx.ntotal == 50

        query = vecs[0:1]
        scores, returned_ids = idx.search(query, top_k=5)
        assert len(scores) == 5
        # The top result should be the query vector itself.
        assert int(returned_ids[0]) == 0
        assert scores[0] > 0.99

    def test_empty_shard_returns_empty(self):
        idx = FaissShardIndex(shard_id=99, dim=16)
        scores, ids = idx.search(np.zeros(16, dtype=np.float32), top_k=5)
        assert len(scores) == 0


class TestShardedFaissIndex:
    def test_multi_shard_search(self):
        sidx = ShardedFaissIndex(num_shards=4, dim=16, index_dir="/tmp/faiss_test")
        for shard_id in range(4):
            vecs = np.random.randn(20, 16).astype(np.float32)
            vecs /= np.linalg.norm(vecs, axis=1, keepdims=True)
            ids  = np.arange(20, dtype=np.int64)
            sidx.add_candidates(vecs, ids, shard_id=shard_id)

        query = np.random.randn(16).astype(np.float32)
        query /= np.linalg.norm(query)
        results = sidx.search_all_shards(query, top_k=10)
        assert len(results) <= 10
        # Results should be sorted by score descending.
        scores = [r[0] for r in results]
        assert scores == sorted(scores, reverse=True)


# ---------------------------------------------------------------------------
# Hard-filter tests
# ---------------------------------------------------------------------------

class TestHardFilter:
    def test_passes_remote(self, senior_ml_candidate, ml_job):
        assert _passes_hard_filter(ml_job, senior_ml_candidate) is True

    def test_fails_location_mismatch(self, senior_ml_candidate, java_job):
        # Alice wants remote or SF; java_job is NY non-remote.
        assert _passes_hard_filter(java_job, senior_ml_candidate) is False

    def test_fails_salary_too_low(self, senior_ml_candidate):
        low_salary_job = Job(
            job_id="job-low",
            employer_id="emp-X",
            title="ML Intern",
            description="Low pay ML position.",
            required_skills=["python"],
            preferred_skills=[],
            experience_level=ExperienceLevel.INTERN,
            employment_type=EmploymentType.FULL_TIME,
            location="remote",
            is_remote=True,
            salary_max=50_000,  # below Alice's min of 120k
        )
        assert _passes_hard_filter(low_salary_job, senior_ml_candidate) is False

    def test_fails_employment_type_mismatch(self, senior_ml_candidate):
        contract_job = Job(
            job_id="job-ct",
            employer_id="emp-X",
            title="ML Contractor",
            description="Contract ML role.",
            required_skills=["python"],
            preferred_skills=[],
            experience_level=ExperienceLevel.SENIOR,
            employment_type=EmploymentType.CONTRACT,  # Alice only wants FULL_TIME
            location="remote",
            is_remote=True,
        )
        assert _passes_hard_filter(contract_job, senior_ml_candidate) is False


# ---------------------------------------------------------------------------
# Matching engine integration test (no Kafka / DB required)
# ---------------------------------------------------------------------------

class _InMemoryStore(CandidateStore):
    def __init__(self, candidates):
        self._candidates = candidates

    def get_candidates_by_local_ids(self, shard_id, local_ids):
        return [self._candidates[i % len(self._candidates)] for i in local_ids]


class TestMatchingEngine:
    def test_end_to_end(self, senior_ml_candidate, java_lead_candidate, ml_job):
        from config.settings import settings as _settings
        dim = _settings.vector.embedding_dim  # use real dim so engine and index agree
        # Build a tiny index.
        faiss_idx = ShardedFaissIndex(num_shards=1, dim=dim, index_dir="/tmp/faiss_e2e")
        candidates = [senior_ml_candidate, java_lead_candidate]
        vecs = embed_candidates(candidates, dim=dim)
        faiss_idx.add_candidates(vecs, np.arange(len(candidates), dtype=np.int64), shard_id=0)

        store = _InMemoryStore(candidates)
        collected: list = []

        engine = MatchingEngine(
            faiss_index=faiss_idx,
            candidate_store=store,
            result_sink=collected.extend,
            top_k=10,
            min_score=0.0,   # accept all in test
            shard_workers=2,
        )
        total = engine.process_jobs([ml_job])
        assert total > 0
        assert all(isinstance(r, MatchResult) for r in collected)
        # All results belong to the processed job.
        assert all(r.job_id == ml_job.job_id for r in collected)
        # Results are sorted by score descending.
        scores = [r.similarity_score for r in collected]
        assert scores == sorted(scores, reverse=True)

    def test_inactive_job_skipped(self, senior_ml_candidate, ml_job):
        ml_job.is_active = False
        faiss_idx = ShardedFaissIndex(num_shards=1, dim=64, index_dir="/tmp/faiss_skip")
        store = _InMemoryStore([senior_ml_candidate])
        collected: list = []
        engine = MatchingEngine(faiss_idx, store, collected.extend, top_k=5, min_score=0.0)
        total = engine.process_jobs([ml_job])
        assert total == 0
        assert collected == []
