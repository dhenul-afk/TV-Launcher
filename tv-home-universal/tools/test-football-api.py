#!/usr/bin/env python3
"""Live football-data.org v4 smoke test. Never print or persist the API token.

Usage: set FOOTBALL_DATA_API_TOKEN, then run this script with Python 3.
An empty match list is valid (for example, during a competition break).
This verifies the API, not an installed Android app or a packaged API key.
"""

import datetime as dt
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://api.football-data.org/v4"
MAX_RESPONSE_BYTES = 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Do not forward the authentication header to a redirect destination."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def fetch(opener, path, token):
    request = urllib.request.Request(
        BASE + path,
        headers={"X-Auth-Token": token, "Accept": "application/json"},
    )
    for attempt in range(2):
        try:
            with opener.open(request, timeout=30) as response:
                if response.status != 200:
                    raise ValueError("unexpected HTTP status")
                raw = response.read(MAX_RESPONSE_BYTES + 1)
            if len(raw) > MAX_RESPONSE_BYTES:
                raise ValueError("response exceeds 1 MiB")
            payload = json.loads(raw)
            if not isinstance(payload, dict):
                raise ValueError("response is not a JSON object")
            return payload
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            if status == 429 and attempt == 0:
                print("Rate limit reached; retrying once in 65 seconds.", flush=True)
                time.sleep(65)
                continue
            # Do not print response bodies, request headers, or the token.
            raise ValueError("HTTP " + str(status)) from None
        except urllib.error.URLError:
            raise ValueError("network or TLS error") from None
    raise ValueError("request failed")


def validate(payload, collection):
    items = payload.get(collection)
    if not isinstance(items, list):
        raise ValueError("missing or invalid " + collection + " array")
    if collection == "matches":
        for match in items:
            if not isinstance(match, dict):
                raise ValueError("invalid match object")
            if not isinstance(match.get("utcDate"), str) or not isinstance(match.get("status"), str):
                raise ValueError("missing match date or status")
            for team in ("homeTeam", "awayTeam"):
                if not isinstance(match.get(team), dict):
                    raise ValueError("missing match team")
        return len(items)
    rows = 0
    for standing in items:
        if not isinstance(standing, dict) or not isinstance(standing.get("table"), list):
            raise ValueError("invalid standings table")
        rows += len(standing["table"])
    return rows


def main():
    token = os.environ.get("FOOTBALL_DATA_API_TOKEN", "").strip()
    if not token:
        print("FAIL: repository secret FOOTBALL_DATA_API_TOKEN is missing or empty.")
        return 1

    today = dt.datetime.now(dt.timezone.utc).date()

    def matches_path(start, end):
        return "/matches?" + urllib.parse.urlencode({"dateFrom": start, "dateTo": end})

    # Mirror the widget's schedule/results windows and supported standings leagues.
    cases = [
        ("Schedule (next 7 days)", matches_path(today, today + dt.timedelta(days=7)), "matches"),
        ("Results (last 7 days)", matches_path(today - dt.timedelta(days=7), today), "matches"),
    ]
    cases.extend(("Standings " + code, "/competitions/" + code + "/standings", "standings")
                 for code in ("PL", "PD", "CL"))
    opener = urllib.request.build_opener(NoRedirect())
    summary = ["## Football API smoke test", "", "| Check | Result |", "| --- | --- |"]
    failures = 0
    for label, path, collection in cases:
        try:
            count = validate(fetch(opener, path, token), collection)
            result = "PASS: HTTP 200; valid JSON; " + str(count) + " items"
        except (ValueError, TimeoutError, OSError):
            # Only controlled diagnostics are logged; no response body or secret.
            failures += 1
            result = "FAIL: request or response validation failed"
        print(label + ": " + result, flush=True)
        summary.append("| " + label + " | " + result + " |")
        time.sleep(1)

    summary.extend(["", "Token is read from the repository secret and is never printed or packaged in the APK."])
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with Path(summary_path).open("a", encoding="utf-8") as handle:
            handle.write("\n".join(summary) + "\n")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
