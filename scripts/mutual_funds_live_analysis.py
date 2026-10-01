#!/usr/bin/env python3
"""Fetch live Indian mutual fund NAV and TER data, then print CAGR analysis.

Data sources:
  - NAV and historical NAV: https://api.mfapi.in
  - Total expense ratio (TER): https://www.amfiindia.com/ter-of-mf-schemes

Examples:
  python3 scripts/mutual_funds_live_analysis.py
  python3 scripts/mutual_funds_live_analysis.py --search "Parag Parikh Flexi Cap"
  python3 scripts/mutual_funds_live_analysis.py --holding 122639:500000 --holding 125354:250000
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import re
import sys
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass
from difflib import SequenceMatcher
from typing import Any


MFAPI_BASE_URL = "https://api.mfapi.in"
AMFI_BASE_URL = "https://www.amfiindia.com"
HTTP_HEADERS = {
    "User-Agent": "Mozilla/5.0 mutual-funds-live-analysis/1.0",
    "Accept": "application/json, text/plain, */*",
    "Referer": f"{AMFI_BASE_URL}/ter-of-mf-schemes",
}

DEFAULT_HOLDINGS = [
    ("122639", 100000.0),  # Parag Parikh Flexi Cap Fund - Direct Plan - Growth
    ("120716", 100000.0),  # UTI Nifty 50 Index Fund - Growth Option- Direct
    ("125354", 100000.0),  # Axis Small Cap Fund - Direct Plan - Growth
    ("118825", 100000.0),  # Mirae Asset Large Cap Fund - Direct Plan - Growth
]


@dataclass(frozen=True)
class NavPoint:
    date: dt.date
    nav: float


@dataclass
class FundAnalysis:
    scheme_code: str
    scheme_name: str
    latest_date: dt.date
    latest_nav: float
    holding_amount: float
    cagr_by_years: dict[int, float | None]
    yearly_returns: dict[int, float]
    expense_ratio: float | None
    ter_plan: str
    ter_date: str
    ter_match: str
    ter_match_score: float


def fetch_text(url: str, *, timeout: int = 35, retries: int = 3) -> str:
    last_error: Exception | None = None
    for attempt in range(retries):
        try:
            request = urllib.request.Request(url, headers=HTTP_HEADERS)
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.read().decode("utf-8", errors="replace")
        except Exception as error:  # noqa: BLE001 - CLI should retry transient network issues.
            last_error = error
            if attempt < retries - 1:
                time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"Failed to fetch {url}: {last_error}") from last_error


def fetch_json(url: str, *, timeout: int = 35, retries: int = 3) -> Any:
    return json.loads(fetch_text(url, timeout=timeout, retries=retries))


def parse_mfapi_date(value: str) -> dt.date:
    return dt.datetime.strptime(value, "%d-%m-%Y").date()


def parse_iso_date(value: str) -> dt.date:
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00")).date()


def current_financial_year(today: dt.date | None = None) -> str:
    today = today or dt.date.today()
    start_year = today.year if today.month >= 4 else today.year - 1
    return f"{start_year}-{start_year + 1}"


def normalize_name(value: str) -> str:
    value = value.lower().replace("&", " and ")
    value = re.sub(r"[^a-z0-9]+", " ", value)
    stop_words = {
        "direct",
        "regular",
        "plan",
        "growth",
        "option",
        "idcw",
        "dividend",
        "reinvestment",
        "payout",
        "reinv",
    }
    words = [word for word in value.split() if word not in stop_words]
    return " ".join(words)


def percent(value: float | None) -> str:
    if value is None or not math.isfinite(value):
        return "n/a"
    return f"{value * 100:.2f}%"


def money(value: float) -> str:
    return f"INR {round(value):,}"


def markdown_table(headers: list[str], rows: list[list[str]]) -> str:
    widths = [
        max(len(headers[index]), *(len(row[index]) for row in rows)) if rows else len(headers[index])
        for index in range(len(headers))
    ]
    header_line = "| " + " | ".join(headers[index].ljust(widths[index]) for index in range(len(headers))) + " |"
    separator = "| " + " | ".join("-" * widths[index] for index in range(len(headers))) + " |"
    row_lines = [
        "| " + " | ".join(row[index].ljust(widths[index]) for index in range(len(headers))) + " |"
        for row in rows
    ]
    return "\n".join([header_line, separator, *row_lines])


def search_schemes(query: str) -> list[dict[str, Any]]:
    encoded_query = urllib.parse.quote(query)
    url = f"{MFAPI_BASE_URL}/mf/search?q={encoded_query}"
    data = fetch_json(url)
    if not isinstance(data, list):
        raise RuntimeError("Unexpected MFAPI search response")
    return data


def fetch_nav_history(scheme_code: str) -> tuple[dict[str, Any], list[NavPoint]]:
    url = f"{MFAPI_BASE_URL}/mf/{urllib.parse.quote(str(scheme_code))}"
    payload = fetch_json(url, timeout=45)
    if payload.get("status") != "SUCCESS":
        raise RuntimeError(f"MFAPI did not return SUCCESS for scheme {scheme_code}")

    history = [
        NavPoint(date=parse_mfapi_date(point["date"]), nav=float(point["nav"]))
        for point in payload.get("data", [])
        if point.get("date") and point.get("nav")
    ]
    history.sort(key=lambda point: point.date)
    if not history:
        raise RuntimeError(f"No NAV history returned for scheme {scheme_code}")
    return payload.get("meta", {}), history


def date_years_before(value: dt.date, years: int) -> dt.date:
    try:
        return value.replace(year=value.year - years)
    except ValueError:
        return value.replace(month=2, day=28, year=value.year - years)


def nav_on_or_before(history: list[NavPoint], target: dt.date) -> NavPoint | None:
    candidates = [point for point in history if point.date <= target]
    return candidates[-1] if candidates else None


def calculate_cagr(start: NavPoint, end: NavPoint) -> float | None:
    elapsed_years = (end.date - start.date).days / 365.25
    if elapsed_years <= 0 or start.nav <= 0 or end.nav <= 0:
        return None
    return (end.nav / start.nav) ** (1 / elapsed_years) - 1


def trailing_cagrs(history: list[NavPoint], years: list[int]) -> dict[int, float | None]:
    latest = history[-1]
    result: dict[int, float | None] = {}
    for year_count in years:
        start = nav_on_or_before(history, date_years_before(latest.date, year_count))
        result[year_count] = calculate_cagr(start, latest) if start else None
    return result


def calendar_year_returns(history: list[NavPoint]) -> dict[int, float]:
    year_end_nav: dict[int, NavPoint] = {}
    for point in history:
        year_end_nav[point.date.year] = point

    returns: dict[int, float] = {}
    years = sorted(year_end_nav)
    for previous_year, year in zip(years, years[1:]):
        previous = year_end_nav[previous_year]
        current = year_end_nav[year]
        if previous.nav > 0:
            returns[year] = current.nav / previous.nav - 1
    return returns


def fetch_embedded_ter_years() -> list[str]:
    html = fetch_text(f"{AMFI_BASE_URL}/ter-of-mf-schemes", timeout=45)
    return list(dict.fromkeys(re.findall(r'"TER_Year":"([^"]+)"', html)))


def fetch_latest_ter_month() -> str:
    candidate_years = [current_financial_year()]
    for year in fetch_embedded_ter_years():
        if year not in candidate_years:
            candidate_years.append(year)

    for year in candidate_years:
        url = f"{AMFI_BASE_URL}/api/populate-ter-month?year={urllib.parse.quote(year)}"
        try:
            months = fetch_json(url)
        except RuntimeError:
            continue
        if isinstance(months, list) and months:
            month_number = months[0].get("MonthNumber")
            if month_number:
                return str(month_number)

    raise RuntimeError("Could not determine latest AMFI TER month")


def fetch_ter_rows(month: str) -> list[dict[str, Any]]:
    query = urllib.parse.urlencode(
        {
            "MF_ID": "All",
            "Month": month,
            "strCat": "-1",
            "strType": "-1",
            "page": "1",
            "pageSize": "25000",
        }
    )
    url = f"{AMFI_BASE_URL}/api/populate-te-rdata-revised?{query}"
    payload = fetch_json(url, timeout=60)
    rows = payload.get("data", [])
    if not isinstance(rows, list):
        raise RuntimeError("Unexpected AMFI TER response")
    return rows


def dedupe_latest_ter_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    latest_by_scheme: dict[tuple[str, str], dict[str, Any]] = {}
    for row in rows:
        scheme_name = str(row.get("Scheme_Name", ""))
        date_text = str(row.get("TER_Date", ""))
        if not scheme_name or not date_text:
            continue

        key = (normalize_name(scheme_name), str(row.get("MF_ID", "")))
        current = latest_by_scheme.get(key)
        if not current:
            latest_by_scheme[key] = row
            continue

        try:
            if parse_iso_date(date_text) > parse_iso_date(str(current.get("TER_Date", ""))):
                latest_by_scheme[key] = row
        except ValueError:
            latest_by_scheme[key] = row

    return list(latest_by_scheme.values())


def infer_plan_type(scheme_name: str) -> str:
    normalized = scheme_name.lower()
    if "regular" in normalized:
        return "regular"
    return "direct"


def row_expense_ratio(row: dict[str, Any], plan_type: str) -> float | None:
    field = "R_TER" if plan_type == "regular" else "D_TER"
    value = row.get(field)
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def match_ter_row(scheme_name: str, rows: list[dict[str, Any]]) -> tuple[dict[str, Any] | None, float]:
    target = normalize_name(scheme_name)
    if not target:
        return None, 0.0

    best_row: dict[str, Any] | None = None
    best_score = 0.0
    for row in rows:
        candidate_name = str(row.get("Scheme_Name", ""))
        candidate = normalize_name(candidate_name)
        if not candidate:
            continue

        if candidate == target:
            score = 1.0
        elif candidate in target or target in candidate:
            score = 0.96
        else:
            score = SequenceMatcher(None, target, candidate).ratio()

        if score > best_score:
            best_score = score
            best_row = row

    if best_score < 0.72:
        return None, best_score
    return best_row, best_score


def analyze_fund(
    scheme_code: str,
    holding_amount: float,
    years: list[int],
    ter_rows: list[dict[str, Any]],
) -> FundAnalysis:
    meta, history = fetch_nav_history(scheme_code)
    scheme_name = str(meta.get("scheme_name") or scheme_code)
    latest = history[-1]
    plan_type = infer_plan_type(scheme_name)
    ter_row, score = match_ter_row(scheme_name, ter_rows)
    expense_ratio = row_expense_ratio(ter_row, plan_type) if ter_row else None

    return FundAnalysis(
        scheme_code=scheme_code,
        scheme_name=scheme_name,
        latest_date=latest.date,
        latest_nav=latest.nav,
        holding_amount=holding_amount,
        cagr_by_years=trailing_cagrs(history, years),
        yearly_returns=calendar_year_returns(history),
        expense_ratio=expense_ratio,
        ter_plan=plan_type,
        ter_date=str(ter_row.get("TER_Date", ""))[:10] if ter_row else "n/a",
        ter_match=str(ter_row.get("Scheme_Name", "n/a")) if ter_row else "n/a",
        ter_match_score=score,
    )


def parse_holding(value: str) -> tuple[str, float]:
    if ":" not in value:
        return value.strip(), 100000.0
    code, amount = value.split(":", 1)
    try:
        parsed_amount = float(amount.replace(",", "").strip())
    except ValueError as error:
        raise argparse.ArgumentTypeError(f"Invalid holding amount in {value!r}") from error
    if parsed_amount < 0:
        raise argparse.ArgumentTypeError("Holding amount cannot be negative")
    return code.strip(), parsed_amount


def parse_years(value: str) -> list[int]:
    years = []
    for token in value.split(","):
        token = token.strip()
        if not token:
            continue
        year = int(token)
        if year <= 0:
            raise argparse.ArgumentTypeError("CAGR years must be positive")
        years.append(year)
    return sorted(set(years))


def print_search_results(query: str) -> None:
    rows = [
        [str(item.get("schemeCode", "")), str(item.get("schemeName", ""))]
        for item in search_schemes(query)[:20]
    ]
    print(f"# MFAPI search results for: {query}\n")
    print(markdown_table(["Scheme code", "Scheme name"], rows))


def print_analysis(analyses: list[FundAnalysis], years: list[int], ter_month: str) -> None:
    cagr_headers = [f"{year}Y CAGR" for year in years]
    rows = []
    for analysis in analyses:
        annual_expense = (
            analysis.holding_amount * analysis.expense_ratio / 100
            if analysis.expense_ratio is not None
            else 0.0
        )
        rows.append(
            [
                analysis.scheme_code,
                analysis.scheme_name,
                analysis.latest_date.isoformat(),
                f"{analysis.latest_nav:.4f}",
                money(analysis.holding_amount),
                *(percent(analysis.cagr_by_years[year]) for year in years),
                f"{analysis.expense_ratio:.2f}%" if analysis.expense_ratio is not None else "n/a",
                money(annual_expense) if analysis.expense_ratio is not None else "n/a",
            ]
        )

    total_holding = sum(analysis.holding_amount for analysis in analyses)
    weighted_expense = 0.0
    expense_weight = 0.0
    for analysis in analyses:
        if analysis.expense_ratio is not None:
            weighted_expense += analysis.holding_amount * analysis.expense_ratio
            expense_weight += analysis.holding_amount
    weighted_expense_ratio = weighted_expense / expense_weight if expense_weight else None
    annual_expense = total_holding * weighted_expense_ratio / 100 if weighted_expense_ratio is not None else None

    weighted_cagrs: dict[int, float | None] = {}
    for year in years:
        weighted_value = 0.0
        weighted_amount = 0.0
        for analysis in analyses:
            cagr = analysis.cagr_by_years[year]
            if cagr is not None:
                weighted_value += analysis.holding_amount * cagr
                weighted_amount += analysis.holding_amount
        weighted_cagrs[year] = weighted_value / weighted_amount if weighted_amount else None

    print("# Live mutual fund analysis\n")
    print(f"Fetched NAV from MFAPI and TER from AMFI month `{ter_month}`.\n")
    print(markdown_table(["Code", "Scheme", "NAV date", "NAV", "Holding", *cagr_headers, "TER", "Annual TER cost"], rows))
    print("\n## Portfolio summary\n")
    summary_rows = [
        ["Total analyzed holding", money(total_holding)],
        ["Weighted TER", f"{weighted_expense_ratio:.2f}%" if weighted_expense_ratio is not None else "n/a"],
        ["Estimated annual TER cost", money(annual_expense) if annual_expense is not None else "n/a"],
    ]
    for year in years:
        summary_rows.append([f"Weighted {year}Y CAGR", percent(weighted_cagrs[year])])
    print(markdown_table(["Metric", "Value"], summary_rows))

    recent_years = sorted({year for analysis in analyses for year in analysis.yearly_returns})[-6:]
    if recent_years:
        print("\n## Calendar-year returns\n")
        yearly_rows = []
        for analysis in analyses:
            yearly_rows.append(
                [
                    analysis.scheme_code,
                    *[percent(analysis.yearly_returns.get(year)) for year in recent_years],
                ]
            )
        print(markdown_table(["Code", *[str(year) for year in recent_years]], yearly_rows))

    print("\n## TER matching details\n")
    detail_rows = [
        [
            analysis.scheme_code,
            analysis.ter_plan,
            analysis.ter_date,
            f"{analysis.ter_match_score:.2f}",
            analysis.ter_match,
        ]
        for analysis in analyses
    ]
    print(markdown_table(["Code", "Plan", "TER date", "Match score", "AMFI matched scheme"], detail_rows))
    print(
        "\nTER is already reflected in published NAV. Annual TER cost is an estimate for visibility, "
        "not an extra separately charged amount. This is not investment advice."
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Live Indian mutual fund CAGR and TER analyzer")
    parser.add_argument("--search", help="Search MFAPI schemes by name and print scheme codes")
    parser.add_argument(
        "--holding",
        action="append",
        type=parse_holding,
        help="Scheme holding as SCHEME_CODE:AMOUNT. Can be repeated. Amount defaults to 100000.",
    )
    parser.add_argument(
        "--scheme",
        action="append",
        help="Scheme code to analyze with a default notional holding of INR 100000. Can be repeated.",
    )
    parser.add_argument("--years", type=parse_years, default=[1, 3, 5], help="Comma-separated CAGR windows")
    parser.add_argument("--ter-month", help="AMFI TER month in MM-YYYY format. Defaults to latest available.")
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()

    try:
        if args.search:
            print_search_results(args.search)
            return 0

        holdings = list(args.holding or [])
        holdings.extend((scheme, 100000.0) for scheme in (args.scheme or []))
        if not holdings:
            holdings = DEFAULT_HOLDINGS

        ter_month = args.ter_month or fetch_latest_ter_month()
        ter_rows = dedupe_latest_ter_rows(fetch_ter_rows(ter_month))
        analyses = [analyze_fund(code, amount, args.years, ter_rows) for code, amount in holdings]
        print_analysis(analyses, args.years, ter_month)
        return 0
    except Exception as error:  # noqa: BLE001 - concise CLI error reporting.
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
