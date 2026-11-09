#!/usr/bin/env python3
"""Check that local-only documentation stays out of maintained docs."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tomllib
from pathlib import Path
from typing import Sequence

TEXT_DOC_SUFFIXES = {".adoc", ".asciidoc", ".csv", ".md", ".mdx", ".rst", ".txt"}

DEFAULT_REQUIRED_RULES = {
    "/AGENTS.md",
    "/CLAUDE.md",
    "/docs/issue-ledger.md",
    "/docs/open-work.md",
    "/docs/TODOS.md",
    "/docs/work-register.md",
}

# Known repo defaults for seamless backward-compatibility if no config file is present
KNOWN_REPO_EXTRA_RULES: dict[str, set[str]] = {
    "terra-rasa": {"/docs/archive/", "/docs/local/", "/docs/design/"},
    "soullink": {"/docs/archive/", "/docs/local/", "/docs/v1/"},
}


def run_git(*args: str, cwd: Path | None = None) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        ["git", *args],
        cwd=cwd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def git_bytes(*args: str, cwd: Path | None = None) -> bytes:
    result = run_git(*args, cwd=cwd)
    if result.returncode:
        sys.stderr.buffer.write(result.stderr)
        raise SystemExit(result.returncode)
    return result.stdout


def ignore_text(root: Path, *, staged: bool) -> str:
    if staged:
        result = run_git("show", ":.gitignore", cwd=root)
        if result.returncode == 0:
            return result.stdout.decode("utf-8", errors="replace")
        return ""
    ignore_file = root / ".gitignore"
    return ignore_file.read_text(encoding="utf-8", errors="replace") if ignore_file.exists() else ""


def local_doc_tokens(ignore_text_value: str) -> list[str]:
    tokens: set[str] = {"issue ledger", "open work"}
    for raw_rule in ignore_text_value.splitlines():
        rule = raw_rule.strip()
        if not rule or rule.startswith(("#", "!")):
            continue
        normalized = rule.lstrip("/")
        if normalized in {"AGENTS.md", "CLAUDE.md"}:
            tokens.add(normalized)
        elif normalized.startswith("docs/") and not any(char in normalized for char in "*?[]"):
            tokens.add(normalized)
            if normalized.endswith("/"):
                tokens.add(normalized.rstrip("/"))
            elif normalized.endswith(".md"):
                tokens.add(Path(normalized).name)
    return sorted(tokens, key=len, reverse=True)


def document_paths(root: Path, *, staged: bool) -> list[str]:
    args = ["ls-files", "--cached"]
    if not staged:
        args.extend(["--others", "--exclude-standard"])
    args.extend(["-z"])
    paths = git_bytes(*args, cwd=root).split(b"\0")
    return [
        path.decode("utf-8", errors="replace")
        for path in paths
        if path and Path(path.decode("utf-8", errors="replace")).suffix.casefold() in TEXT_DOC_SUFFIXES
    ]


def load_required_rules(root: Path, extra_cli_rules: Sequence[str] | None = None) -> set[str]:
    rules = set(DEFAULT_REQUIRED_RULES)

    # 1. Check pyproject.toml
    pyproject = root / "pyproject.toml"
    if pyproject.is_file():
        try:
            data = tomllib.loads(pyproject.read_text(encoding="utf-8"))
            hygiene_cfg = data.get("tool", {}).get("mc-hygiene", {}).get("doc-boundary", {})
            if "required_rules" in hygiene_cfg:
                rules = set(hygiene_cfg["required_rules"])
            if "extra_rules" in hygiene_cfg:
                rules.update(hygiene_cfg["extra_rules"])
        except Exception as e:
            print(f"Warning: Failed to parse {pyproject}: {e}", file=sys.stderr)

    # 2. Check .doc-boundary.json
    boundary_json = root / ".doc-boundary.json"
    if boundary_json.is_file():
        try:
            data = json.loads(boundary_json.read_text(encoding="utf-8"))
            if "required_rules" in data:
                rules = set(data["required_rules"])
            if "extra_rules" in data:
                rules.update(data["extra_rules"])
        except Exception as e:
            print(f"Warning: Failed to parse {boundary_json}: {e}", file=sys.stderr)

    # 3. Fallback to known repository heuristics if neither config was present
    if not pyproject.is_file() and not boundary_json.is_file():
        repo_name = root.name
        if repo_name in KNOWN_REPO_EXTRA_RULES:
            rules.update(KNOWN_REPO_EXTRA_RULES[repo_name])

    # 4. Apply CLI extra rules
    if extra_cli_rules:
        rules.update(extra_cli_rules)

    return rules


def check_boundary(root: Path, staged: bool = False, extra_rules: Sequence[str] | None = None) -> list[str]:
    required_rules = load_required_rules(root, extra_rules)
    ignores = ignore_text(root, staged=staged)
    ignore_rules = {
        line.strip()
        for line in ignores.splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }

    failures: list[str] = []
    for rule in sorted(required_rules - ignore_rules):
        failures.append(f"required local-document ignore rule is missing: {rule}")

    ignored_tracked = [
        path.decode("utf-8", errors="replace")
        for path in git_bytes("ls-files", "-ci", "--exclude-standard", "-z", cwd=root).split(b"\0")
        if path
    ]
    for path in ignored_tracked:
        failures.append(f"tracked file matches .gitignore: {path}")

    tokens = local_doc_tokens(ignores)
    for path in document_paths(root, staged=staged):
        if staged:
            content = git_bytes("show", f":{path}", cwd=root).decode("utf-8", errors="replace")
        else:
            doc_path = root / path
            if not doc_path.is_file():
                continue
            content = doc_path.read_text(encoding="utf-8", errors="replace")
        for line_number, line in enumerate(content.splitlines(), 1):
            lowered = line.casefold()
            for token in tokens:
                if token.casefold() in lowered:
                    failures.append(
                        f"{path}:{line_number}: reference to local-only documentation ({token})"
                    )

    return failures


def resolve_repo_root(hint: Path | None = None) -> Path:
    if hint:
        return hint.resolve()
    script_path = Path(__file__).resolve()
    if script_path.parent.name == "scripts" and (script_path.parent.parent / ".git").exists():
        return script_path.parent.parent
    return Path(git_bytes("rev-parse", "--show-toplevel").decode().strip())


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--staged",
        action="store_true",
        help="check the Git index, as used by the pre-commit hook",
    )
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=None,
        help="path to the repository root (defaults to auto-detection or git rev-parse)",
    )
    parser.add_argument(
        "--extra-rule",
        action="append",
        dest="extra_rules",
        help="extra required ignore rule (e.g. /docs/archive/)",
    )
    args = parser.parse_args(argv)

    root = resolve_repo_root(args.repo_root)
    failures = check_boundary(root, staged=args.staged, extra_rules=args.extra_rules)

    if failures:
        print("Local documentation boundary check failed:", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        print(
            "Keep local documentation ignored and remove references to it from maintained docs.",
            file=sys.stderr,
        )
        return 1

    print("Local documentation boundary check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
