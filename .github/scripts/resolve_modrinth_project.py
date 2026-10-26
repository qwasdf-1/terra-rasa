#!/usr/bin/env python3
"""Resolve a Modrinth project ID and verify its configured GitHub source."""

import json
import os
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(1)


project_id = os.environ.get("MODRINTH_PROJECT_ID", "").strip()
expected_source = os.environ["EXPECTED_SOURCE_URL"].rstrip("/")
user_agent = os.environ["MODRINTH_USER_AGENT"]
modrinth_token = os.environ.get("MODRINTH_TOKEN", "").strip()

if not project_id:
    fail("Set the repository Actions variable MODRINTH_PROJECT_ID")

if not modrinth_token:
    fail("Set MODRINTH_TOKEN with PROJECT_READ and VERSION_CREATE permissions")

request = Request(
    "https://api.modrinth.com/v2/project/" + quote(project_id, safe=""),
    headers={
        "User-Agent": user_agent,
        "Accept": "application/json",
        "Authorization": modrinth_token,
    },
)
try:
    with urlopen(request, timeout=30) as response:
        project = json.load(response)
except (HTTPError, URLError, TimeoutError) as exc:
    fail(f"Could not resolve Modrinth project ID {project_id!r}: {exc}")

resolved_id = project.get("id")
resolved_slug = project.get("slug")
source_url = (project.get("source_url") or "").rstrip("/")
if not resolved_id or not resolved_slug:
    fail("Modrinth response did not include the project ID and slug")
if source_url != expected_source:
    fail(f"Project source_url must be {expected_source!r}; got {source_url!r}")

print(json.dumps({"id": resolved_id, "slug": resolved_slug}))
