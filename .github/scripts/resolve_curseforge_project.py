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

    targets_file = os.path.join("gradle", "minecraft-targets.json")
    if os.path.isfile(targets_file):
        try:
            with open(targets_file, "r", encoding="utf-8") as f:
                data = json.load(f)
                return data.get("include", [])
        except Exception as exc:
            fail(f"Could not read {targets_file}: {exc}")

    fail("No targets provided via TARGETS_JSON or gradle/minecraft-targets.json")


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


def parse_environments(raw: str | None) -> list[str]:
    if not raw:
        return ["client", "server"]
    tokens = [t.strip().lower() for t in raw.replace(",", " ").replace("|", " ").split() if t.strip()]
    envs: list[str] = []
    for t in tokens:
        if t in ("both", "client_and_server", "*"):
            for e in ("client", "server"):
                if e not in envs:
                    envs.append(e)
        elif t in ("client", "server"):
            if t not in envs:
                envs.append(t)
    return envs or ["client", "server"]


def resolve_game_version_ids(
    versions: list[dict],
    version_types: list[dict],
    minecraft_version: str,
    java_version: str | None = None,
    environments: list[str] | None = None,
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

    # 2. Resolve Minecraft game version ID
    mc_id: int | None = None
    mc_norm = minecraft_version.strip()
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
        fail(f"Could not find CurseForge game version ID for Minecraft '{minecraft_version}'")

    result: list[int] = [mc_id, fabric_id]

    # 3. Optional Java version
    if java_version:
        j_norm = str(java_version).strip().lower()
        java_name = f"java {j_norm}"
        java_slug = f"java-{j_norm}"
        for v in versions:
            v_name = (v.get("name") or "").strip().lower()
            v_slug = (v.get("slug") or "").strip().lower()
            if v_name == java_name or v_slug == java_slug:
                java_id = v.get("id")
                if java_id is not None and java_id not in result:
                    result.append(java_id)
                break

    # 4. Resolve Environment IDs (Client and/or Server)
    target_envs = environments if environments is not None else ["client", "server"]
    env_type_ids = {
        vt.get("id")
        for vt in version_types
        if "environment" in (vt.get("name") or "").lower() or "environment" in (vt.get("slug") or "").lower()
    }

    for env in target_envs:
        env_norm = env.strip().lower()
        env_id: int | None = None

        if env_type_ids:
            for v in versions:
                if v.get("gameVersionTypeID") in env_type_ids:
                    v_name = (v.get("name") or "").strip().lower()
                    v_slug = (v.get("slug") or "").strip().lower()
                    if v_name == env_norm or v_slug == env_norm:
                        env_id = v.get("id")
                        break

        if env_id is None:
            for v in versions:
                v_name = (v.get("name") or "").strip().lower()
                v_slug = (v.get("slug") or "").strip().lower()
                if v_name == env_norm or v_slug == env_norm:
                    env_id = v.get("id")
                    break

        if env_id is None:
            env_id = KNOWN_ENV_IDS.get(env_norm)

        if env_id is not None:
            if env_id not in result:
                result.append(env_id)
        else:
            fail(f"Could not find CurseForge game version ID for environment '{env}'")

    return sorted(set(result))


def main() -> None:
    project_id_raw = os.environ.get("CURSEFORGE_PROJECT_ID", "").strip()
    curseforge_token = os.environ.get("CURSEFORGE_TOKEN", "").strip()
    user_agent = os.environ.get("CURSEFORGE_USER_AGENT", "curseforge-publisher").strip()
    raw_targets = os.environ.get("TARGETS_JSON", "").strip()
    raw_overrides = os.environ.get("CURSEFORGE_GAME_VERSIONS", "").strip()
    raw_env = os.environ.get("CURSEFORGE_ENVIRONMENT", "").strip()
    target_envs = parse_environments(raw_env)

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

    # Check if all targets are covered by manual overrides
    all_overridden = all(t.get("minecraft_version") in overrides for t in targets)

    game_versions_map: dict[str, list[int]] = {}
    if all_overridden:
        for t in targets:
            mc = t.get("minecraft_version")
            entry = list(overrides[mc])
            for env in target_envs:
                eid = KNOWN_ENV_IDS.get(env)
                if eid and eid not in entry:
                    entry.append(eid)
            game_versions_map[mc] = sorted(set(entry))
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
            mc = t.get("minecraft_version")
            if not mc:
                continue
            if mc in overrides:
                entry = list(overrides[mc])
                for env in target_envs:
                    eid = KNOWN_ENV_IDS.get(env)
                    if eid and eid not in entry:
                        entry.append(eid)
                game_versions_map[mc] = sorted(set(entry))
            else:
                java_ver = t.get("java_version")
                game_versions_map[mc] = resolve_game_version_ids(
                    versions_raw,
                    types_raw,
                    minecraft_version=mc,
                    java_version=java_ver,
                    environments=target_envs,
                )

    print(json.dumps({"project_id": str(project_id), "game_versions": game_versions_map}))


if __name__ == "__main__":
    main()
