"""
Sharded FAISS index management.

Scale context
-------------
100 B candidates × 256 floats × 4 bytes = ~100 TB raw.
We shard this across 1 000 FAISS IVF_PQ indices, each holding ~100 M vectors.
  - IVF with 4 096 centroids → fast coarse quantisation.
  - PQ with 32 sub-quantizers × 8 bits → 32 bytes/vector → 3.2 GB/shard.
  - Total storage: ~3.2 TB — fits on 10 standard SSDs or cheap object storage.

In production each shard lives in an S3/GCS bucket and is memory-mapped by
the matching workers.  Here we implement a local-file version that is fully
functional for smaller datasets and acts as the interface contract for the
production deployment.

For the online nprobe=64 search over 100 M vectors per shard, each query
takes ~50 ms on a single CPU core.  With 16 parallel shard workers per
matching-service replica and 200 replicas, throughput is ~64 000 queries/s,
which comfortably covers 10 M jobs processed in a nightly batch.
"""

from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import numpy as np

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Optional FAISS import — fall back to a brute-force NumPy backend so the
# module works in environments without the faiss-cpu package.
# ---------------------------------------------------------------------------
try:
    import faiss as _faiss
    USING_FAISS = True
    logger.info("FAISS available — using IVF_PQ indices.")
except ImportError:
    _faiss = None  # type: ignore[assignment]
    USING_FAISS = False
    logger.warning(
        "faiss-cpu not installed — using brute-force NumPy fallback. "
        "Install with: pip install faiss-cpu"
    )


# ---------------------------------------------------------------------------
# Brute-force fallback (exact cosine search over float32 arrays)
# ---------------------------------------------------------------------------

class _NumpyIndex:
    """In-memory exact cosine index — for development/testing only."""

    def __init__(self, dim: int):
        self.dim = dim
        self._vectors: Optional[np.ndarray] = None  # (N, dim)
        self._ids: Optional[np.ndarray] = None       # (N,) int64

    def add_with_ids(self, vectors: np.ndarray, ids: np.ndarray) -> None:
        if self._vectors is None:
            self._vectors = vectors.copy()
            self._ids = ids.copy()
        else:
            self._vectors = np.vstack([self._vectors, vectors])
            self._ids = np.concatenate([self._ids, ids])

    def search(self, query: np.ndarray, k: int) -> Tuple[np.ndarray, np.ndarray]:
        if self._vectors is None or len(self._vectors) == 0:
            return np.array([[]], dtype=np.float32), np.array([[]], dtype=np.int64)
        # Cosine similarity (vectors are already L2-normalised).
        scores = (self._vectors @ query.T).squeeze(-1)  # (N,)
        k = min(k, len(scores))
        top_idx = np.argpartition(scores, -k)[-k:]
        top_idx = top_idx[np.argsort(scores[top_idx])[::-1]]
        return scores[top_idx].reshape(1, -1), self._ids[top_idx].reshape(1, -1)

    @property
    def ntotal(self) -> int:
        return len(self._vectors) if self._vectors is not None else 0


# ---------------------------------------------------------------------------
# Single-shard wrapper
# ---------------------------------------------------------------------------

