"""
Email template rendering for job-match notifications.
"""

from __future__ import annotations

from typing import List

from models.schemas import MatchResult


_HTML_TEMPLATE = """\
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
  <title>New Job Match</title>
  <style>
    body  {{ font-family: Arial, sans-serif; background: #f5f7fa; margin: 0; padding: 0; }}
    .wrap {{ max-width: 600px; margin: 40px auto; background: #fff;
             border-radius: 8px; overflow: hidden;
             box-shadow: 0 2px 8px rgba(0,0,0,.1); }}
    .hdr  {{ background: #1a73e8; padding: 28px 32px; color: #fff; }}
    .hdr h1 {{ margin: 0; font-size: 22px; }}
    .body {{ padding: 28px 32px; color: #333; }}
    .score {{ display: inline-block; background: #e8f0fe; color: #1a73e8;
              font-weight: bold; border-radius: 4px;
              padding: 4px 10px; margin: 12px 0; font-size: 15px; }}
    .reasons {{ background: #f8f9fa; border-left: 4px solid #1a73e8;
                padding: 12px 16px; border-radius: 0 4px 4px 0; margin: 16px 0; }}
    .reasons li {{ margin: 6px 0; font-size: 14px; color: #555; }}
    .cta  {{ text-align: center; margin: 24px 0; }}
    .btn  {{ background: #1a73e8; color: #fff !important; padding: 14px 32px;
             border-radius: 6px; text-decoration: none; font-size: 15px;
             font-weight: bold; display: inline-block; }}
    .ftr  {{ font-size: 12px; color: #999; text-align: center;
             padding: 16px 32px; border-top: 1px solid #eee; }}
  </style>
</head>
<body>
  <div class="wrap">
    <div class="hdr">
      <h1>&#127775; New Job Match Found</h1>
    </div>
    <div class="body">
      <p>Hi <strong>{candidate_name}</strong>,</p>
      <p>We found a <strong>{job_title}</strong> position that closely matches your profile.</p>
      <div class="score">Match Score: {score_pct}%</div>
      <p><strong>Why you're a great fit:</strong></p>
      <div class="reasons">
        <ul>
          {reasons_html}
        </ul>
      </div>
      <div class="cta">
        <a href="{apply_url}" class="btn">View &amp; Apply Now</a>
      </div>
      <p style="font-size:13px;color:#666;">
        This recommendation was generated automatically based on your profile.
        If you are not interested, you can
        <a href="{unsubscribe_url}">unsubscribe here</a>.
      </p>
    </div>
    <div class="ftr">
      &copy; JobMatch &bull; {candidate_email}
    </div>
  </div>
</body>
</html>
"""

_PLAIN_TEMPLATE = """\
Hi {candidate_name},

We found a new job that matches your profile:
  {job_title}  |  Match Score: {score_pct}%

Why you're a great fit:
{reasons_text}

Apply here: {apply_url}

To unsubscribe: {unsubscribe_url}

— The JobMatch Team
"""

_BASE_URL = "https://jobmatch.example.com"


def _apply_url(job_id: str, candidate_id: str) -> str:
    return f"{_BASE_URL}/jobs/{job_id}?ref={candidate_id}"


def _unsubscribe_url(candidate_id: str) -> str:
    return f"{_BASE_URL}/unsubscribe?c={candidate_id}"


def render_html(match: MatchResult) -> str:
    reasons_html = "\n          ".join(
        f"<li>{r}</li>" for r in match.match_reasons
    )
    return _HTML_TEMPLATE.format(
        candidate_name=match.candidate_name,
        candidate_email=match.candidate_email,
        job_title=match.job_title,
        score_pct=round(match.similarity_score * 100),
        reasons_html=reasons_html,
        apply_url=_apply_url(match.job_id, match.candidate_id),
        unsubscribe_url=_unsubscribe_url(match.candidate_id),
    )


def render_plain(match: MatchResult) -> str:
    reasons_text = "\n".join(f"  • {r}" for r in match.match_reasons)
    return _PLAIN_TEMPLATE.format(
        candidate_name=match.candidate_name,
        candidate_email=match.candidate_email,
        job_title=match.job_title,
        score_pct=round(match.similarity_score * 100),
        reasons_text=reasons_text,
        apply_url=_apply_url(match.job_id, match.candidate_id),
        unsubscribe_url=_unsubscribe_url(match.candidate_id),
    )


def render_subject(match: MatchResult) -> str:
    score = round(match.similarity_score * 100)
    return f"[{score}% match] {match.job_title} — New opportunity for you"
