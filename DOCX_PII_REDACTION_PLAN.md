# DOCX PII Redaction — Build Brief (Approved Plan)

This document is the **full implementation brief**. A Cursor agent should implement the project from this file alone. Do not wait for further approval.

---

## Constraints (hard requirements)

| Constraint | Requirement |
|---|---|
| Language | **Python 3** |
| DOCX I/O | **`python-docx`** only for read/write of `.docx` |
| Detection | **Regex + local NER** (Microsoft **Presidio** preferred; spaCy acceptable as NER backend) **+ context rules** |
| LLM | **NO LLM API** for parsing, identifying, verifying, or generating replacements. No OpenAI/Anthropic/etc. calls. |
| Consistency | Same real PII → same fake across **all files in a batch** (e.g. `ramit hansda` / `Ramit Hansda` → same `John Doe`) |
| Mapping store | Shared **persistent JSON** mapping file (load → update → save) |
| Fake generation | **Faker**, seeded by `hash(pii_type + normalized_original)` for deterministic replacements |
| CLI | Accept **one or more** `.docx` paths |
| Ticket/order IDs | **Do NOT redact** ticket/order/reference IDs unless the span also matches a real PII pattern (email, SSN, CC, phone, etc.) |

Minimum PII types to detect and replace:

- Full names  
- Email addresses  
- Phone numbers  
- Company names  
- Physical / mailing addresses  
- Social Security Numbers (SSNs)  
- Credit card numbers  
- Dates of birth  
- IP addresses  

Assignment deliverables (implement toward these):

1. Redaction script (source)  
2. Redacted `.docx` output(s)  
3. README — approach + tradeoffs / false positives & negatives  
4. Evaluation report with precision / recall (and accuracy if useful)  

---

## 1. Goal / Non-goals

### Goal

Build a local, offline-capable CLI that:

1. Reads one or more Word (`.docx`) ticket-log / prospectus-style documents.  
2. Detects PII with **regex + Presidio/spaCy NER + context heuristics** (no cloud LLM).  
3. Replaces each distinct PII value with a **stable fake** of the same type.  
4. Writes redacted `.docx` files preserving structure as much as practical.  
5. Persists a shared mapping so multi-file / re-runs stay consistent.  
6. Ships evaluation helpers and a short README suitable for the assignment.

### Non-goals

- Calling any remote LLM or hosted PII API.  
- Redacting ticket numbers, order IDs, SKUs, or similar operational IDs **by default**.  
- Perfect human-level NER on every edge case (document limitations honestly).  
- GUI / web UI.  
- Editing images, charts, or embedded OLE objects inside DOCX.  
- Supporting `.doc` (legacy binary) or PDF in v1.

---

## 2. Stack table

| Layer | Choice | Notes |
|---|---|---|
| Runtime | Python 3.10+ | Pin in `requirements.txt` / `pyproject.toml` |
| DOCX | `python-docx` | Paragraphs, tables, headers/footers; run-aware replace |
| Orchestration / NER | `presidio-analyzer` + `presidio-anonymizer` (optional for replace logic) | Prefer Presidio; spaCy model underneath |
| spaCy model | `en_core_web_lg` (or `md` if lg is too heavy) | Local only; download in setup docs |
| Regex / validators | stdlib `re` + optional `phonenumbers`, Luhn for CC | Keep validators local |
| Fakes | `Faker` | Seeded per canonical key |
| Mapping | JSON file on disk | e.g. `mapping.json` / `--mapping PATH` |
| CLI | `argparse` or `typer` | Prefer stdlib `argparse` unless typer already present |
| Tests | `pytest` | Unit + golden / eval fixtures |
| Eval | Custom labeled spans JSON + precision/recall script | No LLM judge |

---

## 3. Architecture

Pipeline (strict order):

```
extract → detect → merge → map → replace → emit
```

### 3.1 Extract

- Open each `.docx` with `python-docx`.  
- Walk **all text containers**: body paragraphs, tables (cells), headers, footers.  
- Build a list of **text units** with stable location keys, e.g.  
  `("body", para_index)`, `("table", t, r, c)`, `("header", section, para)`, etc.  
