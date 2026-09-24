# DOCX PII Redaction — Build Brief (Approved Plan)

This document is the **full implementation brief**. A Cursor agent should implement the project from this file alone. Do not wait for further approval.

**India PII included:** **PAN** (`ABCDE1234F`) and **Aadhaar** — see [India identifiers: PAN and Aadhaar](#india-identifiers-pan-and-aadhaar).

---

## Constraints (hard requirements)

| Constraint | Requirement |
|---|---|
| Language | **Python 3** |
| DOCX I/O | **`python-docx`** only for read/write of `.docx` |
| Detection | **Regex + local NER** (Microsoft **Presidio** preferred; spaCy acceptable as NER backend) **+ context rules** |
| LLM | **NO LLM API** for parsing, identifying, verifying, or generating replacements. No OpenAI/Anthropic/etc. calls. |
| Consistency | Same real PII → same fake across **all files in a batch** (e.g. `ramit hansda` / `Ramit Hansda` → same `John Doe`) |
| Mapping store | Shared **persistent JSON** mapping file (load → update → save) for assignment v1; see §11 for production DB |
| Fake generation | **Faker**, seeded by `hash(pii_type + normalized_original)` for deterministic replacements |
| CLI | Accept **one or more** `.docx` paths |
| Ticket/order IDs | **Do NOT redact** ticket/order/reference IDs unless the span also matches a real PII pattern (email, SSN, CC, phone, **Aadhaar**, **PAN**, etc.) |

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
- **PAN** (India Permanent Account Number)  
- **Aadhaar** (India UID)  

### India identifiers: PAN and Aadhaar

**Ctrl+F targets:** `PAN` · `Aadhaar` · `AADHAAR` · `Permanent Account` · `ABCDE1234F`

India **PAN** and **Aadhaar** are **first-class PII types** (same priority as email/SSN/CC). They must appear in detectors, mapping, fakes, eval gold, and the Definition of Done.

| Identifier | Type id | Example format | Regex / validation |
|---|---|---|---|
| **PAN** (Permanent Account Number) | `PAN` | `ABCDE1234F` | `\b[A-Z]{5}[0-9]{4}[A-Z]\b` (case-insensitive detect → uppercase canonical) |
| **Aadhaar** (UID) | `AADHAAR` | `1234 5678 9012` | 12 digits (optional spaces/dashes) + **Verhoeff** and/or context (`Aadhaar` / `Aadhar` / `UID` / `आधार`) |

Quick rules:

- **PAN** ≠ credit-card “PAN”; ≠ 10-char ticket/order codes unless the strict PAN regex matches (prefer `PAN` / `Permanent Account Number` context).  
- **Aadhaar** ≠ arbitrary 12-digit order/invoice IDs without Verhoeff and/or Aadhaar context.  
- Details: §4.1–§4.4 (types + regex), §5 (canonical keys), §6 (fake generators), §9 (eval + checklist).

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
2. Detects PII with **regex + Presidio/spaCy NER + context heuristics** (no cloud LLM) — including India **PAN** (`ABCDE1234F`) and **Aadhaar**.  
3. Replaces each distinct PII value with a **stable fake** of the same type (custom generators for **PAN** / **AADHAAR**).  
4. Writes redacted `.docx` files preserving structure as much as practical.  
5. Persists a shared mapping so multi-file / re-runs stay consistent (same **PAN** / **Aadhaar** → same fake).  
6. Ships evaluation helpers and a short README suitable for the assignment (gold labels include **PAN** and **AADHAAR**).

### Non-goals

- Calling any remote LLM or hosted PII API.  
- Redacting ticket numbers, order IDs, SKUs, or similar operational IDs **by default**.  
  - Do **not** treat alphanumeric order/ticket codes as India **PAN** just because they are 10 chars.  
  - Do **not** treat arbitrary 12-digit order/refund/invoice numbers as **Aadhaar** without Verhoeff and/or Aadhaar context cues.  
- Perfect human-level NER on every edge case (document limitations honestly).  
- GUI / web UI (assignment v1).  
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
| Regex / validators | stdlib `re` + optional `phonenumbers`, Luhn for CC, **Verhoeff for Aadhaar** | Keep validators local; implement Verhoeff in-repo if no dep |
| Fakes | `Faker` + custom generators for `PAN` / `AADHAAR` | Seeded per canonical key; format-preserving |
| Mapping | JSON file on disk (v1) | e.g. `mapping.json` / `--mapping PATH`; Postgres/Redis in production (§11) |
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

1. **Regex detectors** (high precision patterns: email, SSN, CC, IP, phone, DOB formats, **PAN**, **Aadhaar** with validators).  
2. **Presidio Analyzer** (or spaCy NER) for PERSON, ORG, LOCATION / ADDRESS-like entities.  
3. **Context rules** (label/keyword windows: `DOB:`, `Date of Birth`, `SSN`, `Email`, `Phone`, `Address`, `Company`, `Customer Name`, `Aadhaar` / `Aadhar` / `UID` / `आधार`, `PAN`, `Permanent Account Number`, etc.).

Each detector emits spans: `{start, end, type, text, source, confidence}`.

### 3.3 Merge

- Normalize types to a canonical enum (see §4).  
- Resolve overlapping spans (see §4.5).  
- Drop spans that look like **ticket/order IDs** unless they also match a real PII regex (see §4.4) — including verified `PAN` / `AADHAAR`.  
- Output a non-overlapping, ordered list of spans per text unit.

### 3.4 Map

- Canonicalize each span’s text for lookup (see §5).  
- Key = `(pii_type, canonical_text)`.  
- If key exists in shared mapping → reuse `fake_value`.  
- Else generate via Faker (or custom PAN/Aadhaar generator) with seed derived from `hash(type + normalized_original)`, store, persist JSON.

### 3.5 Replace

- Apply replacements **right-to-left** within each text unit so offsets stay valid.  
- Prefer a **run-aware** rewriter: replace characters in the concatenated text, then write back into runs without destroying unrelated formatting when possible.  
- If a span crosses run boundaries, coalesce into the first run (or redistribute) and clear consumed chars from subsequent runs.

### 3.6 Emit

- Save redacted docx to output path(s): default `output/<stem>.redacted.docx` or `--out-dir`.  
- Write/update `mapping.json`.  
- Optionally write a sidecar audit log: original → fake, type, file, confidence (useful for eval; keep out of the redacted docx).  
  - **Production:** never log full Aadhaar or PAN in audit — store token/hash or last-4 only (see §11).

---

## 4. Detection approach (detail)

### 4.1 Canonical PII types

Use these string ids everywhere (mapping, detectors, eval). **Must include `PAN` and `AADHAAR`** (see [India identifiers: PAN and Aadhaar](#india-identifiers-pan-and-aadhaar)):

| Type id | Examples |
|---|---|
| `PERSON` | Full names |
| `EMAIL` | `user@domain.com` |
| `PHONE` | `+91 9876543210`, `(555) 123-4567` |
| `ORG` | Company / employer names |
| `ADDRESS` | Street + city/state/zip style |
| `SSN` | `123-45-6789` |
| `CREDIT_CARD` | 13–19 digit card numbers (Luhn-checked). **Not** India **PAN**. |
| `DOB` | Dates of birth (not arbitrary dates unless context says DOB) |
| `IP_ADDRESS` | IPv4 / IPv6 |
| `PAN` | India **PAN** / Permanent Account Number — format `ABCDE1234F` |
| `AADHAAR` | India **Aadhaar** UID — format `1234 5678 9012` (12 digits) |

### 4.2 Regex detectors (implement first; high precision)

| Type | Guidance |
|---|---|
| `EMAIL` | Standard email regex; lower-confidence if TLD weird — still redact if well-formed |
| `PHONE` | International + US/IN patterns; prefer `phonenumbers` parse/validate when installed |
| `SSN` | `\b\d{3}-\d{2}-\d{4}\b` and unspaced 9-digit with weak prior; reject obvious non-SSN contexts if labeled as ticket |
| `CREDIT_CARD` | Digit groups / continuous 13–19 digits; **require Luhn pass**; reject if adjacent to `order`/`ticket`/`ref` labels and Luhn fails |
| `IP_ADDRESS` | IPv4 + simple IPv6; exclude `0.0.0.0` / version-like noise only if clearly not an IP context |
| `DOB` | Date patterns **only when** nearby context keywords (`DOB`, `Date of Birth`, `born`, `birthday`) **or** Presidio `DATE_TIME` with DOB context — do **not** blanket-redact every date (ticket dates, meeting dates) |
| `PAN` | Regex: `\b[A-Z]{5}[0-9]{4}[A-Z]\b` — **case-insensitive detect**; normalize match to **uppercase** for the canonical key. Example: `ABCDE1234F`. Optional soft signal: 4th character entity type (`P`=individual, `C`=company, `H`=HUF, `F`=firm, `A`=AOP, `T`=trust, `B`=BOI, `L`=local authority, `J`=artificial juridical, `G`=govt) — boost confidence if known; **do not hard-reject** unknown 4th letters. Suppress if negative ticket/order window and no `PAN` / `Permanent Account` context. |
| `AADHAAR` | Match 12-digit groups with optional spaces/dashes (e.g. `XXXX XXXX XXXX`, `XXXX-XXXX-XXXX`, or continuous). **Do not** treat arbitrary 12-digit numbers as Aadhaar without **(a)** Verhoeff checksum validation when available **and/or** **(b)** context cues (`Aadhaar`, `Aadhar`, `UID`, `आधार`). Prefer Verhoeff + context together for high confidence; Verhoeff alone or strong context + well-formed digits for medium. Reject when labeled as order/ticket/invoice without Aadhaar cues. |

Implement Verhoeff checksum locally (stdlib-only is fine). Add a custom Presidio recognizer for `PAN` / `AADHAAR` if using Presidio’s pipeline.

### 4.3 NER / Presidio entities

Configure Presidio Analyzer with spaCy `en_core_web_*`:

| Presidio / spaCy entity | Map to |
|---|---|
| `PERSON` | `PERSON` |
| `ORG` | `ORG` |
| `LOCATION` / `GPE` / address recognizers | `ADDRESS` when multi-token / street-like; else LOCATION-only weak → require address cues |
| Built-in: email, phone, IP, crypto, etc. | Prefer Presidio built-ins **in addition to** own regex; de-dupe in merge |
| Custom: `PAN`, `AADHAAR` | Wire as custom PatternRecognizer / Recognizer; not covered by English NER |

Add custom Presidio recognizers if needed for SSN / CC / DOB / PAN / Aadhaar with context.

**No remote recognizers. No LLM recognizer.**

### 4.4 Context rules

- Positive windows (±N chars or same line): labels listed in §3.2 boost confidence / allow weaker patterns.  
  - Aadhaar cues: `Aadhaar`, `Aadhar`, `UID`, `UIDAI`, `आधार`.  
  - PAN cues: `PAN`, `Permanent Account Number`, `PAN Card`, `Income Tax PAN`.  
- Negative windows: `ticket`, `order`, `ord#`, `ref`, `reference`, `case id`, `request id`, `sku`, `invoice #` → **suppress** digit-only or alphanumeric ID-like spans that are **not** email/SSN/CC/phone/IP/**validated PAN**/**validated Aadhaar**.  
- Explicit product choice for the assignment: **ticket/order IDs are not PII** unless they match a real PII pattern. Document this in README.  
- **Disambiguation:** a 10-char alphanumeric order code is not PAN unless it matches the PAN regex (and preferably has PAN context). A 12-digit shipment ID is not Aadhaar without Verhoeff and/or Aadhaar context.

### 4.5 Confidence & overlap resolution

Assign rough confidence bands:

- Regex + validator (Luhn / phonenumbers / email shape / **Verhoeff for Aadhaar** / **PAN format + optional 4th-char soft signal**): **0.90–0.99**  
- Aadhaar: Verhoeff **and** context → **0.95+**; Verhoeff only or strong context + digit shape → **0.75–0.90**; digit shape alone → **do not emit**  
- PAN: format match → **0.90+**; format + known 4th-char entity type → slight boost; format + `PAN` context → **0.95+**  
- Presidio/spaCy NER alone: **0.60–0.85** (model score if available)  
- Context-boosted NER: raise toward **0.85+**  
- Context-only weak guess: **do not emit** unless necessary for labeled eval fixtures  

Overlap policy (apply in order):

1. Prefer higher confidence.  
2. Prefer longer span (full name over first name if nested).  
3. Prefer more specific type (`EMAIL` over `PERSON` if email-shaped; `PAN` / `AADHAAR` / `CREDIT_CARD` over generic digit NER).  
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
| `PAN` | strip → **uppercase**; reject/normalize only if it still matches `[A-Z]{5}[0-9]{4}[A-Z]` |
| `AADHAAR` | **digits only** (strip spaces/dashes) so `1234 5678 9012` and `123456789012` share one mapping key |

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
    },
    "PAN|ABCDE1234F": {
      "pii_type": "PAN",
      "canonical": "ABCDE1234F",
      "examples": ["abcde1234f", "ABCDE1234F"],
      "fake": "XXXXX0000X",
      "seed": "..."
    },
    "AADHAAR|123456789012": {
      "pii_type": "AADHAAR",
      "canonical": "123456789012",
      "examples": ["1234 5678 9012", "123456789012"],
      "fake": "999988887777",
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
- **Production:** never write full Aadhaar/PAN into unstructured logs; mapping store is access-controlled (see §11).

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
| `PAN` | Custom/seeded generator: 5 letters + 4 digits + 1 letter; optionally preserve 4th-char entity type from original; keep consistent via mapping |
| `AADHAAR` | Custom/seeded **format-preserving** 12-digit fake; prefer **Verhoeff-valid** checksum; optionally re-space like original (`XXXX XXXX XXXX`) when writing back |

Store the seed (hex) in mapping for audit/debug.

### 5.4 Multi-file example

CLI:

```bash
python -m pii_redact input/a.docx input/b.docx --mapping mapping.json --out-dir output/
```

If both files contain `Ramit Hansda` / `ramit hansda`, both become the **same** fake from `mapping.json`. Same for the same PAN/Aadhaar appearing with different spacing/casing.

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
| `--audit` | Optional JSONL/JSON of replacements applied (**redact Aadhaar/PAN in production logs** — last-4 / token only) |
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
      regex_detectors.py    # includes PAN + Aadhaar (+ Verhoeff)
      ner_presidio.py
      context_rules.py
      merge.py
      verhoeff.py           # optional small helper module
    mapping_store.py        # load/save JSON + canonicalize
    faker_factory.py        # seeded fake per type (incl. PAN/Aadhaar)
    replace.py              # run-aware replace
    emit.py                 # save docx
    types.py                # Span, PiiType, TextUnit dataclasses
  tests/
    test_regex.py           # include PAN + Aadhaar + Verhoeff cases
    test_merge.py
    test_mapping_consistency.py
    test_replace_runs.py
    fixtures/
  evaluation/
    labels.example.json     # gold spans (include PAN/AADHAAR samples)
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
2. **Types** — `PiiType` (include `PAN`, `AADHAAR`), `Span`, `TextUnit`, mapping entry dataclasses.  
3. **Extract** — DOCX walker for paragraphs, tables, headers/footers + run lists.  
4. **Regex detectors** — EMAIL, PHONE, SSN, CREDIT_CARD (+ Luhn), IP, **PAN**, **Aadhaar** (+ Verhoeff); DOB only with context helper.  
5. **Context rules** — positive boosters (incl. Aadhaar/PAN labels); **negative ticket/order ID suppression** that does not swallow validated PAN/Aadhaar.  
6. **Merge** — overlap resolution + type priority.  
7. **Mapping store** — canonicalize (PAN uppercase; Aadhaar digits-only), JSON load/save, atomic write.  
8. **Faker factory** — seeded generators per type incl. format-preserving PAN + checksum-valid Aadhaar; unit test same input → same fake.  
9. **Replace** — run-aware replacement; unit test split-run name.  
10. **Emit + CLI** — multi-file paths, `--mapping`, `--out-dir`.  
11. **Presidio/spaCy NER** — wire analyzer; merge with regex; allow `--no-ner`; custom recognizers for PAN/Aadhaar optional.  
12. **ORG / PERSON / ADDRESS** tuning — context + NER; avoid over-redacting common nouns.  
13. **End-to-end** on sample/assignment docx → write `output/*.redacted.docx`.  
14. **Evaluation** — gold labels file + `evaluate.py` (precision/recall per type + micro/macro); include PAN/Aadhaar fixtures.  
15. **README** — approach, tradeoffs, explicit “ticket/order IDs not redacted”, Aadhaar/PAN rules, how to run, model download.  
16. **Polish** — logging (no full Aadhaar/PAN), dry-run, edge cases (empty paras, nested tables if any).

---

## 9. Testing / evaluation criteria

### 9.1 Automated tests

- Regex true positives / true negatives for each pattern family.  
- Luhn reject for non-CC digit strings.  
- **PAN:** match `ABCDE1234F` / `abcde1234f` → same canonical `ABCDE1234F`; reject near-misses (`ABCD1234F`, `ABCDE12345`). Soft 4th-char signal does not hard-fail unknowns.  
- **Aadhaar:** spaced vs unspaced → same digits-only key; Verhoeff fail + no context → not emitted; Verhoeff pass or strong context → emitted; arbitrary 12-digit order IDs without cues → not Aadhaar.  
- Ticket-like IDs **not** redacted: e.g. `Ticket #A-10293`, `Order 998877`, `INV-123456789012` (unless validated as real PII).  
- Mapping consistency: `Ramit Hansda` and `ramit hansda` → identical fake; second file reuses mapping; same for PAN/Aadhaar surface forms.  
- Run-split replacement preserves surrounding text.  
- Determinism: fixed mapping seed path → identical fake across process restarts.

### 9.2 Assignment evaluation report

Prepare gold annotations (JSON) for at least one document (or a synthetic fixture mirroring the prospectus/ticket log):

```json
{
  "file": "samples/ticket_log.docx",
  "entities": [
    {"start": 10, "end": 22, "type": "PERSON", "text": "Rashi Patil"},
    {"start": 40, "end": 62, "type": "EMAIL", "text": "rashhi.patil@gmail.com"},
    {"start": 80, "end": 90, "type": "PAN", "text": "ABCDE1234F"},
    {"start": 100, "end": 114, "type": "AADHAAR", "text": "1234 5678 9012"}
  ]
}
```

### Precision & recall targets

**Status:** These are **targets**, not measured results. No production/gold evaluation has been run yet — report actual numbers in `evaluation/report.md` after labeling and scoring.

**Formulas** (entity-span level; same type + match policy below):

- **Precision** = TP / (TP + FP) — of predicted PII spans, how many are correct  
- **Recall** = TP / (TP + FN) — of gold PII spans, how many were found  

Match policy: prefer **span overlap IoU ≥ 0.5** + same type, or exact boundary match; state which in the eval report.

| PII type | Target precision | Target recall | Notes |
|---|---|---|---|
| `EMAIL` | ≥ 0.98 | ≥ 0.98 | regex; well-formed addresses |
| `PHONE` | ≥ 0.95 | ≥ 0.95 | regex + optional `phonenumbers` |
| `SSN` | ≥ 0.98 | ≥ 0.95 | hyphenated / digit pattern |
| `CREDIT_CARD` | ≥ 0.99 | ≥ 0.95 | Luhn-validated |
| `PAN` | ≥ 0.97 | ≥ 0.95 | format `ABCDE1234F` |
| `AADHAAR` | ≥ 0.95 | ≥ 0.90 | Verhoeff + context |
| `IP_ADDRESS` | ≥ 0.98 | ≥ 0.98 | IPv4 / IPv6 regex |
| `DOB` | ≥ 0.90 | ≥ 0.90 | context-dependent (not all dates) |
| `PERSON` | ≥ 0.90 | ≥ 0.85 | NER bottleneck |
| `ORG` | ≥ 0.85 | ≥ 0.80 | NER + company context |
| `ADDRESS` | ≥ 0.85 | ≥ 0.80 | NER LOCATION + address cues |
| **Overall (micro)** | **≥ 0.95** | **≥ 0.95** | user goal ~95%; structured types pull this up |

Assignment asks for precision / recall / accuracy in the evaluation report; it does **not** prescribe numeric thresholds — the table above is the project bar for regex + local NER (no LLM). Structured regex types should beat NER types; micro-average ~0.95 is achievable when EMAIL/PHONE/SSN/CC/PAN/IP dominate the gold set.

Also document in `evaluation/report.md`:

- Per-type and overall (micro-average) — include `PAN` and `AADHAAR` rows.  
- Call out false positives (over-redaction of order IDs as Aadhaar/PAN) and false negatives (missed names/addresses/Aadhaar) in README/report.

### 9.3 Definition of done

- CLI redacts multiple docx with shared mapping.  
- No LLM API usage anywhere in code paths.  
- Ticket/order IDs left intact unless PII-pattern match (incl. validated **PAN** / **Aadhaar**).  
- **PAN** detected via `\b[A-Z]{5}[0-9]{4}[A-Z]\b` (example `ABCDE1234F`), mapped uppercase, faked consistently.  
- **AADHAAR** detected with Verhoeff and/or context, digits-only canonical key, faked consistently.  
- Gold eval + report include **PAN** and **AADHAAR** rows.  
- README + evaluation numbers present.  
- `pytest` passes on unit tests (incl. PAN + Aadhaar + Verhoeff cases).

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
| Aadhaar false positives on 12-digit IDs | Require Verhoeff and/or Aadhaar context; never digit-shape alone |
| PAN false positives on ticket codes | Strict regex; optional context; negative ticket/order windows |
| Logging full Aadhaar/PAN | Tokenize/hash or last-4 in audit; encrypt mapping at rest in production |
| Presidio + large spaCy model heavy | Document download; allow `md` model; `--no-ner` ablation; size workers (§11) |
| Formatting loss on aggressive replace | Prefer in-run edits; note limitation for complex runs |
| Mapping file growth / PII at rest | `.gitignore` mapping; warn that mapping contains originals (sensitive) |
| Multi-worker mapping races | Centralized DB + atomic get-or-create (§11) |
| No LLM verification | Evaluation is human/gold-label based; do not add LLM judges |

---

## 11. Production scalability

Assignment v1 is a single-node CLI + JSON map. This section is the practical path to production without changing the detection/replace core.

### 11.1 Throughput & architecture

```
ingest DOCX → job queue → worker pool → store outputs + update mapping
```

| Mode | When to use |
|---|---|
| **Batch CLI** | Assignment, local runs, small corpora, one-shot redaction |
| **Service + queue** | Continuous ingest, multi-tenant APIs, SLAs |

Recommended service shape:

1. API / ingress accepts upload (or S3/GCS pointer) and enqueues a job.  
2. Queue: **SQS**, **Redis**, **Celery**, or **RQ** — pick one stack and stick to it.  
3. **Stateless workers** pull jobs: download DOCX → run same pipeline as CLI → upload redacted DOCX → ack.  
4. Object store for inputs/outputs; **centralized mapping store** (Postgres or Redis) — **not** local `mapping.json` when multiple workers run.  
5. Horizontal scale = add workers. NER (spaCy/Presidio) is **CPU- and memory-bound** — size worker RAM for the loaded model (often 1–3+ GB for `en_core_web_lg`); do not pack too many model-loading processes per node.

### 11.2 Mapping consistency at scale

Single source of truth table:

```text
UNIQUE (pii_type, normalized_key) → replacement, seed, created_at, …
```

Requirements:

- **Atomic get-or-create** (e.g. `INSERT … ON CONFLICT DO NOTHING` + re-read, or transactional upsert) so parallel workers/files do not invent two fakes for the same key.  
- Workers never keep a long-lived local JSON as authority; optional short TTL cache in front of DB is fine if invalidation is correct.  
- Keep the same **deterministic hash seed** (`hash(pii_type + normalized_key)`) so cold parallel workers that race before a write propagates still **converge on the same fake**.  
  - Caveat: if a non-deterministic generator were ever used, races would diverge — do not.  
  - Caveat: deterministic seed + eventual DB write still needs unique constraint so only one row wins; losers must reuse the winner’s stored value on conflict read.  
- Canonical keys stay as in §5.1 (`PAN` uppercase; `AADHAAR` digits-only).

### 11.3 Performance

- **Load spaCy/Presidio once per worker process**, not per file.  
- **Parallelize at file/job level** first; do not shard by paragraph until profiling says otherwise (run-aware replace + mapping makes paragraph sharding harder).  
- Stream / process large docs one at a time per worker; avoid loading huge corpora into one process.  
- Cache **compiled regex**; disable unused Presidio recognizers to cut analyzer cost.  
- Default **CPU** for NER; add GPU only if measured throughput justifies cost and Presidio/spaCy path can use it.

### 11.4 Reliability & ops

- **Idempotent jobs:** same input bytes + same mapping store → same redacted output (content-addressed job keys help).  
- Retries with backoff; **dead-letter queue** for poison docs.  
- Structured logging **without raw PII** (especially no full Aadhaar/PAN — last-4 or opaque token only).  
- Metrics: files/sec, spans/file, PII type counts (incl. `PAN` / `AADHAAR`), error rate, p95 job latency.  
- Audit: mapping access-controlled; retention + encryption; redacted audit exports for support.

### 11.5 Security

- Encrypt mapping store **at rest**; restrict IAM / DB roles (app write, auditor read).  
- Ephemeral scratch disk for DOCX; **delete after job** (success or failure).  
- TLS in transit for queue, object store, and DB.  
- **No LLM API** — keep the existing hard constraint in production too (data residency + cost + leakage).

### 11.6 Rollout path

| Stage | Shape |
|---|---|
| **v1** | Single-node CLI + JSON map (this assignment) |
| **v2** | API + queue workers + Postgres mapping (atomic get-or-create) |
| **v3** | Multi-tenant isolation, quotas, monitoring dashboards, SLOs |

Agents implementing later stages should reuse the same `extract → detect → merge → map → replace → emit` core; only the mapping backend and job harness change.

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

**Remember:** offline detection only — regex + Presidio/spaCy + rules; Faker (plus custom PAN/Aadhaar generators) for replacements; shared JSON mapping for cross-file consistency; never redact ticket/order IDs unless they match real PII patterns (including validated PAN/Aadhaar). No LLM API. See §11 before designing any multi-worker deployment.
