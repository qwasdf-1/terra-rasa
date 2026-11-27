#!/usr/bin/env python3
"""Plan an in-place CurseForge repair from public file IDs and publisher logs.

No Core API key. Historical publisher logs are preferred because their numeric
version tags preserve even manually supplied overrides. The release manifest
is a fallback, not a read of current CurseForge metadata.
"""
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
PROJECTS = {
    "qwasdf-1/soul-link": (1774398, "soul-link-shared-health", "soullink"),
    "qwasdf-1/terra-rasa": (1774360, "terra-rasa", "terra-rasa"),
    "qwasdf-1/LMFT-recrafted": (1827006, "lmft-recrafted", "lmft"),
}
API = "https://minecraft.curseforge.com/api"
CLIENT, SERVER = 9638, 9639


def gh(*args):
    return subprocess.check_output(["gh", *args], text=True, timeout=120)


def author_get(path, token):
    request = Request(API + path, headers={"X-Api-Token": token,
                      "Accept": "application/json", "User-Agent": "recrafted-environment-repair"})
    with urlopen(request, timeout=30) as response:
        return json.load(response)


def env_value(log, key):
    # gh run view --log prefixes each line with job/step/timestamp.
    # Only inspect specific env keys; do not print raw logs or credentials.
    values = set(re.findall(r"(?:^|\n)[^\n]*?\s+" + re.escape(key) + r": ([^\r\n]+)", log))
    return next(iter(values)) if len(values) == 1 else None


def logged_rows(repo, project, prefix):
    runs = json.loads(gh("run", "list", "--repo", repo, "--workflow", "curseforge.yml",
                         "--limit", "100", "--json", "databaseId,status"))
    if len(runs) == 100:
        print("WARNING: run discovery is limited to the newest 100 runs.")
    rows = {}
    for run in runs:
        if run["status"] != "completed":
            continue
        try:
            pages = json.loads(gh("api", "--paginate", "--slurp",
                f"repos/{repo}/actions/runs/{run['databaseId']}/jobs?filter=all&per_page=100"))
        except subprocess.SubprocessError:
            print(f"WARNING: could not list jobs for run {run['databaseId']}")
            continue
        for job in (job for page in pages for job in page["jobs"]):
            if job["conclusion"] != "success" or "publish" not in job["name"].lower() and "curseforge:" not in job["name"].lower():
                continue
            try:
                log = gh("run", "view", "--repo", repo, "--job", str(job["id"]), "--log")
            except subprocess.SubprocessError:
                print(f"WARNING: logs unavailable for job {job['id']}")
                continue
            ids = set(int(x) for x in re.findall(r"Published CurseForge file (?:ID )?(\d+)(?: for Minecraft [^\r\n]+)?\s*(?:\r?\n|$)", log))
            if len(ids) != 1:
                continue  # duplicate uploads or unsupported historical log format
            file_id = ids.pop()
            project_id = env_value(log, "PROJECT_ID") or env_value(log, "CURSEFORGE_PROJECT_ID")
            if project_id != str(project):
                print(f"WARNING: cannot verify project for logged file {file_id}")
                continue
            loader = env_value(log, "LOADER")
            tag = env_value(log, "RELEASE_TAG")
            if loader not in ("fabric", "neoforge") or not tag or not re.fullmatch(r"v\d+\.\d+\.\d+(?:-[\w.-]+)?", tag):
                print(f"WARNING: incomplete release identity for logged file {file_id}")
                continue
            version = tag[1:]
            if prefix == "lmft":
                jar = f"lmft-{loader}-26.x-{version}-26.x.jar"
            else:
                mc = env_value(log, "MINECRAFT")
                if not mc or not re.fullmatch(r"\d+(?:\.\d+)+", mc):
                    continue
                jar = f"{prefix}-mc{mc}{'-neoforge' if loader == 'neoforge' else ''}-{version}.jar"
                logged_jar = env_value(log, "JAR_NAME")
                if logged_jar and logged_jar != jar:
                    raise ValueError(f"Unexpected logged artifact for {file_id}")
            row = {"id": file_id, "jar": jar, "source": f"publisher job {job['id']}"}
            raw = env_value(log, "GAME_VERSIONS")
            if prefix != "lmft" and raw:
                old = json.loads(raw)[mc]
                if not isinstance(old, list) or not all(type(x) is int and x > 0 for x in old):
                    raise ValueError(f"Invalid historical numeric tags for {file_id}")
                if loader == "neoforge":
                    fabric = int(env_value(log, "FABRIC_LOADER_ID") or 0)
                    neo = int(env_value(log, "NEOFORGE_LOADER_ID") or 0)
                    if not fabric or not neo:
                        raise ValueError(f"Missing historical loader IDs for {file_id}")
                    old = list(set(old) - {fabric} | {neo})
                row["original_ids"] = sorted(set(old))
            if file_id in rows and rows[file_id]["jar"] != jar:
                raise ValueError(f"Conflicting publisher logs for {file_id}")
            if file_id not in rows or "original_ids" in row:
                rows[file_id] = row
    return rows