- For each unit, also retain the **run list** (python-docx `Run` objects) because replacements must handle PII split across runs.

### 3.2 Detect

For each text unit’s full concatenated text string, run **in parallel conceptually**:

1. **Regex detectors** (high precision patterns: email, SSN, CC, IP, phone, DOB formats).  
2. **Presidio Analyzer** (or spaCy NER) for PERSON, ORG, LOCATION / ADDRESS-like entities.  
3. **Context rules** (label/keyword windows: `DOB:`, `Date of Birth`, `SSN`, `Email`, `Phone`, `Address`, `Company`, `Customer Name`, etc.).

Each detector emits spans: `{start, end, type, text, source, confidence}`.

### 3.3 Merge

- Normalize types to a canonical enum (see §4).  
- Resolve overlapping spans (see §4.5).  
- Drop spans that look like **ticket/order IDs** unless they also match a real PII regex (see §4.4).  
- Output a non-overlapping, ordered list of spans per text unit.

### 3.4 Map

- Canonicalize each span’s text for lookup (see §5).  
- Key = `(pii_type, canonical_text)`.  
- If key exists in shared mapping → reuse `fake_value`.  
- Else generate via Faker with seed derived from `hash(type + normalized_original)`, store, persist JSON.

### 3.5 Replace

- Apply replacements **right-to-left** within each text unit so offsets stay valid.  
- Prefer a **run-aware** rewriter: replace characters in the concatenated text, then write back into runs without destroying unrelated formatting when possible.  
- If a span crosses run boundaries, coalesce into the first run (or redistribute) and clear consumed chars from subsequent runs.

### 3.6 Emit

- Save redacted docx to output path(s): default `output/<stem>.redacted.docx` or `--out-dir`.  
- Write/update `mapping.json`.  
- Optionally write a sidecar audit log: original → fake, type, file, confidence (useful for eval; keep out of the redacted docx).

---

## 4. Detection approach (detail)

### 4.1 Canonical PII types

Use these string ids everywhere (mapping, detectors, eval):

| Type id | Examples |
|---|---|
| `PERSON` | Full names |
| `EMAIL` | `user@domain.com` |
| `PHONE` | `+91 9876543210`, `(555) 123-4567` |
| `ORG` | Company / employer names |
| `ADDRESS` | Street + city/state/zip style |
| `SSN` | `123-45-6789` |
| `CREDIT_CARD` | 13–19 digit PAN (Luhn-checked) |
| `DOB` | Dates of birth (not arbitrary dates unless context says DOB) |
| `IP_ADDRESS` | IPv4 / IPv6 |

### 4.2 Regex detectors (implement first; high precision)

| Type | Guidance |
|---|---|
| `EMAIL` | Standard email regex; lower-confidence if TLD weird — still redact if well-formed |
| `PHONE` | International + US/IN patterns; prefer `phonenumbers` parse/validate when installed |
| `SSN` | `\b\d{3}-\d{2}-\d{4}\b` and unspaced 9-digit with weak prior; reject obvious non-SSN contexts if labeled as ticket |
| `CREDIT_CARD` | Digit groups / continuous 13–19 digits; **require Luhn pass**; reject if adjacent to `order`/`ticket`/`ref` labels and Luhn fails |
| `IP_ADDRESS` | IPv4 + simple IPv6; exclude `0.0.0.0` / version-like noise only if clearly not an IP context |
| `DOB` | Date patterns **only when** nearby context keywords (`DOB`, `Date of Birth`, `born`, `birthday`) **or** Presidio `DATE_TIME` with DOB context — do **not** blanket-redact every date (ticket dates, meeting dates) |

### 4.3 NER / Presidio entities

Configure Presidio Analyzer with spaCy `en_core_web_*`:

| Presidio / spaCy entity | Map to |
|---|---|
| `PERSON` | `PERSON` |
| `ORG` | `ORG` |
| `LOCATION` / `GPE` / address recognizers | `ADDRESS` when multi-token / street-like; else LOCATION-only weak → require address cues |
| Built-in: email, phone, IP, crypto, etc. | Prefer Presidio built-ins **in addition to** own regex; de-dupe in merge |

