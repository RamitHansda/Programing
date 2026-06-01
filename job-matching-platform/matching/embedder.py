"""
Embedding generation for jobs and candidates.

Architecture note
-----------------
At 100 B candidates we cannot call an external embedding API at query time.
Instead we:
  1. Pre-compute candidate embeddings offline (batch pipeline, run nightly).
  2. Store them in sharded FAISS IVF_PQ indices on cheap object storage (S3/GCS).
  3. At match time, embed each job once and query all relevant shards in
     parallel.

For the embedding model we use a lightweight sentence-transformer
(all-MiniLM-L6-v2, 256-dim).  The same model is used for both candidates and
jobs so vectors live in the same semantic space.

In production you would replace `_encode_text` with a call to a self-hosted
Triton Inference Server or a gRPC micro-service that batches requests.
"""

from __future__ import annotations

import hashlib
import logging
from typing import List, Optional

import numpy as np

from models.schemas import Candidate, Job

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Attempt to import sentence-transformers; fall back to a deterministic
# hash-based stub so the module is importable without the heavy dependency.
# ---------------------------------------------------------------------------
try:
    from sentence_transformers import SentenceTransformer as _SentenceTransformer

    _MODEL: Optional[_SentenceTransformer] = None

    def _get_model(dim: int = 256) -> _SentenceTransformer:
        global _MODEL
        if _MODEL is None:
            # all-MiniLM-L6-v2 produces 384-dim; we project to `dim` below.
            _MODEL = _SentenceTransformer("all-MiniLM-L6-v2")
            logger.info("Loaded sentence-transformer model.")
        return _MODEL

    def _encode_text(texts: List[str], dim: int = 256) -> np.ndarray:
        model = _get_model(dim)
        vecs = model.encode(texts, batch_size=512, show_progress_bar=False, normalize_embeddings=True)
        vecs = np.array(vecs, dtype=np.float32)
        if vecs.shape[1] != dim:
            # Random projection to target dimension (deterministic seed).
            rng = np.random.default_rng(seed=42)
            proj = rng.standard_normal((vecs.shape[1], dim)).astype(np.float32)
            proj /= np.linalg.norm(proj, axis=0, keepdims=True)
            vecs = vecs @ proj
            vecs /= np.linalg.norm(vecs, axis=1, keepdims=True) + 1e-9
        return vecs

    USING_REAL_MODEL = True

except ImportError:
    logger.warning(
        "sentence-transformers not installed — using hash-based stub embeddings. "
        "Install with: pip install sentence-transformers"
    )

    def _encode_text(texts: List[str], dim: int = 256) -> np.ndarray:  # type: ignore[misc]
        """Deterministic hash-based stub for testing without GPU/model."""
        vecs = []
        for text in texts:
            digest = hashlib.sha256(text.encode()).digest()
            # Repeat digest bytes to fill `dim` floats, then slice to exact size.
            raw = np.frombuffer((digest * ((dim * 4 // len(digest)) + 1))[: dim * 4], dtype=np.uint8)
            vec = raw[:dim].astype(np.float32) / 127.5 - 1.0
            # Normalise AFTER slicing so the resulting dim-vector is unit-length.
            vec /= np.linalg.norm(vec) + 1e-9
            vecs.append(vec)
        return np.array(vecs, dtype=np.float32)

    USING_REAL_MODEL = False


# ---------------------------------------------------------------------------
# Public helpers
# ---------------------------------------------------------------------------

def _candidate_text(c: Candidate) -> str:
    """Serialise a candidate's profile into a single text string for encoding."""
    parts = [
        c.full_name or "",
        c.current_title or "",
        c.education or "",
        " ".join(c.skills),
        f"experience {c.experience_years} years {c.experience_level.value}",
        " ".join(c.preferred_locations),
        " ".join(t.value for t in c.preferred_employment_types),
    ]
    return " | ".join(p for p in parts if p)


def _job_text(j: Job) -> str:
    """Serialise a job posting into a single text string for encoding."""
    parts = [
        j.title,
        j.description[:512],   # truncate long descriptions
        " ".join(j.required_skills),
        " ".join(j.preferred_skills),
        f"{j.experience_level.value} level",
        j.employment_type.value,
        j.location,
        "remote" if j.is_remote else "",
    ]
    return " | ".join(p for p in parts if p)


def embed_candidates(candidates: List[Candidate], dim: int = 256) -> np.ndarray:
    """
    Return a (N, dim) float32 array of L2-normalised embeddings,
    one row per candidate.
    """
    texts = [_candidate_text(c) for c in candidates]
    return _encode_text(texts, dim)


def embed_jobs(jobs: List[Job], dim: int = 256) -> np.ndarray:
    """
    Return a (N, dim) float32 array of L2-normalised embeddings,
    one row per job.
    """
    texts = [_job_text(j) for j in jobs]
    return _encode_text(texts, dim)


def embed_single_job(job: Job, dim: int = 256) -> np.ndarray:
    """Return a (dim,) float32 vector for a single job."""
    return embed_jobs([job], dim)[0]