def targets_for(repo, prefix, jar):
    if prefix == "lmft":
        match = re.fullmatch(r"lmft-(fabric|neoforge)-26\.x-(\d+\.\d+\.\d+(?:-[\w.-]+)?)[+-]26\.x\.jar", jar)
        if not match:
            raise ValueError(f"Not a recognized runtime JAR: {jar}")
        loader, version = match.groups()
        path = "release/targets.json"
    else:
        match = re.fullmatch(re.escape(prefix) + r"-mc(\d+(?:\.\d+)+)(-neoforge)?-(\d+\.\d+\.\d+(?:-[\w.-]+)?)\.jar", jar)
        if not match:
            raise ValueError(f"Not a recognized runtime JAR: {jar}")
        mc, suffix, version = match.groups()
        loader = "neoforge" if suffix else "fabric"
        path = "gradle/minecraft-targets.json"
    # Immutable tagged source, not the newest release's target matrix.
    target = json.loads(gh("api", f"repos/{repo}/contents/{path}?ref=v{version}",
                          "-H", "Accept: application/vnd.github.raw+json"))
    if prefix == "lmft":
        if loader not in target["loaders"]:
            raise ValueError("Loader is absent from historical release targets")
        games, java = target["minecraft_versions"], target["java_version"]
    else:
        matches = [x for x in target["include"] if x["minecraft_version"] == mc
                   and x.get("loader", "fabric") == loader]
        if len(matches) != 1:
            raise ValueError(f"Artifact absent from tagged targets: {jar}")
        games, java = [mc], matches[0]["java_version"]
    if not games or not all(re.fullmatch(r"\d+(?:\.\d+)+", x) for x in games):
        raise ValueError("Invalid historical Minecraft targets")
    if str(java) not in ("21", "25"):
        raise ValueError("Unexpected historical Java version")
    return games, loader, str(java)


def numeric_tags(games, loader, java, versions, types):
    # Minecraft names may be duplicated across Bukkit/other categories.
    categories = {x["id"]: x.get("name", "") for x in types}
    def exact(name):
        ids = {x["id"] for x in versions if x.get("name", "").casefold() == name.casefold()}
        if len(ids) != 1:
            raise ValueError(f"Ambiguous or missing CurseForge tag: {name}")
        return ids.pop()
    result = [exact(loader), exact("Java " + java), SERVER]
    if exact("Server") != SERVER or exact("Client") != CLIENT:
        raise ValueError("Unexpected environment tag IDs")
    for game in games:
        choices = []
        for x in versions:
            if x.get("name") != game:
                continue
            category = categories.get(x.get("gameVersionTypeID"), "").strip().casefold()
            match = re.fullmatch(r"(?:minecraft[ -])?(\d+(?:\.\d+)*)(?:\.x)?", category)
            if match and game.split(".")[:len(match[1].split("."))] == match[1].split("."):
                choices.append((len(match[1].split(".")), x["id"]))
            elif category == "minecraft":
                choices.append((0, x["id"]))
        if not choices:
            raise ValueError(f"No Minecraft release category for {game}")
        top = max(score for score, _ in choices)
        ids = {id_ for score, id_ in choices if score == top}
        if len(ids) != 1:
            raise ValueError(f"Ambiguous Minecraft release category for {game}")
        result.append(ids.pop())
    return sorted(set(result))