Add custom Presidio recognizers if needed for SSN / CC / DOB with context.

**No remote recognizers. No LLM recognizer.**

### 4.4 Context rules

- Positive windows (±N chars or same line): labels listed in §3.2 boost confidence / allow weaker patterns.  
- Negative windows: `ticket`, `order`, `ord#`, `ref`, `reference`, `case id`, `request id`, `sku`, `invoice #` → **suppress** digit-only or alphanumeric ID-like spans that are **not** email/SSN/CC/phone/IP.  
- Explicit product choice for the assignment: **ticket/order IDs are not PII** unless they match a real PII pattern. Document this in README.

### 4.5 Confidence & overlap resolution

Assign rough confidence bands:

- Regex + validator (Luhn / phonenumbers / email shape): **0.90–0.99**  
- Presidio/spaCy NER alone: **0.60–0.85** (model score if available)  
- Context-boosted NER: raise toward **0.85+**  
- Context-only weak guess: **do not emit** unless necessary for labeled eval fixtures  

Overlap policy (apply in order):

1. Prefer higher confidence.  
2. Prefer longer span (full name over first name if nested).  
3. Prefer more specific type (`EMAIL` over `PERSON` if email-shaped).  
4. Prefer regex-validated financial/ID types over NER org/person when conflicting on same digits.  
5. Never leave two overlapping kept spans.

### 4.6 DOCX run handling

Critical DOCX issue: formatting splits one logical string across runs (`Ra` + `mit Han` + `sda`).

Implementation requirements:

1. Detect on **concatenated paragraph/cell text**.  
2. Map character offsets back to `(run_index, offset_in_run)`.  
3. Replacement strategies (pick one and stick to it; document in README):  
   - **Recommended:** mutate run texts in place — put full replacement in the first run that contains span start; blank out remaining covered characters in later runs.  
   - Avoid rewriting the whole paragraph as a single run unless formatting preservation is abandoned (acceptable fallback with a warning flag).  
4. Process tables cell-by-cell the same way.  
5. Process headers/footers per section.

---

## 5. Consistency model

### 5.1 Canonicalization

Before map lookup / seed:

| Type | Normalize |
|---|---|
| `PERSON` / `ORG` | Unicode NFKC → strip → collapse whitespace → **casefold** |
| `EMAIL` | strip → lower |
| `PHONE` | keep digits and leading `+` only (or E.164 if `phonenumbers` available) |
| `ADDRESS` | NFKC → collapse whitespace → casefold |
| `SSN` / `CREDIT_CARD` | digits only |
| `DOB` | parse to ISO `YYYY-MM-DD` when possible; else casefold raw |
| `IP_ADDRESS` | strip; lowercase IPv6 |

Example: `ramit hansda`, `Ramit Hansda`, `RAMIT  HANSDA` → canonical `ramit hansda` → **one** mapping entry → one fake (e.g. `John Doe`) everywhere, including across multiple input files in the same CLI run and future runs that load the same JSON.

### 5.2 Mapping schema (JSON)

Path default: `./mapping.json` (override with `--mapping`).

```json
{
  "version": 1,
  "entries": {
    "PERSON|ramit hansda": {
      "pii_type": "PERSON",
      "canonical": "ramit hansda",
      "examples": ["Ramit Hansda", "ramit hansda"],
      "fake": "John Doe",
      "seed": "a1b2c3d4e5f6..."
    },
    "EMAIL|ramit.hansda@example.com": {
      "pii_type": "EMAIL",
      "canonical": "ramit.hansda@example.com",
      "examples": ["Ramit.Hansda@example.com"],
      "fake": "john.doe@example.com",
      "seed": "..."
    }
  }
}
```

Rules:

- Key format: `{TYPE}|{canonical}`.  
- On hit: reuse `fake`; optionally append new surface forms to `examples`.  
- On miss: generate fake, append entry, atomic write (write temp → rename).  
- Multi-file batch: **one shared in-memory map** loaded once at start, saved once at end (or after each file if safer).

