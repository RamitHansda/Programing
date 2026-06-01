"""
Offline index builder — run nightly to rebuild FAISS shards from scratch.

This script is designed to be run as a Kubernetes Job or a Spark/Flink batch
task.  It reads candidates from PostgreSQL in streaming batches, encodes them,
and writes the resulting FAISS shards to object storage.

Usage
-----
  python -m scripts.index_builder \
      --shard-start 0 --shard-end 999 \
      --batch-size 10000 \
      --workers 16

With 100 B candidates / 1 000 shards = 100 M candidates/shard.
At 10 000 candidates/batch → 10 000 batches/shard.
Embedding 10 000 candidates on a GPU ≈ 5 s → ~14 h per shard single-threaded.
Running 200 shards in parallel across 5 machines finishes in ~70 h.
In practice you would use a distributed Spark job or Flink for this step.
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

import numpy as np

# Make the project root importable when run as a script.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from config.settings import settings
from matching.embedder import embed_candidates
from matching.faiss_index import FaissShardIndex

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-8s %(name)s — %(message)s",
)
logger = logging.getLogger("index_builder")


# ---------------------------------------------------------------------------
# Stub data source (replace with your actual DB reader in production)
# ---------------------------------------------------------------------------

def _iter_candidate_batches_stub(shard_id: int, batch_size: int):
    """
    Yields (candidates, local_ids) tuples for the given shard.
    Replace this with a real DB / data-lake reader.
    """
    from models.schemas import Candidate, ExperienceLevel, EmploymentType

    logger.warning("Using STUB candidate source — replace with real DB reader.")
    # Yield one small batch so the script can be tested end-to-end.
    from datetime import datetime
    candidates = [
        Candidate(
            candidate_id=f"stub-{shard_id}-{i}",
            email=f"candidate_{shard_id}_{i}@example.com",
            full_name=f"Candidate {shard_id}-{i}",
            skills=["python", "sql", "machine learning"],
            experience_years=3.0 + i,
            experience_level=ExperienceLevel.MID,
            preferred_locations=["remote", "New York"],
            preferred_employment_types=[EmploymentType.FULL_TIME],
        )
        for i in range(min(batch_size, 100))
    ]
    local_ids = np.arange(len(candidates), dtype=np.int64)
    yield candidates, local_ids


def build_shard(
    shard_id: int,
    batch_size: int = 10_000,
    index_dir: str = "/data/faiss",
    dim: int = 256,
    nlist: int = 4096,
    m_pq: int = 32,
    nprobe: int = 64,
    dry_run: bool = False,
) -> None:
    index_path = Path(index_dir) / f"shard_{shard_id:04d}.faiss"
    shard = FaissShardIndex(
        shard_id=shard_id,
        dim=dim,
        nlist=nlist,
        m_pq=m_pq,
        nprobe=nprobe,
        index_path=None if dry_run else str(index_path),
    )

    logger.info("Building shard %d → %s", shard_id, index_path)

    # Two-pass: first pass gathers training vectors, second pass adds all.
    # For simplicity we train on the first batch (min 4 096 × 4 = 16 384 vectors).
    trained = False
    total_added = 0

    for candidates, local_ids in _iter_candidate_batches_stub(shard_id, batch_size):
        vecs = embed_candidates(candidates, dim=dim)

        if not trained:
            # FAISS IVF requires at least nlist vectors for training.
            if len(vecs) >= nlist:
                shard.train(vecs)
                trained = True
            else:
                logger.warning(
                    "Shard %d: not enough vectors to train IVF (%d < %d). "
                    "Will use brute-force index.",
                    shard_id, len(vecs), nlist,
                )
                trained = True  # proceed without IVF training on stub

        shard.add(vecs, local_ids + total_added)
        total_added += len(vecs)

    if not dry_run:
        shard.save()
        logger.info("Shard %d: saved %d vectors to %s.", shard_id, total_added, index_path)
    else:
        logger.info("Shard %d: dry run complete, %d vectors processed.", shard_id, total_added)


def main() -> None:
    parser = argparse.ArgumentParser(description="Build FAISS shards for candidate index.")
    parser.add_argument("--shard-start", type=int, default=0)
    parser.add_argument("--shard-end",   type=int, default=0)
    parser.add_argument("--batch-size",  type=int, default=10_000)
    parser.add_argument("--index-dir",   default=settings.vector.index_dir)
    parser.add_argument("--dim",         type=int, default=settings.vector.embedding_dim)
    parser.add_argument("--nprobe",      type=int, default=settings.vector.nprobe)
    parser.add_argument("--dry-run",     action="store_true")
    args = parser.parse_args()

    for shard_id in range(args.shard_start, args.shard_end + 1):
        build_shard(
            shard_id=shard_id,
            batch_size=args.batch_size,
            index_dir=args.index_dir,
            dim=args.dim,
            nprobe=args.nprobe,
            dry_run=args.dry_run,
        )

    logger.info("Index build complete for shards %d–%d.", args.shard_start, args.shard_end)


if __name__ == "__main__":
    main()
