"""
Unit tests for the email pipeline (no external services required).
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from notifications.dedup import DedupManager, _InMemoryRedis
from notifications.sender import SendResult, StubProvider, send_match_email
from notifications.templates import render_html, render_plain, render_subject
from notifications.worker import EmailWorker
from models.schemas import MatchResult, MatchStatus
from messaging.kafka_consumer import MatchConsumer


# ---------------------------------------------------------------------------
# Fixture
# ---------------------------------------------------------------------------

@pytest.fixture()
def sample_match() -> MatchResult:
    return MatchResult(
        match_id="m-001",
        job_id="job-001",
        candidate_id="cand-001",
        candidate_email="alice@example.com",
        candidate_name="Alice Smith",
        job_title="Senior ML Engineer",
        employer_id="emp-A",
        similarity_score=0.92,
        match_reasons=[
            "Matched skills: python, pytorch",
            "Experience level matches: senior",
            "Remote position matches preference",
            "Overall profile similarity: 92%",
        ],
        status=MatchStatus.PENDING,
    )


# ---------------------------------------------------------------------------
# Template tests
# ---------------------------------------------------------------------------

class TestTemplates:
    def test_subject_contains_score(self, sample_match):
        subj = render_subject(sample_match)
        assert "92%" in subj
        assert "Senior ML Engineer" in subj

    def test_html_contains_name(self, sample_match):
        html = render_html(sample_match)
        assert "Alice Smith" in html
        assert "92" in html
        assert "jobmatch.example.com" in html

    def test_plain_contains_apply_url(self, sample_match):
        plain = render_plain(sample_match)
        assert "job-001" in plain
        assert "Apply here:" in plain

    def test_html_contains_all_reasons(self, sample_match):
        html = render_html(sample_match)
        for reason in sample_match.match_reasons:
            assert reason in html

    def test_unsubscribe_link_present(self, sample_match):
        html = render_html(sample_match)
        assert "unsubscribe" in html.lower()


# ---------------------------------------------------------------------------
# Sender tests
# ---------------------------------------------------------------------------

class TestSender:
    def test_stub_provider_succeeds(self, sample_match):
        provider = StubProvider()
        result = send_match_email(sample_match, provider, max_retries=1)
        assert result.success is True
        assert sample_match.status == MatchStatus.EMAIL_SENT
        assert len(provider.sent) == 1

    def test_stub_provider_records_email(self, sample_match):
        provider = StubProvider()
        send_match_email(sample_match, provider)
        assert provider.sent[0]["to"] == "alice@example.com"

    def test_failing_provider_marks_failed(self, sample_match):
        class AlwaysFailProvider(StubProvider):
            def send(self, *args, **kwargs) -> SendResult:
                return SendResult(success=False, error="503 Service Unavailable")

        provider = AlwaysFailProvider()
        result = send_match_email(sample_match, provider, max_retries=2, backoff_base=0.0)
        assert result.success is False
        assert result.attempts == 2
        assert sample_match.status == MatchStatus.EMAIL_FAILED

    def test_retry_succeeds_on_second_attempt(self, sample_match):
        call_count = {"n": 0}

        class FlakyProvider(StubProvider):
            def send(self, *args, **kwargs) -> SendResult:
                call_count["n"] += 1
                if call_count["n"] == 1:
                    return SendResult(success=False, error="transient error")
                return SendResult(success=True, provider_message_id="ok-123")

        provider = FlakyProvider()
        result = send_match_email(sample_match, provider, max_retries=3, backoff_base=0.0)
        assert result.success is True
        assert result.attempts == 2
        assert call_count["n"] == 2


# ---------------------------------------------------------------------------
# Dedup tests
# ---------------------------------------------------------------------------

class TestDedup:
    def test_first_send_allowed(self, sample_match):
        dedup = DedupManager(daily_cap=5, _redis_client=_InMemoryRedis())
        batch = dedup.filter_batch([sample_match])
        assert len(batch) == 1

    def test_duplicate_filtered(self, sample_match):
        dedup = DedupManager(_redis_client=_InMemoryRedis())
        dedup.mark_sent(sample_match)
        batch = dedup.filter_batch([sample_match])
        assert len(batch) == 0

    def test_daily_cap_enforced(self):
        dedup = DedupManager(daily_cap=2, _redis_client=_InMemoryRedis())
        results = [
            MatchResult(
                match_id=f"m-{i}",
                job_id=f"job-{i}",
                candidate_id="cand-cap",
                candidate_email="cap@example.com",
                candidate_name="Cap Test",
                job_title=f"Job {i}",
                employer_id="emp-X",
                similarity_score=0.8,
                match_reasons=[],
            )
            for i in range(5)
        ]
        eligible = dedup.filter_batch(results)
        assert len(eligible) == 2  # daily cap = 2

    def test_rate_slot_granted(self, sample_match):
        dedup = DedupManager(rate_limit_rps=1000, _redis_client=_InMemoryRedis())
        assert dedup.acquire_rate_slot() is True


# ---------------------------------------------------------------------------
# Worker integration test
# ---------------------------------------------------------------------------

class TestEmailWorker:
    def test_worker_processes_stub_batch(self, sample_match):
        provider = StubProvider()
        dedup    = DedupManager(_redis_client=_InMemoryRedis())
        worker   = EmailWorker(provider=provider, dedup=dedup, concurrency=2)
        worker.process_batch([sample_match])
        assert worker.stats["sent"] == 1
        assert worker.stats["failed"] == 0

    def test_worker_skips_duplicates(self, sample_match):
        provider = StubProvider()
        dedup    = DedupManager(_redis_client=_InMemoryRedis())
        dedup.mark_sent(sample_match)
        worker = EmailWorker(provider=provider, dedup=dedup, concurrency=2)
        worker.process_batch([sample_match])
        assert worker.stats["sent"] == 0
        assert worker.stats["skipped"] == 1

    def test_worker_run_with_consumer(self, sample_match):
        provider = StubProvider()
        dedup    = DedupManager(_redis_client=_InMemoryRedis())
        worker   = EmailWorker(provider=provider, dedup=dedup, concurrency=2)
        consumer = MatchConsumer(_stub_messages=[sample_match.to_dict()])
        worker.run(consumer=consumer, batch_size=10)
        assert worker.stats["sent"] == 1


# ---------------------------------------------------------------------------
# MatchResult serialisation
# ---------------------------------------------------------------------------

class TestMatchResultSerde:
    def test_round_trip(self, sample_match):
        d = sample_match.to_dict()
        restored = MatchResult.from_dict(d)
        assert restored.match_id == sample_match.match_id
        assert restored.similarity_score == sample_match.similarity_score
        assert restored.status == sample_match.status
        assert restored.match_reasons == sample_match.match_reasons
