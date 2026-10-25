#!/usr/bin/env python3
"""Resolve a Modrinth project URL or ID and verify its configured GitHub source."""

import json
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import quote, unquote, urlparse
from urllib.request import Request, urlopen


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(1)


def slug_from_url(value: str) -> str:
    parsed = urlparse(value)
    if (
        parsed.scheme != "https"
        or parsed.hostname not in {"modrinth.com", "www.modrinth.com"}
        or parsed.username
        or parsed.password
    ):
        fail("Project URL must use https://modrinth.com/...")
    slug = unquote(parsed.path.rstrip("/").split("/")[-1])
    if not re.fullmatch(r"[A-Za-z0-9._-]{3,64}", slug):
        fail("Could not read a valid project slug from the configured Modrinth URL")
    return slug


project_id = os.environ.get("MODRINTH_PROJECT_ID", "").strip()
project_url = os.environ.get("MODRINTH_PROJECT_URL", "").strip()
expected_source = os.environ["EXPECTED_SOURCE_URL"].rstrip("/")
user_agent = os.environ["MODRINTH_USER_AGENT"]

if not project_id and not project_url:
    fail("Set a Modrinth project URL for the first publish, or pin its ID in Actions variables")

# A pinned ID takes precedence; the URL is only needed for first-time resolution.
expected_slug = slug_from_url(project_url) if project_url and not project_id else None
lookup = project_id or expected_slug
request = Request(
    "https://api.modrinth.com/v2/project/" + quote(lookup, safe=""),
    headers={"User-Agent": user_agent, "Accept": "application/json"},
)
try:
    with urlopen(request, timeout=30) as response:
        project = json.load(response)
except (HTTPError, URLError, TimeoutError) as exc:
    fail(f"Could not resolve Modrinth project {lookup!r}: {exc}")

resolved_id = project.get("id")
resolved_slug = project.get("slug")
source_url = (project.get("source_url") or "").rstrip("/")
if not resolved_id or not resolved_slug:
    fail("Modrinth response did not include the project ID and slug")
if expected_slug and expected_slug != resolved_slug:
    fail(f"URL slug {expected_slug!r} resolved to unexpected slug {resolved_slug!r}")
if source_url != expected_source:
    fail(f"Project source_url must be {expected_source!r}; got {source_url!r}")

print(json.dumps({"id": resolved_id, "slug": resolved_slug}))
