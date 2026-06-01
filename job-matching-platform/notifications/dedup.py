"""
Redis-backed deduplication and per-candidate rate limiting.

Dedup key schema
----------------
  sent:<job_id>:<candidate_id>   → "1"  (TTL = 30 days)

  This key is set atomically using SET NX EX (SET if Not eXists, with Expire).
  If the key already exists, the email has already been sent and we skip it.

Per-candidate daily cap
-----------------------
  daily_cap:<candidate_id>:<YYYY-MM-DD>  → counter  (TTL = 48 h)

  We allow at most N job-match emails per candidate per day (default 3).
  The counter is incremented atomically; if it exceeds the cap we skip.

Rate limiter (global)
---------------------
  Uses a Redis sliding-window counter keyed by second bucket to cap the total
  outbound email rate across all workers at `rate_limit_rps` emails/second.
  This protects the upstream email provider API from overload.
"""

from __future__ import annotations

import logging
import time
from datetime import datetime, timezone
from typing import List, Optional

from config.settings import settings
from models.schemas import MatchResult

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Optional Redis import
# ---------------------------------------------------------------------------
try:
    import redis as _redis

    USING_REDIS = True
except ImportError:
    _redis = None  # type: ignore[assignment]
    USING_REDIS = False
    logger.warning("redis-py not installed — dedup/rate-limit disabled. pip install redis")


# ---------------------------------------------------------------------------
# In-memory stub (for testing)
# ---------------------------------------------------------------------------

class _InMemoryRedis:
    def __init__(self):
        self._store: dict = {}

    def set(self, key, value, nx=False, ex=None):
        if nx and key in self._store:
            return None
        self._store[key] = value
        return True

    def incr(self, key):
        self._store[key] = self._store.get(key, 0) + 1
        return self._store[key]

    def expire(self, key, seconds):
        pass  # no-op in stub

    def get(self, key):
        return self._store.get(key)

    def pipeline(self):
        return _InMemoryPipeline(self)

    def close(self):
        pass


class _InMemoryPipeline:
    def __init__(self, store: _InMemoryRedis):
        self._store = store
        self._cmds = []

    def incr(self, key):
        self._cmds.append(("incr", key))
        return self

    def expire(self, key, seconds):
        self._cmds.append(("expire", key, seconds))
        return self

    def execute(self):
        results = []
        for cmd in self._cmds:
            if cmd[0] == "incr":
                results.append(self._store.incr(cmd[1]))
            elif cmd[0] == "expire":
                results.append(None)
        self._cmds.clear()
        return results


# ---------------------------------------------------------------------------
# DedupManager
# ---------------------------------------------------------------------------

class DedupManager:
    """
    Centralises all Redis operations for deduplication and rate limiting.

    All methods are safe to call from multiple threads / async tasks.
    """

    SENT_KEY_PREFIX  = "sent"
    DAILY_CAP_PREFIX = "daily_cap"
    RATE_KEY_PREFIX  = "rate"

    def __init__(
        self,
        redis_url: Optional[str] = None,
        dedup_ttl: Optional[int] = None,
        daily_cap: int = 3,
        rate_limit_rps: Optional[int] = None,
        # Pass an explicit client to override auto-connection (useful in tests).
        _redis_client=None,
    ):
        url = redis_url or settings.redis.url
        self.dedup_ttl = dedup_ttl or settings.redis.dedup_ttl_seconds
        self.daily_cap = daily_cap
        self.rate_limit_rps = rate_limit_rps or settings.redis.rate_limit_rps

        if _redis_client is not None:
            self._redis = _redis_client
            logger.info("DedupManager using injected Redis client.")
        elif USING_REDIS:
            self._redis = _redis.from_url(url, decode_responses=True)
            logger.info("DedupManager connected to Redis at %s", url)
        else:
            self._redis = _InMemoryRedis()
            logger.info("DedupManager using in-memory stub.")

    # ------------------------------------------------------------------
    # Dedup
    # ------------------------------------------------------------------

    def _sent_key(self, match: MatchResult) -> str:
        return f"{self.SENT_KEY_PREFIX}:{match.job_id}:{match.candidate_id}"

    def mark_sent(self, match: MatchResult) -> None:
        """Record that this (job, candidate) pair has been emailed."""
        self._redis.set(self._sent_key(match), "1", ex=self.dedup_ttl)

    def is_already_sent(self, match: MatchResult) -> bool:
        return self._redis.get(self._sent_key(match)) is not None

    # ------------------------------------------------------------------
    # Per-candidate daily cap
    # ------------------------------------------------------------------

    def _daily_cap_key(self, candidate_id: str) -> str:
        today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        return f"{self.DAILY_CAP_PREFIX}:{candidate_id}:{today}"

    def increment_and_check_cap(self, candidate_id: str) -> bool:
        """
        Increment the candidate's daily email counter.
        Returns True if the email should proceed (within cap), False if over cap.
        """
        key = self._daily_cap_key(candidate_id)
        pipe = self._redis.pipeline()
        pipe.incr(key)
        pipe.expire(key, 48 * 3600)  # 48 h TTL so midnight rollover works
        results = pipe.execute()
        count = results[0]
        return count <= self.daily_cap

    # ------------------------------------------------------------------
    # Global rate limiter — sliding window per second
    # ------------------------------------------------------------------

    def _rate_key(self) -> str:
        bucket = int(time.time())
        return f"{self.RATE_KEY_PREFIX}:{bucket}"

    def acquire_rate_slot(self) -> bool:
        """
        Claim one rate-limit slot for the current second.
        Returns True if the slot was granted, False if the per-second cap is
        already exhausted.

        Callers should back off and retry when False is returned.
        """
        key = self._rate_key()
        pipe = self._redis.pipeline()
        pipe.incr(key)
        pipe.expire(key, 2)   # keep the bucket for 2 seconds to avoid race at boundary
        results = pipe.execute()
        count = results[0]
        return count <= self.rate_limit_rps

    # ------------------------------------------------------------------
    # Batch filter: remove already-sent and over-cap results
    # ------------------------------------------------------------------

    def filter_batch(self, results: List[MatchResult]) -> List[MatchResult]:
        """
        Return the subset of `results` that should proceed to email sending.
        Mutates nothing — callers must call `mark_sent` after successful send.
        """
        eligible = []
        for r in results:
            if self.is_already_sent(r):
                logger.debug("Skipping duplicate (job=%s, candidate=%s).", r.job_id, r.candidate_id)
                continue
            if not self.increment_and_check_cap(r.candidate_id):
                logger.debug("Daily cap hit for candidate %s.", r.candidate_id)
                continue
            eligible.append(r)
        return eligible
