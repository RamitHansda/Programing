-- Database schema for the job-candidate matching platform.
-- Candidate embeddings are NOT stored here; they live in FAISS shards.
-- This schema holds metadata and lookup tables.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ---------------------------------------------------------------------------
-- Candidates
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS candidates (
    candidate_id        UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    email               TEXT        NOT NULL UNIQUE,
    full_name           TEXT        NOT NULL,
    skills              TEXT[]      NOT NULL DEFAULT '{}',
    experience_years    FLOAT       NOT NULL DEFAULT 0,
    experience_level    TEXT        NOT NULL,
    preferred_locations TEXT[]      NOT NULL DEFAULT '{}',
    preferred_emp_types TEXT[]      NOT NULL DEFAULT '{}',
    current_title       TEXT,
    education           TEXT,
    preferred_salary_min INT,
    preferred_salary_max INT,
    -- Shard mapping: which FAISS shard + local row ID holds this candidate's vector.
    shard_id            INT         NOT NULL DEFAULT 0,
    local_vector_id     BIGINT,     -- row offset inside the shard
    opted_out           BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_candidates_shard ON candidates(shard_id, local_vector_id);
CREATE INDEX IF NOT EXISTS idx_candidates_opted_out ON candidates(opted_out) WHERE opted_out = FALSE;

-- ---------------------------------------------------------------------------
-- Jobs
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS jobs (
    job_id              UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    employer_id         UUID        NOT NULL,
    title               TEXT        NOT NULL,
    description         TEXT        NOT NULL,
    required_skills     TEXT[]      NOT NULL DEFAULT '{}',
    preferred_skills    TEXT[]      NOT NULL DEFAULT '{}',
    experience_level    TEXT        NOT NULL,
    employment_type     TEXT        NOT NULL,
    location            TEXT        NOT NULL,
    is_remote           BOOLEAN     NOT NULL DEFAULT FALSE,
    salary_min          INT,
    salary_max          INT,
    is_active           BOOLEAN     NOT NULL DEFAULT TRUE,
    posted_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_jobs_active ON jobs(is_active, posted_at DESC) WHERE is_active = TRUE;

-- ---------------------------------------------------------------------------
-- Match results (audit log)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS match_results (
    match_id            UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    job_id              UUID        NOT NULL REFERENCES jobs(job_id),
    candidate_id        UUID        NOT NULL REFERENCES candidates(candidate_id),
    similarity_score    FLOAT       NOT NULL,
    match_reasons       TEXT[]      NOT NULL DEFAULT '{}',
    status              TEXT        NOT NULL DEFAULT 'pending',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (job_id, candidate_id)   -- prevent duplicate matches per sweep
);

CREATE INDEX IF NOT EXISTS idx_matches_candidate ON match_results(candidate_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_matches_job       ON match_results(job_id, similarity_score DESC);
CREATE INDEX IF NOT EXISTS idx_matches_status    ON match_results(status) WHERE status = 'pending';

-- ---------------------------------------------------------------------------
-- Email audit log
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS email_records (
    record_id           UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    match_id            UUID        NOT NULL REFERENCES match_results(match_id),
    candidate_email     TEXT        NOT NULL,
    job_id              UUID        NOT NULL,
    sent_at             TIMESTAMPTZ,
    provider_message_id TEXT,
    attempt_count       INT         NOT NULL DEFAULT 0,
    last_error          TEXT,
    status              TEXT        NOT NULL DEFAULT 'pending',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_email_records_match ON email_records(match_id);
CREATE INDEX IF NOT EXISTS idx_email_records_email ON email_records(candidate_email, sent_at DESC);
