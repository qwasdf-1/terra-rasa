#!/usr/bin/env python3
"""Check that local-only documentation stays out of maintained docs."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

TEXT_DOC_SUFFIXES = {".adoc", ".asciidoc", ".csv", ".md", ".mdx", ".rst", ".txt"}
REQUIRED_IGNORE_RULES = {
    "/AGENTS.md",
    "/CLAUDE.md",
    "/docs/issue-ledger.md",
    "/docs/open-work.md",
    "/docs/TODOS.md",
    "/docs/work-register.md",
    "/docs/archive/",
    "/docs/local/",
    "/docs/design/",
}


def run_git(*args: str) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        ["git", *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def git_bytes(*args: str) -> bytes:
    result = run_git(*args)
    if result.returncode:
        sys.stderr.buffer.write(result.stderr)
        raise SystemExit(result.returncode)
    return result.stdout


def ignore_text(root: Path, *, staged: bool) -> str:
    if staged:
        result = run_git("show", ":.gitignore")
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


def document_paths(*, staged: bool) -> list[str]:
    args = ["ls-files", "--cached"]
    if not staged:
        args.extend(["--others", "--exclude-standard"])
    args.extend(["-z"])
    paths = git_bytes(*args).split(b"\0")
    return [
        path.decode("utf-8", errors="replace")
        for path in paths
        if path and Path(path.decode("utf-8", errors="replace")).suffix.casefold() in TEXT_DOC_SUFFIXES
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--staged",
        action="store_true",
        help="check the Git index, as used by the pre-commit hook",
    )
    args = parser.parse_args()

    root = Path(git_bytes("rev-parse", "--show-toplevel").decode().strip())
    ignores = ignore_text(root, staged=args.staged)
    ignore_rules = {
        line.strip()
        for line in ignores.splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }
    failures: list[str] = []
    for rule in sorted(REQUIRED_IGNORE_RULES - ignore_rules):
        failures.append(f"required local-document ignore rule is missing: {rule}")

    ignored_tracked = [
        path.decode("utf-8", errors="replace")
        for path in git_bytes("ls-files", "-ci", "--exclude-standard", "-z").split(b"\0")
        if path
    ]
    for path in ignored_tracked:
        failures.append(f"tracked file matches .gitignore: {path}")

    tokens = local_doc_tokens(ignores)
    for path in document_paths(staged=args.staged):
        if args.staged:
            content = git_bytes("show", f":{path}").decode("utf-8", errors="replace")
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
