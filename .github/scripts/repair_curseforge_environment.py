#!/usr/bin/env python3
"""Repair existing CurseForge environment tags without replacing the uploaded files."""
import json
import os
import re
import subprocess
from urllib.request import Request, urlopen

API = "https://api.curseforge.com/v1"
AUTHOR_API = "https://minecraft.curseforge.com/api"
PROJECTS = {"qwasdf-1/soul-link": (1774398, "soullink"),
            "qwasdf-1/terra-rasa": (1774360, "terra-rasa")}


def read_api(path, key):
    req = Request(API + path, headers={"x-api-key": key, "Accept": "application/json"})
    with urlopen(req, timeout=30) as response:
        return json.load(response)


def all_files(project, key):
    index = 0
    seen = set()
    while True:
        response = read_api(f"/mods/{project}/files?index={index}&pageSize=50", key)
        files = response["data"]
        page = response["pagination"]
        if page["index"] != index or page["resultCount"] != len(files):
            raise ValueError("Unexpected pagination response")
        for file in files:
            if file["id"] in seen or file["modId"] != project:
                raise ValueError("Duplicate file or unexpected project in API response")
            seen.add(file["id"])
            yield file
        index += len(files)
        if index >= page["totalCount"]:
            return
        if not files:
            raise ValueError("Pagination stopped before all files were read")


def correction(file, prefix):
    # Additional files are not independently taggable. Ignore non-runtime artifacts.
    if file.get("parentProjectFileId") or file.get("isServerPack"):
        return None
    match = re.fullmatch(re.escape(prefix) + r"-mc(\d+(?:\.\d+)+)(-neoforge)?-\d+\.\d+\.\d+(?:[-+][\w.-]+)?\.jar", file["fileName"])
    if not match:
        return None
    old = file["gameVersions"]
    if not isinstance(old, list) or not all(isinstance(tag, str) and tag for tag in old):
        raise ValueError(f"Unexpected version list for file {file['id']}")
    expected_loader = "neoforge" if match.group(2) else "fabric"
    normalized = {tag.casefold() for tag in old}
    # Abort rather than guessing or dropping compatibility tags if the read API
    # omits the tags we need to preserve.
    if match.group(1) not in old or expected_loader not in normalized:
        raise ValueError(f"Missing Minecraft/loader tag for file {file['id']}: {old!r}")
    if not normalized.intersection({"client", "server"}):
        raise ValueError(f"Read API did not expose environment tags for file {file['id']}: {old!r}")
    if "client" not in normalized and "server" in normalized:
        return None
    return [tag for tag in old if tag.casefold() not in {"client", "server"}] + ["Server"]


def main():
    project, prefix = PROJECTS[os.environ["GITHUB_REPOSITORY"]]
    configured = os.environ.get("CURSEFORGE_PROJECT_ID", "")
    if configured and configured != str(project):
        raise ValueError("Configured project ID differs from the verified project ID")
    key = os.environ.get("CURSEFORGE_API_KEY", "").strip()
    if not key:
        raise SystemExit("Set CURSEFORGE_API_KEY to a CurseForge Core API read key (not the author upload token).")
    apply = os.environ.get("APPLY", "false") == "true"
    token = os.environ.get("CURSEFORGE_TOKEN", "").strip()
    if apply and not token:
        raise SystemExit("Set the existing CURSEFORGE_TOKEN author secret.")
    raw_ids = os.environ.get("FILE_IDS", "").strip()
    selected = {int(part.strip()) for part in raw_ids.split(",")} if raw_ids else set()
    files = list(all_files(project, key))
    if not files:
        raise ValueError("No visible project files returned")
    if selected - {file["id"] for file in files}:
        raise ValueError("Some selected file IDs were not found in the project")
    plan = []
    for file in files:
        if selected and file["id"] not in selected:
            continue
        new = correction(file, prefix)
        if new is None:
            print(f"SKIP {file['id']} {file['fileName']} (already correct or not a recognized runtime jar)")
            continue
        print(f"{file['id']} {file['fileName']}: {file['gameVersions']} -> {new}")
        plan.append({"fileID": file["id"], "gameVersionNames": new})
    print(f"{len(plan)} files to update out of {len(files)} visible project files")
    if not apply:
        print("Dry run: no changes submitted.")
        return
    for metadata in plan:
        # Only send the file ID and complete corrected version-tag list.
        # Keep changelogs, relations, release type, and file contents unchanged.
        result = subprocess.run(
            ["curl", "--fail-with-body", "--silent", "--show-error", "--max-time", "60",
             "-H", f"X-Api-Token: {token}", "--form-string",
             "metadata=" + json.dumps(metadata), f"{AUTHOR_API}/projects/{project}/update-file"],
            capture_output=True, text=True,
        )
        if result.returncode:
            raise SystemExit(f"Update failed for file {metadata['fileID']} (curl exit {result.returncode}). Rerun after resolving the failure.")
        if json.loads(result.stdout).get("id") != metadata["fileID"]:
            raise ValueError("Unexpected update response")
        print(f"CurseForge accepted update for {metadata['fileID']}")
    print("Updates accepted; rerun in dry-run mode after CurseForge refreshes to verify.")


if __name__ == "__main__":
    main()