def corrected(ids):
    if not ids or not all(type(x) is int and x > 0 for x in ids):
        raise ValueError("Invalid version IDs")
    if not {CLIENT, SERVER}.intersection(ids):
        raise ValueError("Historical tags contain no recognized environment")
    return sorted(set(ids) - {CLIENT} | {SERVER})


def main():
    repo = os.environ["GITHUB_REPOSITORY"]
    project, slug, prefix = PROJECTS[repo]
    configured = os.environ.get("CURSEFORGE_PROJECT_ID", "")
    if configured and configured != str(project):
        raise ValueError("Configured project ID differs from verified project")
    token = os.environ.get("CURSEFORGE_TOKEN", "").strip()
    apply = os.environ.get("APPLY") == "true"
    allow_reconstructed = os.environ.get("ALLOW_RECONSTRUCTED") == "true"
    raw = os.environ.get("FILE_IDS", "").strip()
    selected = {int(x.strip()) for x in raw.split(",")} if raw else set()
    manifest = json.loads((ROOT / ".github/scripts/curseforge-repair-files.json").read_text())
    if manifest["project_id"] != project:
        raise ValueError("Manifest project mismatch")
    rows = {}
    for row in manifest["files"]:
        if type(row["id"]) is not int or row["id"] <= 0 or row["id"] in rows:
            raise ValueError("Invalid or duplicate manifest file ID")
        rows[row["id"]] = dict(row, source="public file listing")
    for file_id, row in logged_rows(repo, project, prefix).items():
        if file_id in rows and rows[file_id]["jar"].replace("+26.x.jar", "-26.x.jar") != row["jar"].replace("+26.x.jar", "-26.x.jar"):
            raise ValueError(f"Manifest/log artifact mismatch for {file_id}")
        rows[file_id] = row
    if selected - rows.keys():
        raise ValueError(f"Unknown file IDs: {sorted(selected - rows.keys())}. Add verified rows to the manifest.")
    if not rows:
        raise ValueError("No verified files")
    print(f"Discovered {len(rows)} unique file IDs (not a complete live inventory).")
    catalog = None
    plan = []
    for file_id, row in sorted(rows.items()):
        if selected and file_id not in selected:
            continue
        print(f"{file_id} {row['jar']} ({row['source']}) https://www.curseforge.com/minecraft/mc-mods/{slug}/files/{file_id}")
        if "original_ids" in row:
            old = row["original_ids"]
            new = corrected(old)
            print(f"  exact historical upload IDs: {old} -> {new}")
        else:
            games, loader, java = targets_for(repo, prefix, row["jar"])
            if not token:
                raise ValueError("Existing CURSEFORGE_TOKEN needed to resolve reconstructed tags")
            if catalog is None:
                catalog = (author_get("/game/versions", token), author_get("/game/version-types", token))
            new = numeric_tags(games, loader, java, *catalog)
            print(f"  RECONSTRUCTED: {games}, {loader}, Java {java}, Server -> {new}")
            if apply and not allow_reconstructed:
                raise ValueError("Reconstructed tags require explicit allow_reconstructed approval after preview.")
        plan.append({"fileID": file_id, "gameVersions": new})
    print(f"{len(plan)} planned updates; no live metadata read was performed.")
    if not apply:
        print("Dry run: no file updates submitted.")
        return
    if not token:
        raise ValueError("Missing existing CURSEFORGE_TOKEN")
    for metadata in plan:
        result = subprocess.run(["curl", "--fail-with-body", "--silent", "--show-error",
            "--max-time", "60", "-H", f"X-Api-Token: {token}", "--form-string",
            "metadata=" + json.dumps(metadata), f"{API}/projects/{project}/update-file"],
            capture_output=True, text=True)
        if result.returncode or json.loads(result.stdout).get("id") != metadata["fileID"]:
            raise SystemExit(f"Update failed for {metadata['fileID']}; earlier updates may have succeeded.")
        print(f"CurseForge accepted update for {metadata['fileID']}")
    print("Verify the resulting tags and any missing files in the CurseForge author dashboard.")


if __name__ == "__main__":
    main()