### 5.3 Deterministic Faker seeding

```text
seed_material = f"{pii_type}|{canonical}"
seed_int = stable_hash_to_int32_or_int64(seed_material)  # e.g. sha256 → int
fake = Faker("en_US")
fake.seed_instance(seed_int)
```

Generators by type:

| Type | Faker provider idea |
|---|---|
| `PERSON` | `name()` |
| `EMAIL` | `email()` / `safe_email()` |
| `PHONE` | `phone_number()` (optionally preserve country hint from original) |
| `ORG` | `company()` |
| `ADDRESS` | `address()` (single-line-ify newlines to `, ` for DOCX) |
| `SSN` | `ssn()` |
| `CREDIT_CARD` | `credit_card_number()` |
| `DOB` | `date_of_birth()` → format similarly to original when possible |
| `IP_ADDRESS` | `ipv4()` / `ipv6()` matching original family |

Store the seed (hex) in mapping for audit/debug.

### 5.4 Multi-file example

CLI:

```bash
python -m pii_redact input/a.docx input/b.docx --mapping mapping.json --out-dir output/
```

If both files contain `Ramit Hansda` / `ramit hansda`, both become the **same** fake from `mapping.json`.

---

## 6. CLI interface sketch

```bash
python -m pii_redact PATH [PATH ...] \
  [--mapping PATH] \
  [--out-dir DIR] \
  [--audit PATH] \
  [--spacy-model NAME] \
  [--no-ner] \
  [--dry-run] \
  [-v]
```

| Flag | Behavior |
|---|---|
| `PATH...` | One or more `.docx` files (required) |
| `--mapping` | Shared JSON mapping (default `mapping.json`) |
| `--out-dir` | Directory for `*.redacted.docx` |
| `--audit` | Optional JSONL/JSON of replacements applied |
| `--spacy-model` | Default `en_core_web_lg` |
| `--no-ner` | Regex + rules only (debug / ablation) |
| `--dry-run` | Detect + print planned replacements; no write |
| `-v` | Verbose logging |

Exit codes: `0` success; `2` bad args; `1` processing error on any file (still process others if reasonable; document choice).

---

## 7. Project layout suggestion

Prefer implementing under `pii-redaction/` (folder already present) **or** repo root package. Suggested tree:

```text
pii-redaction/
  README.md
  requirements.txt          # or pyproject.toml
  mapping.json              # gitignore real runs; ship empty or example
  .gitignore
  pii_redact/
    __init__.py
    __main__.py             # python -m pii_redact
    cli.py
    extract.py              # DOCX walk → text units + runs
    detect/
      __init__.py
      regex_detectors.py
      ner_presidio.py
      context_rules.py
      merge.py
    mapping_store.py        # load/save JSON + canonicalize
    faker_factory.py        # seeded fake per type
    replace.py              # run-aware replace
    emit.py                 # save docx
    types.py                # Span, PiiType, TextUnit dataclasses
  tests/
    test_regex.py
    test_merge.py
    test_mapping_consistency.py
    test_replace_runs.py
    fixtures/
  evaluation/
    labels.example.json     # gold spans
    evaluate.py             # precision / recall
    report.md               # generated or hand-finished for handoff
  samples/                  # optional sample docx (synthetic)
  output/                   # gitignore redacted outputs
```

Top-level handoff for agents: this file at `/workspace/DOCX_PII_REDACTION_PLAN.md`.

---

## 8. Implementation steps (ordered checklist)

Implement in this order; commit logically as you go.

