import json
import unittest
from unittest.mock import patch

import repair_curseforge_environment as repair


class RepairTests(unittest.TestCase):
    def test_only_environment_changes(self):
        self.assertEqual(repair.corrected([11, 22, 33, 9638, 9639]), [11, 22, 33, 9639])
        self.assertEqual(repair.corrected([11, 9639]), [11, 9639])
        with self.assertRaises(ValueError):
            repair.corrected([11, 22])

    def test_discover_original_numeric_metadata(self):
        log = "\n".join([
            "publish (26.2 neoforge)\tUpload\t2026-11-09T12:00:00Z   PROJECT_ID: 1774398",
            "publish (26.2 neoforge)\tUpload\t2026-11-09T12:00:00Z   RELEASE_TAG: v0.6.0",
            "publish (26.2 neoforge)\tUpload\t2026-11-09T12:00:00Z   LOADER: neoforge",
            "publish (26.2 neoforge)\tUpload\t2026-11-09T12:00:00Z   MINECRAFT: 26.2",
            "publish (26.2 neoforge)\tUpload\t2026-11-09T12:00:00Z   JAR_NAME: soullink-mc26.2-neoforge-0.6.0.jar",
            'publish (26.2 neoforge)\tUpload\t2026-11-09T12:00:00Z   GAME_VERSIONS: {"26.2":[16498,50,9638,9639,700]}',
            "publish (26.2 neoforge)\tUpload\t2026-11-09T12:00:00Z   FABRIC_LOADER_ID: 50",
            "publish (26.2 neoforge)\tUpload\t2026-11-09T12:00:00Z   NEOFORGE_LOADER_ID: 51",
            "publish (26.2 neoforge)\tUpload\t2026-11-09T12:00:00Z Published CurseForge file 123 for Minecraft 26.2 (neoforge)",
        ])
        with patch.object(repair, "gh", side_effect=[
            json.dumps([{"databaseId": 1, "status": "completed"}]),
            json.dumps([{"jobs": [{"id": 2, "name": "publish (26.2 neoforge)", "conclusion": "success"}]}]),
            log,
        ]):
            rows = repair.logged_rows("qwasdf-1/soul-link", 1774398, "soullink")
        self.assertEqual(rows[123]["jar"], "soullink-mc26.2-neoforge-0.6.0.jar")
        self.assertEqual(repair.corrected(rows[123]["original_ids"]), [51, 700, 9639, 16498])

    def test_reconstruct_disambiguates_minecraft_category(self):
        versions = [
            {"id": 50, "name": "Fabric"}, {"id": 70, "name": "Java 25"},
            {"id": 9638, "name": "Client"}, {"id": 9639, "name": "Server"},
            {"id": 16498, "name": "26.2", "gameVersionTypeID": 86},
            {"id": 16500, "name": "26.2", "gameVersionTypeID": 1},
        ]
        types = [{"id": 86, "name": "26.2"}, {"id": 1, "name": "Bukkit 26.2"}]
        self.assertEqual(repair.numeric_tags(["26.2"], "fabric", "25", versions, types),
                         [50, 70, 9639, 16498])
        with self.assertRaises(ValueError):
            repair.numeric_tags(["26.2"], "fabric", "25", versions, types[1:])


if __name__ == "__main__":
    unittest.main()
