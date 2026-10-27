#!/usr/bin/env sh
set -eu
repo_root="$(git rev-parse --show-toplevel)"
git -C "$repo_root" config --local core.hooksPath .githooks
printf 'Installed local Git hooks for %s\n' "$repo_root"