class FaissShardIndex:
    """
    Wraps one shard (IVF_PQ or NumPy fallback).

    The shard ID is part of the candidate_id namespace: candidate integer IDs
    are local to their shard.  The mapping from global candidate_id (UUID) to
    (shard_id, local_int_id) is maintained externally in the database.
    """

    def __init__(
        self,
        shard_id: int,
        dim: int = 256,
        nlist: int = 4096,
        m_pq: int = 32,
        nprobe: int = 64,
        index_path: Optional[str] = None,
    ):
        self.shard_id = shard_id
        self.dim = dim
        self.nprobe = nprobe
        self.index_path = index_path
        self._index = self._load_or_create(nlist, m_pq)

    # ------------------------------------------------------------------
    def _load_or_create(self, nlist: int, m_pq: int):
        if self.index_path and os.path.exists(self.index_path):
            return self._load()
        return self._create_empty(nlist, m_pq)

    def _create_empty(self, nlist: int, m_pq: int):
        if USING_FAISS:
            quantizer = _faiss.IndexFlatIP(self.dim)
            index = _faiss.IndexIVFPQ(quantizer, self.dim, nlist, m_pq, 8)
            index = _faiss.IndexIDMap(index)
            return index
        return _NumpyIndex(self.dim)

    def _load(self):
        if USING_FAISS:
            index = _faiss.read_index(self.index_path)
            if hasattr(index, "nprobe"):
                index.nprobe = self.nprobe
            return index
        raise RuntimeError("Cannot load FAISS index without faiss-cpu installed.")

    # ------------------------------------------------------------------
    def train(self, vectors: np.ndarray) -> None:
        """Train IVF quantizer.  Must be called before add() on a fresh index."""
        if USING_FAISS and not self._index.is_trained:
            inner = _faiss.downcast_index(self._index.index)
            inner.train(vectors)
            logger.info("Shard %d: trained IVF on %d vectors.", self.shard_id, len(vectors))

    def add(self, vectors: np.ndarray, ids: np.ndarray) -> None:
        """
        Add L2-normalised float32 vectors with explicit int64 IDs.

        Parameters
        ----------
        vectors : ndarray, shape (N, dim)
        ids     : ndarray, shape (N,), dtype int64 — local shard row IDs
        """
        assert vectors.shape[1] == self.dim
        assert vectors.dtype == np.float32
        if USING_FAISS:
            self._index.add_with_ids(vectors, ids.astype(np.int64))
        else:
            self._index.add_with_ids(vectors, ids.astype(np.int64))
        logger.debug("Shard %d: added %d vectors (total=%d).", self.shard_id, len(vectors), self.ntotal)

    def search(self, query_vec: np.ndarray, top_k: int = 100) -> Tuple[np.ndarray, np.ndarray]:
        """
        Returns (scores, local_ids) arrays of shape (top_k,).

        Scores are cosine similarities in [0, 1] for normalised vectors.
        """
        if self.ntotal == 0:
            return np.array([], dtype=np.float32), np.array([], dtype=np.int64)
        q = query_vec.reshape(1, -1).astype(np.float32)
        if USING_FAISS:
            if hasattr(self._index, "nprobe"):
                self._index.nprobe = self.nprobe
            scores, ids = self._index.search(q, min(top_k, self.ntotal))
        else:
            scores, ids = self._index.search(q, min(top_k, self.ntotal))
        return scores.flatten(), ids.flatten()

    def save(self) -> None:
        if self.index_path and USING_FAISS:
            Path(self.index_path).parent.mkdir(parents=True, exist_ok=True)
            _faiss.write_index(self._index, self.index_path)
            logger.info("Shard %d: saved to %s.", self.shard_id, self.index_path)

    @property
    def ntotal(self) -> int:
        return self._index.ntotal


# ---------------------------------------------------------------------------
# Shard manager — coordinates across all shards
# ---------------------------------------------------------------------------

class ShardedFaissIndex:
    """
    Manages the full set of FAISS shards.

    In production each shard runs in a separate process / pod and communicates
    over gRPC.  This class represents the co-located single-process version
    used for batch jobs and testing.
    """

    def __init__(
        self,
        num_shards: int = 1000,
        dim: int = 256,
        index_dir: str = "/data/faiss",
        nprobe: int = 64,
        top_k: int = 500,
    ):
        self.num_shards = num_shards
        self.dim = dim
        self.index_dir = index_dir
        self.nprobe = nprobe
        self.top_k = top_k
        self._shards: Dict[int, FaissShardIndex] = {}

    def _get_shard(self, shard_id: int) -> FaissShardIndex:
        if shard_id not in self._shards:
            path = os.path.join(self.index_dir, f"shard_{shard_id:04d}.faiss")
            self._shards[shard_id] = FaissShardIndex(
                shard_id=shard_id,
                dim=self.dim,
                nprobe=self.nprobe,
                index_path=path,
            )
        return self._shards[shard_id]

    def add_candidates(
        self,
        vectors: np.ndarray,
        local_ids: np.ndarray,
        shard_id: int,
    ) -> None:
        shard = self._get_shard(shard_id)
        shard.add(vectors, local_ids)

    def search_all_shards(
        self,
        query_vec: np.ndarray,
        top_k: Optional[int] = None,
        shard_ids: Optional[List[int]] = None,
    ) -> List[Tuple[float, int, int]]:
        """
        Search across specified shards (or all loaded shards).

        Returns a list of (score, shard_id, local_id) sorted by score desc,
        capped at top_k.
        """
        k = top_k or self.top_k
        target_shards = shard_ids if shard_ids is not None else list(self._shards.keys())
        results: List[Tuple[float, int, int]] = []

        for sid in target_shards:
            shard = self._get_shard(sid)
            if shard.ntotal == 0:
                continue
            scores, ids = shard.search(query_vec, top_k=k)
            for score, local_id in zip(scores, ids):
                if local_id >= 0:
                    results.append((float(score), sid, int(local_id)))

        results.sort(key=lambda x: x[0], reverse=True)
        return results[:k]

    def save_all(self) -> None:
        for shard in self._shards.values():
            shard.save()
