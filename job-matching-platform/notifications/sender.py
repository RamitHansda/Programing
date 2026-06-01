"""
Email sender — wraps SendGrid and AWS SES with a common interface.

Retry policy
------------
  Attempt 1 → immediate
  Attempt 2 → 2 s backoff
  Attempt 3 → 4 s backoff
  Attempt 4+ → DLQ (dead-letter Kafka topic)

Both SendGrid and SES have rate limits we must respect:
  SendGrid Pro  →  up to 600 K emails/hour ≈ 166/s per API key.
  AWS SES       →  up to 14 emails/s per region by default (can increase to 14 K/s).

With 50 workers × 100 concurrency = 5 000 in-flight calls across the cluster,
the global Redis rate limiter caps actual throughput at settings.redis.rate_limit_rps.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Optional

from config.settings import settings
from notifications.templates import render_html, render_plain, render_subject
from models.schemas import MatchResult, MatchStatus

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Optional provider SDKs
# ---------------------------------------------------------------------------
try:
    import sendgrid as _sendgrid
    from sendgrid.helpers.mail import Mail as _SGMail
    SENDGRID_AVAILABLE = True
except ImportError:
    _sendgrid = None  # type: ignore[assignment]
    _SGMail = None  # type: ignore[assignment]
    SENDGRID_AVAILABLE = False

try:
    import boto3 as _boto3
    SES_AVAILABLE = True
except ImportError:
    _boto3 = None  # type: ignore[assignment]
    SES_AVAILABLE = False


# ---------------------------------------------------------------------------
# Result type
# ---------------------------------------------------------------------------

@dataclass
class SendResult:
    success: bool
    provider_message_id: Optional[str] = None
    error: Optional[str] = None
    attempts: int = 1


# ---------------------------------------------------------------------------
# Provider interface
# ---------------------------------------------------------------------------

class EmailProvider:
    def send(self, to: str, subject: str, html: str, plain: str) -> SendResult:
        raise NotImplementedError


class SendGridProvider(EmailProvider):
    def __init__(self, api_key: str, from_address: str, from_name: str):
        if not SENDGRID_AVAILABLE:
            raise ImportError("sendgrid not installed. pip install sendgrid")
        self._client = _sendgrid.SendGridAPIClient(api_key=api_key)
        self._from = from_address
        self._from_name = from_name

    def send(self, to: str, subject: str, html: str, plain: str) -> SendResult:
        message = _SGMail(
            from_email=(self._from, self._from_name),
            to_emails=to,
            subject=subject,
            html_content=html,
            plain_text_content=plain,
        )
        try:
            resp = self._client.send(message)
            msg_id = resp.headers.get("X-Message-Id", "")
            return SendResult(success=True, provider_message_id=msg_id)
        except Exception as exc:
            return SendResult(success=False, error=str(exc))


class SESProvider(EmailProvider):
    def __init__(self, from_address: str, from_name: str, region: str = "us-east-1"):
        if not SES_AVAILABLE:
            raise ImportError("boto3 not installed. pip install boto3")
        self._client = _boto3.client("ses", region_name=region)
        self._source = f"{from_name} <{from_address}>"

    def send(self, to: str, subject: str, html: str, plain: str) -> SendResult:
        try:
            resp = self._client.send_email(
                Source=self._source,
                Destination={"ToAddresses": [to]},
                Message={
                    "Subject": {"Data": subject, "Charset": "UTF-8"},
                    "Body": {
                        "Html": {"Data": html, "Charset": "UTF-8"},
                        "Text": {"Data": plain, "Charset": "UTF-8"},
                    },
                },
            )
            msg_id = resp.get("MessageId", "")
            return SendResult(success=True, provider_message_id=msg_id)
        except Exception as exc:
            return SendResult(success=False, error=str(exc))


class StubProvider(EmailProvider):
    """No-op provider for testing and local development."""

    def __init__(self):
        self.sent: list = []

    def send(self, to: str, subject: str, html: str, plain: str) -> SendResult:
        self.sent.append({"to": to, "subject": subject})
        logger.info("[STUB] Email to %s | %s", to, subject)
        return SendResult(success=True, provider_message_id=f"stub-{len(self.sent)}")


# ---------------------------------------------------------------------------
# Factory
# ---------------------------------------------------------------------------

def create_provider() -> EmailProvider:
    cfg = settings.email
    provider_name = cfg.provider.lower()

    if provider_name == "sendgrid" and SENDGRID_AVAILABLE and cfg.api_key:
        return SendGridProvider(cfg.api_key, cfg.from_address, cfg.from_name)
    if provider_name == "ses" and SES_AVAILABLE:
        return SESProvider(cfg.from_address, cfg.from_name)

    logger.warning(
        "Email provider '%s' not available or not configured — using stub.", provider_name
    )
    return StubProvider()


# ---------------------------------------------------------------------------
# High-level send function with retry
# ---------------------------------------------------------------------------

def send_match_email(
    match: MatchResult,
    provider: EmailProvider,
    max_retries: int = 3,
    backoff_base: float = 2.0,
) -> SendResult:
    """
    Render and send a match notification email with exponential-backoff retries.
    Updates match.status in place.
    """
    subject = render_subject(match)
    html    = render_html(match)
    plain   = render_plain(match)

    attempt = 0
    last_result: Optional[SendResult] = None

    while attempt < max_retries:
        attempt += 1
        result = provider.send(match.candidate_email, subject, html, plain)
        result.attempts = attempt

        if result.success:
            match.status = MatchStatus.EMAIL_SENT
            logger.info(
                "Email sent to %s for job %s (attempt=%d, msg_id=%s).",
                match.candidate_email,
                match.job_id,
                attempt,
                result.provider_message_id,
            )
            return result

        logger.warning(
            "Email attempt %d/%d failed for %s: %s",
            attempt, max_retries, match.candidate_email, result.error,
        )
        last_result = result

        if attempt < max_retries:
            sleep_time = backoff_base ** attempt
            time.sleep(sleep_time)

    # All retries exhausted.
    match.status = MatchStatus.EMAIL_FAILED
    logger.error(
        "All %d email attempts failed for match %s (%s).",
        max_retries, match.match_id, match.candidate_email,
    )
    return last_result or SendResult(success=False, error="unknown", attempts=attempt)
