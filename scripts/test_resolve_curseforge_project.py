#!/usr/bin/env python3
"""Unit tests for resolve_curseforge_project.py."""

from __future__ import annotations

import importlib.util
import json
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

SCRIPT_PATH = Path(__file__).resolve().parent.parent / ".github" / "scripts" / "resolve_curseforge_project.py"
spec = importlib.util.spec_from_file_location("resolve_curseforge_project", SCRIPT_PATH)
resolver = importlib.util.module_from_spec(spec)
sys.modules["resolve_curseforge_project"] = resolver
spec.loader.exec_module(resolver)


class ResolveCurseForgeProjectTest(unittest.TestCase):
    def setUp(self) -> None:
        self.mock_types = [
            {"id": 1, "name": "Mod Loaders", "slug": "mod-loaders"},
            {"id": 2, "name": "Minecraft 1.21", "slug": "minecraft-1-21"},
            {"id": 3, "name": "Minecraft 26", "slug": "minecraft-26"},
            {"id": 4, "name": "Java", "slug": "java"},
            {"id": 5, "name": "Environment", "slug": "environment"},
        ]
        self.mock_versions = [
            {"id": 100, "gameVersionTypeID": 1, "name": "Fabric", "slug": "fabric"},
            {"id": 101, "gameVersionTypeID": 1, "name": "Forge", "slug": "forge"},
            {"id": 201, "gameVersionTypeID": 2, "name": "1.21.11", "slug": "1-21-11"},
            {"id": 301, "gameVersionTypeID": 3, "name": "26.1", "slug": "26-1"},
            {"id": 302, "gameVersionTypeID": 3, "name": "26.2", "slug": "26-2"},
            {"id": 401, "gameVersionTypeID": 4, "name": "Java 21", "slug": "java-21"},
            {"id": 402, "gameVersionTypeID": 4, "name": "Java 25", "slug": "java-25"},
            {"id": 9638, "gameVersionTypeID": 5, "name": "Client", "slug": "client"},
            {"id": 9639, "gameVersionTypeID": 5, "name": "Server", "slug": "server"},
        ]

    def test_resolve_game_version_ids_success(self) -> None:
        ids_21 = resolver.resolve_game_version_ids(
            self.mock_versions,
            self.mock_types,
            minecraft_version="1.21.11",
            java_version="21",
        )
        self.assertEqual(ids_21, [100, 201, 401, 9638, 9639])

        ids_25 = resolver.resolve_game_version_ids(
            self.mock_versions,
            self.mock_types,
            minecraft_version="26.1",
            java_version="25",
        )
        self.assertEqual(ids_25, [100, 301, 402, 9638, 9639])

    def test_resolve_game_version_ids_without_java(self) -> None:
        ids = resolver.resolve_game_version_ids(
            self.mock_versions,
            self.mock_types,
            minecraft_version="26.2",
            java_version=None,
        )
        self.assertEqual(ids, [100, 302, 9638, 9639])

    def test_resolve_game_version_ids_custom_environment(self) -> None:
        ids_server = resolver.resolve_game_version_ids(
            self.mock_versions,
            self.mock_types,
            minecraft_version="26.2",
            java_version=None,
            environments=["server"],
        )
        self.assertEqual(ids_server, [100, 302, 9639])

    def test_missing_minecraft_version_fails(self) -> None:
        with self.assertRaises(SystemExit):
            resolver.resolve_game_version_ids(
                self.mock_versions,
                self.mock_types,
                minecraft_version="99.99",
            )

    def test_missing_fabric_fails(self) -> None:
        no_fabric = [v for v in self.mock_versions if v["name"] != "Fabric"]
        with self.assertRaises(SystemExit):
            resolver.resolve_game_version_ids(
                no_fabric,
                self.mock_types,
                minecraft_version="1.21.11",
            )

    @patch("resolve_curseforge_project.fetch_api")
    def test_main_with_api_fetch(self, mock_fetch: MagicMock) -> None:
        def fetch_side_effect(path: str, **kwargs):
            if path == "game/versions":
                return self.mock_versions
            if path == "game/version-types":
                return self.mock_types
            raise ValueError(f"Unexpected path {path}")

        mock_fetch.side_effect = fetch_side_effect

        targets = {
            "include": [
                {"minecraft_version": "1.21.11", "java_version": "21"},
                {"minecraft_version": "26.1", "java_version": "25"},
            ]
        }

        with patch.dict(
            os.environ,
            {
                "CURSEFORGE_PROJECT_ID": "12345",
                "CURSEFORGE_TOKEN": "test-token-uuid",
                "TARGETS_JSON": json.dumps(targets),
            },
        ):
            with patch("sys.stdout") as mock_stdout:
                resolver.main()
                output = "".join(call.args[0] for call in mock_stdout.write.mock_calls).strip()
                data = json.loads(output)
                self.assertEqual(data["project_id"], "12345")
                self.assertEqual(data["game_versions"]["1.21.11"], [100, 201, 401, 9638, 9639])
                self.assertEqual(data["game_versions"]["26.1"], [100, 301, 402, 9638, 9639])

    @patch("resolve_curseforge_project.fetch_api")
    def test_main_with_overrides(self, mock_fetch: MagicMock) -> None:
        mock_fetch.return_value = []

        targets = {
            "include": [
                {"minecraft_version": "1.21.11"},
            ]
        }
        overrides = {"1.21.11": [555, 666]}

        with patch.dict(
            os.environ,
            {
                "CURSEFORGE_PROJECT_ID": "9876",
                "CURSEFORGE_TOKEN": "test-token-uuid",
                "TARGETS_JSON": json.dumps(targets),
                "CURSEFORGE_GAME_VERSIONS": json.dumps(overrides),
            },
        ):
            with patch("sys.stdout") as mock_stdout:
                resolver.main()
                output = "".join(call.args[0] for call in mock_stdout.write.mock_calls).strip()
                data = json.loads(output)
                self.assertEqual(data["project_id"], "9876")
                self.assertEqual(data["game_versions"]["1.21.11"], [555, 666, 9638, 9639])
                mock_fetch.assert_not_called()


if __name__ == "__main__":
    unittest.main()