1. **Scaffold** package, `requirements.txt`, `.gitignore` (`output/`, `__pycache__/`, `.venv/`, local `mapping.json` if sensitive).  
2. **Types** — `PiiType`, `Span`, `TextUnit`, mapping entry dataclasses.  
3. **Extract** — DOCX walker for paragraphs, tables, headers/footers + run lists.  
4. **Regex detectors** — EMAIL, PHONE, SSN, CREDIT_CARD (+ Luhn), IP; DOB only with context helper.  
5. **Context rules** — positive boosters; **negative ticket/order ID suppression**.  
6. **Merge** — overlap resolution + type priority.  
7. **Mapping store** — canonicalize, JSON load/save, atomic write.  
8. **Faker factory** — seeded generators per type; unit test same input → same fake.  
9. **Replace** — run-aware replacement; unit test split-run name.  
10. **Emit + CLI** — multi-file paths, `--mapping`, `--out-dir`.  
11. **Presidio/spaCy NER** — wire analyzer; merge with regex; allow `--no-ner`.  
12. **ORG / PERSON / ADDRESS** tuning — context + NER; avoid over-redacting common nouns.  
13. **End-to-end** on sample/assignment docx → write `output/*.redacted.docx`.  
14. **Evaluation** — gold labels file + `evaluate.py` (precision/recall per type + micro/macro).  
15. **README** — approach, tradeoffs, explicit “ticket/order IDs not redacted”, how to run, model download.  
16. **Polish** — logging, dry-run, edge cases (empty paras, nested tables if any).

---

## 9. Testing / evaluation criteria

### 9.1 Automated tests

- Regex true positives / true negatives for each pattern family.  
- Luhn reject for non-CC digit strings.  
- Ticket-like IDs **not** redacted: e.g. `Ticket #A-10293`, `Order 998877`.  
- Mapping consistency: `Ramit Hansda` and `ramit hansda` → identical fake; second file reuses mapping.  
- Run-split replacement preserves surrounding text.  
- Determinism: fixed mapping seed path → identical fake across process restarts.

### 9.2 Assignment evaluation report

Prepare gold annotations (JSON) for at least one document (or a synthetic fixture mirroring the prospectus/ticket log):

```json
{
  "file": "samples/ticket_log.docx",
  "entities": [
    {"start": 10, "end": 22, "type": "PERSON", "text": "Rashi Patil"},
    {"start": 40, "end": 62, "type": "EMAIL", "text": "rashhi.patil@gmail.com"}
  ]
}
```

Metrics (document formulas in `evaluation/report.md`):

- **Precision** = TP / (TP + FP)  
- **Recall** = TP / (TP + FN)  
- Per-type and overall (micro-average).  
- Match policy: prefer **span overlap IoU ≥ 0.5** + same type, or exact boundary match; state which.  
- Call out false positives (over-redaction) and false negatives (missed names/addresses) in README/report.

### 9.3 Definition of done

- CLI redacts multiple docx with shared mapping.  
- No LLM API usage anywhere in code paths.  
- Ticket/order IDs left intact unless PII-pattern match.  
- README + evaluation numbers present.  
- `pytest` passes on unit tests.

---

## 10. Risks & limitations

| Risk | Mitigation |
|---|---|
| Names split across DOCX runs | Concatenate + run-aware rewrite; test fixtures |
| spaCy misses uncommon / Indic names | Regex/context labels; document FN risk; optional custom name lists (local only) |
| Company vs product noun ambiguity | Require ORG NER + optional context; accept some FP/FN |
| Addresses without clear structure | NER LOCATION + address keywords; may miss partial addresses |
| Over-redacting dates | DOB only with context; don’t treat all DATE as DOB |
| CC false positives on long IDs | Luhn + ticket/order negative rules |
| Presidio + large spaCy model heavy | Document download; allow `md` model; `--no-ner` ablation |
| Formatting loss on aggressive replace | Prefer in-run edits; note limitation for complex runs |
| Mapping file growth / PII at rest | `.gitignore` mapping; warn that mapping contains originals (sensitive) |
| No LLM verification | Evaluation is human/gold-label based; do not add LLM judges |

---

## Quick start (for the implementing agent)

```bash
cd pii-redaction
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python -m spacy download en_core_web_lg
python -m pii_redact path/to/file.docx --out-dir output/ --mapping mapping.json
pytest
python evaluation/evaluate.py --pred audit.json --gold evaluation/labels.json
```

**Remember:** offline detection only — regex + Presidio/spaCy + rules; Faker for replacements; shared JSON mapping for cross-file consistency; never redact ticket/order IDs unless they match real PII patterns.
```
