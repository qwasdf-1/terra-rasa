#!/usr/bin/env python3
"""Resolve a CurseForge project ID and map target releases to game version IDs."""

from __future__ import annotations

import json
import os
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

CURSEFORGE_API_BASE = "https://minecraft.curseforge.com/api"


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(1)


def parse_targets(raw_targets: str | None) -> list[dict]:
    if raw_targets:
        try:
            parsed = json.loads(raw_targets)
            if isinstance(parsed, dict) and "include" in parsed:
                return parsed["include"]
            if isinstance(parsed, list):
                return parsed
        except json.JSONDecodeError as exc:
            fail(f"Invalid TARGETS_JSON: {exc}")

    for candidate in (
        os.path.join("gradle", "release-targets.json"),
        os.path.join("gradle", "minecraft-targets.json"),
    ):
        if os.path.isfile(candidate):
            try:
                with open(candidate, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    return data.get("include", [])
            except Exception as exc:
                fail(f"Could not read {candidate}: {exc}")

    fail("No targets provided via TARGETS_JSON or gradle/release-targets.json")


def fetch_api(path: str, token: str, user_agent: str) -> list | dict:
    url = f"{CURSEFORGE_API_BASE}/{path.lstrip('/')}"
    req = Request(
        url,
        headers={
            "User-Agent": user_agent,
            "Accept": "application/json",
            "X-Api-Token": token,
        },
    )
    try:
        with urlopen(req, timeout=30) as resp:
            return json.load(resp)
    except HTTPError as exc:
        body = ""
        try:
            body = exc.read().decode("utf-8", errors="replace")
        except Exception:
            pass
        if exc.code in (401, 403):
            fail(f"CurseForge token is invalid or unauthorized at {url} (HTTP {exc.code}): {body or exc.reason}")
        if exc.code == 404:
            fail(f"CurseForge resource not found at {url} (HTTP 404): {body or exc.reason}")
        fail(f"CurseForge API request failed for {url} (HTTP {exc.code}): {body or exc.reason}")
    except (URLError, TimeoutError) as exc:
        fail(f"Could not connect to CurseForge API at {url}: {exc}")


KNOWN_ENV_IDS: dict[str, int] = {
    "client": 9638,
    "server": 9639,
}


def server_only_game_versions(ids: list[int]) -> list[int]:
    """Keep all non-environment tags and enforce the server-only distribution."""
    return sorted((set(ids) - {KNOWN_ENV_IDS["client"]}) | {KNOWN_ENV_IDS["server"]})


def resolve_game_version_ids(
    versions: list[dict],
    version_types: list[dict],
    minecraft_version: str | list[str],
    java_version: str | None = None,
) -> list[int]:
    type_name_by_id = {vt.get("id"): vt.get("name", "") for vt in version_types}
    type_slug_by_id = {vt.get("id"): vt.get("slug", "") for vt in version_types}

    # 1. Resolve Fabric mod loader ID
    fabric_id: int | None = None
    for v in versions:
        name = (v.get("name") or "").strip().lower()
        slug = (v.get("slug") or "").strip().lower()
        if name == "fabric" or slug == "fabric":
            fabric_id = v.get("id")
            break

    if fabric_id is None:
        fail("Could not find CurseForge game version ID for loader 'Fabric'")

    # 2. Resolve Minecraft game version ID(s)
    mc_versions = [minecraft_version] if isinstance(minecraft_version, str) else list(minecraft_version)
    if not mc_versions:
        fail("No Minecraft versions provided to resolve")

    result: list[int] = [fabric_id]
    for mc_raw in mc_versions:
        mc_id: int | None = None
        mc_norm = mc_raw.strip()
        mc_slug = mc_norm.replace(".", "-")
        mc_prefix = f"minecraft {mc_norm}".lower()

        # Pass 1: search inside Minecraft version categories
        for v in versions:
            v_name = (v.get("name") or "").strip()
            v_slug = (v.get("slug") or "").strip()
            vt_name = type_name_by_id.get(v.get("gameVersionTypeID"), "").lower()
            vt_slug = type_slug_by_id.get(v.get("gameVersionTypeID"), "").lower()
            if ("minecraft" in vt_name or "minecraft" in vt_slug) and (
                v_name == mc_norm or v_slug == mc_slug or v_name.lower() == mc_prefix
            ):
                mc_id = v.get("id")
                break

        # Pass 2: search across any version type
        if mc_id is None:
            for v in versions:
                v_name = (v.get("name") or "").strip()
                v_slug = (v.get("slug") or "").strip()
                if v_name == mc_norm or v_slug == mc_slug or v_name.lower() == mc_prefix:
                    mc_id = v.get("id")
                    break

        if mc_id is None:
            fail(f"Could not find CurseForge game version ID for Minecraft '{mc_raw}'")
        if mc_id not in result:
            result.append(mc_id)

    # 3. Optional Java version
    if java_version:
        j_norm = str(java_version).strip().lower()
        j_name = f"java {j_norm}"
        j_slug = f"java-{j_norm}"
        for v in versions:
            v_name = (v.get("name") or "").strip().lower()
            v_slug = (v.get("slug") or "").strip().lower()
            if v_name == j_name or v_slug == j_slug:
                java_id = v.get("id")
                if java_id is not None and java_id not in result:
                    result.append(java_id)
                break

    # 4. Enforce Server-only environment ID
    env_type_ids = {
        vt.get("id")
        for vt in version_types
        if "environment" in (vt.get("name") or "").lower() or "environment" in (vt.get("slug") or "").lower()
    }
    server_id: int | None = None
    if env_type_ids:
        for v in versions:
            if v.get("gameVersionTypeID") in env_type_ids:
                v_name = (v.get("name") or "").strip().lower()
                v_slug = (v.get("slug") or "").strip().lower()
                if v_name == "server" or v_slug == "server":
                    server_id = v.get("id")
                    break

    if server_id is None:
        for v in versions:
            v_name = (v.get("name") or "").strip().lower()
            v_slug = (v.get("slug") or "").strip().lower()
            if v_name == "server" or v_slug == "server":
                server_id = v.get("id")
                break

    if server_id is None:
        server_id = KNOWN_ENV_IDS.get("server")

    if server_id is None:
        fail("Could not find CurseForge game version ID for environment 'server'")

    if server_id not in result:
        result.append(server_id)

    return sorted(set(result) - {KNOWN_ENV_IDS["client"]})


def main() -> None:
    project_id_raw = os.environ.get("CURSEFORGE_PROJECT_ID", "").strip()
    curseforge_token = os.environ.get("CURSEFORGE_TOKEN", "").strip()
    user_agent = os.environ.get("CURSEFORGE_USER_AGENT", "curseforge-publisher").strip()
    raw_targets = os.environ.get("TARGETS_JSON", "").strip()
    raw_overrides = os.environ.get("CURSEFORGE_GAME_VERSIONS", "").strip()

    if not project_id_raw:
        fail("Set the repository Actions variable CURSEFORGE_PROJECT_ID")
    if not project_id_raw.isdigit():
        fail(f"CURSEFORGE_PROJECT_ID must be a numeric ID; got {project_id_raw!r}")

    project_id = int(project_id_raw)

    if not curseforge_token:
        fail("Set the CURSEFORGE_TOKEN Actions secret")

    targets = parse_targets(raw_targets)
    if not targets:
        fail("No Minecraft targets found to resolve")

    overrides: dict[str, list[int]] = {}
    if raw_overrides:
        try:
            parsed_overrides = json.loads(raw_overrides)
            if isinstance(parsed_overrides, dict):
                for k, v in parsed_overrides.items():
                    if isinstance(v, list):
                        overrides[str(k)] = [int(x) for x in v]
                    elif isinstance(v, (int, str)) and str(v).isdigit():
                        overrides[str(k)] = [int(v)]
        except Exception as exc:
            fail(f"Invalid CURSEFORGE_GAME_VERSIONS override: {exc}")

    def target_key(target: dict) -> str | None:
        return target.get("target_label") or target.get("minecraft_version")

    def target_mc_versions(target: dict) -> list[str]:
        if isinstance(target.get("minecraft_versions"), list) and target["minecraft_versions"]:
            return [str(v) for v in target["minecraft_versions"]]
        if target.get("minecraft_version"):
            return [str(target["minecraft_version"])]
        return []

    all_overridden = all(target_key(t) in overrides for t in targets)

    game_versions_map: dict[str, list[int]] = {}
    if all_overridden:
        for t in targets:
            key = target_key(t)
            if key:
                game_versions_map[key] = server_only_game_versions(overrides[key])
    else:
        versions_raw = fetch_api("game/versions", token=curseforge_token, user_agent=user_agent)
        if not isinstance(versions_raw, list):
            fail("Unexpected response format from CurseForge game versions API")

        types_raw: list[dict] = []
        try:
            res = fetch_api("game/version-types", token=curseforge_token, user_agent=user_agent)
            if isinstance(res, list):
                types_raw = res
        except Exception:
            types_raw = []

        for t in targets:
            key = target_key(t)
            mc_list = target_mc_versions(t)
            if not key or not mc_list:
                continue
            if key in overrides:
                game_versions_map[key] = server_only_game_versions(overrides[key])
            else:
                java_ver = t.get("java_version")
                game_versions_map[key] = resolve_game_version_ids(
                    versions_raw,
                    types_raw,
                    minecraft_version=mc_list,
                    java_version=java_ver,
                )

    print(json.dumps({"project_id": str(project_id), "game_versions": game_versions_map}))


if __name__ == "__main__":
    main()
