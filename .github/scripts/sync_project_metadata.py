#!/usr/bin/env python3
"""Render curated README sections and sync supported marketplace project fields."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.error import HTTPError
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / ".github/project-metadata.json"
# Recognized exact repository license. Fail closed when the legal text changes.
LICENSES = {"261eeb9e9f8b2b4b0d119366dda99c6fd7d35c64": "Apache-2.0"}
PROJECTS = {"qwasdf-1/soul-link": 1774398, "qwasdf-1/terra-rasa": 1774360,
            "qwasdf-1/LMFT-recrafted": 1827006}


def section(readme, heading):
    lines = readme.splitlines()
    matches = [i for i, line in enumerate(lines) if line.rstrip() == heading]
    if len(matches) != 1:
        raise ValueError(f"Missing or ambiguous README heading: {heading}")
    start = matches[0]
    # Direct content only: exclude nested developer subsections.
    end = next((i for i in range(start + 1, len(lines))
                if re.match(r"^#{1,6} ", lines[i])), len(lines))
    return "\n".join(lines[start:end]).strip()


def absolute_links(markdown, repo):
    base = f"https://github.com/{repo}"
    def link(match):
        prefix, dest = match.groups()
        if dest.startswith(("https://", "http://", "mailto:")):
            return match.group(0)
        if dest.startswith("#"):
            return prefix + base + "/blob/main/README.md" + dest + ")"
        if dest.startswith(("/", "../")) or "://" in dest:
            raise ValueError(f"Unsupported relative README link: {dest}")
        return prefix + base + "/blob/main/" + dest.removeprefix("./") + ")"
    return re.sub(r"(\]\()([^\s)]+)\)", link, markdown)


def render(repo, config, readme, license_bytes):
    if config["curseforge_id"] != PROJECTS[repo]:
        raise ValueError("Project configuration mismatch")
    blob = hashlib.sha1(f"blob {len(license_bytes)}\0".encode() + license_bytes).hexdigest()
    if blob not in LICENSES:
        raise ValueError("Unrecognized LICENSE contents; review the SPDX mapping before publishing")
    license_id = LICENSES[blob]
    parts = []
    if config.get("lead"):
        parts.append(config["lead"])
    if config.get("intro"):
        intro = re.split(r"^## ", readme, maxsplit=1, flags=re.M)[0]
        intro = re.sub(r"^# [^\n]+\n", "", intro, count=1).strip()
        parts.append(intro)
    parts.extend(section(readme, h) for h in config["sections"] if h != "### License")
    parts.append("## Installation\n\n" + config["install"])
    base = f"https://github.com/{repo}"
    parts.append(f"## Resources\n\n- [Source and full documentation]({base})\n- [Report an issue]({base}/issues)")
    if "### License" in config["sections"]:
        parts.append(section(readme, "### License").replace("### License", "## License", 1))
    else:
        parts.append(f"## License\n\n[Apache License 2.0]({base}/blob/main/LICENSE).")
    body = absolute_links("\n\n".join(parts).strip() + "\n", repo)
    summary = config["summary"]
    if not 1 <= len(summary) <= 150 or "\n" in summary or not body.strip():
        raise ValueError("Invalid marketplace summary or body")
    data = {"description": summary, "body": body, "license_id": license_id,
            "source_url": base, "issues_url": base + "/issues"}
    if config.get("wiki"):
        data["wiki_url"] = base + "/blob/main/" + config["wiki"]
    return data


def request(url, token, method="GET", payload=None):
    headers = {"User-Agent": "recrafted-project-metadata-sync/1.0",
               "Authorization": token, "Accept": "application/json"}
    raw = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        raw = json.dumps(payload).encode()
    try:
        with urlopen(Request(url, data=raw, headers=headers, method=method), timeout=30) as response:
            content = response.read()
            return json.loads(content) if content else None
    except HTTPError as exc:
        raise ValueError(f"Modrinth {method} returned HTTP {exc.code}; check project access and PROJECT_WRITE scope") from None


def normalized_source(url):
    if not isinstance(url, str):
        return ""
    return url.rstrip("/").removesuffix(".git").casefold()


def modrinth(data):
    token = os.environ.get("MODRINTH_TOKEN", "").strip()
    if not token:
        raise ValueError("Missing existing MODRINTH_TOKEN")
    identities = [os.environ.get(key, "").strip() for key in ("MODRINTH_PROJECT_ID", "MODRINTH_PROJECT_SLUG")]
    url = os.environ.get("MODRINTH_PROJECT_URL", "").strip()
    if url:
        parsed = urlsplit(url)
        match = re.fullmatch(r"/mod/([^/]+)/?", parsed.path)
        if parsed.scheme != "https" or parsed.netloc != "modrinth.com" or not match or parsed.query or parsed.fragment:
            raise ValueError("Invalid MODRINTH_PROJECT_URL")
        identities.append(match[1])
    identities = list(dict.fromkeys(x for x in identities if x))
    if not identities:
        raise ValueError("Set the existing Modrinth project identifier")
    api = "https://api.modrinth.com/v2/project/"
    current = None
    for identity in identities:
        candidate = request(api + quote(identity, safe=""), token)
        if normalized_source(candidate.get("source_url")) != normalized_source(data["source_url"]):
            raise ValueError("Modrinth project's source URL does not match this repository")
        if current and candidate["id"] != current["id"]:
            raise ValueError("Configured identifiers point to different projects")
        current = candidate
    def actual(obj, key):
        if key == "license_id":
            return (obj.get("license") or {}).get("id")
        return obj.get(key)
    changes = {key: value for key, value in data.items() if actual(current, key) != value}
    if not changes:
        print("Modrinth already matches generated metadata.")
        return
    endpoint = api + quote(current["id"], safe="")
    print("Modrinth fields to update: " + ", ".join(changes))
    request(endpoint, token, "PATCH", changes)
    after = request(endpoint, token)
    mismatched = [key for key, value in changes.items() if actual(after, key) != value]
    if mismatched:
        raise ValueError("Modrinth readback differs: " + ", ".join(mismatched))
    print("Modrinth project metadata updated and verified.")


def curseforge(repo, config, data):
    # Only this field is attempted; the endpoint is community-reported, not
    # documented in CurseForge's official upload API. Opt-in is mandatory.
    project = PROJECTS[repo]
    if config["curseforge_id"] != project or os.environ.get("CURSEFORGE_PROJECT_ID") != str(project):
        raise ValueError("Configured CurseForge project ID must match this repository")
    token = os.environ.get("CURSEFORGE_TOKEN", "").strip()
    if not token:
        raise ValueError("Missing existing CURSEFORGE_TOKEN")
    payload = {"description": data["body"], "descriptionType": "markdown"}
    result = subprocess.run(["curl", "--silent", "--show-error", "--fail-with-body",
        "--max-time", "45", "-H", f"X-Api-Token: {token}", "--form-string",
        "metadata=" + json.dumps(payload),
        f"https://minecraft.curseforge.com/api/projects/{project}/update-project"],
        capture_output=True, text=True)
    if result.returncode:
        raise ValueError(f"Experimental CurseForge description update failed (curl {result.returncode}); use the generated Markdown in the author dashboard")
    # There is no documented response contract or authoritative readback.
    print("CurseForge endpoint returned success status; verify the description in the author dashboard.")
    print("Summary, license and links remain manual on CurseForge.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("render", "modrinth", "curseforge"))
    parser.add_argument("--output", type=Path, default=Path("marketplace-metadata"))
    args = parser.parse_args()
    repo = os.environ["GITHUB_REPOSITORY"]
    config = json.loads(CONFIG.read_text(encoding="utf-8"))
    data = render(repo, config, (ROOT / "README.md").read_text(encoding="utf-8"),
                  (ROOT / "LICENSE").read_bytes())
    if args.mode == "render":
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "description.md").write_text(data["body"], encoding="utf-8")
        (args.output / "modrinth.json").write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
        manual = {**data, "project_id": config["curseforge_id"],
                  "license_name": "Apache License 2.0"}
        (args.output / "curseforge-fields.json").write_text(json.dumps(manual, indent=2) + "\n", encoding="utf-8")
        print("Summary:", data["description"])
        print("License:", data["license_id"])
        print(data["body"])
    else:
        if os.environ.get("GITHUB_REF") != "refs/heads/main" or os.environ.get("APPLY") != "true":
            raise ValueError("Metadata writes require main and explicit apply approval")
        if args.mode == "modrinth":
            modrinth(data)
        elif os.environ.get("CURSEFORGE_EXPERIMENTAL") == "true":
            curseforge(repo, config, data)
        else:
            print("CurseForge automated update disabled: use generated fields in author dashboard.")


if __name__ == "__main__":
    main()
