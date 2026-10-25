"""Preparing and activating canonical worlds on a live server.

Two headless clients stay connected for the whole module, so every assertion also proves that a swap
never disconnects or re-logs a player.
"""

from __future__ import annotations

import json

import pytest

from helpers import HOLDING, OVERWORLD, RESETTABLE, activate, levels, near_spawn, wait_true

LEVEL_SEED = 4242


@pytest.fixture(scope="module")
def players(server):
    return [server.bot("Alice"), server.bot("Bob")]


def test_policy_classifies_every_dimension(server, players):
    by_key = levels(server.status())
    assert by_key[HOLDING]["class"] == "PERSISTENT_SERVICE"
    for key in RESETTABLE:
        assert by_key[key]["class"] == "RESETTABLE_CANONICAL" and by_key[key]["dimension"] == key


def test_preparation_is_isolated_from_canonical_levels(server, players):
    before = levels(server.status())
    assert "preparing" in server.rcon("terrarasa prepare")
    ready = server.wait_prepared()
    status = server.status()
    after = levels(status)
    assert ready["state"] == "CLOSED_READY"
    assert [d["target"] for d in ready["dimensions"]] == list(RESETTABLE)
    assert not [key for key, level in after.items() if level["class"] == "STAGE"], "staged levels are closed"
    for key in RESETTABLE + (HOLDING,):
        assert after[key]["identity"] == before[key]["identity"]
    assert status["globalSeed"] == LEVEL_SEED and ready["seed"] != LEVEL_SEED
    for dimension in ready["dimensions"]:
        namespace, path = dimension["stage"].split(":", 1)
        assert (server.world / "dimensions" / namespace / path / "data").is_dir()
    overworld_stage = ready["dimensions"][0]["stage"].split(":", 1)[1]
    assert any((server.world / "dimensions" / "terrarasa" / overworld_stage / "region").glob("*.mca"))
    assert (server.library_data / "stages" / ready["runId"] / "manifest.json").is_file()


def test_activation_replaces_canonical_levels_without_reconnecting(server, players):
    before = levels(server.status())
    result = activate(server, players)
    status = server.status()
    after = levels(status)
    seed = result["seed"]
    for key in RESETTABLE:
        assert after[key]["identity"] != before[key]["identity"], f"{key} was not replaced"
        assert after[key]["dimension"] == key and after[key]["seed"] == seed
        assert "seedOverride" not in after[key], "canonical levels use the global seed"
    assert after[HOLDING]["identity"] == before[HOLDING]["identity"]
    assert status["globalSeed"] == seed and f"[{seed}]" in server.rcon("seed")
    assert status["journal"]["phase"] == "COMMITTED"
    for bot in players:
        bot.assert_connected()
        assert bot.state.play_entered == 1 and bot.state.reconfigurations == 0
        assert [c.dimension for c in bot.state.dimensions] == [OVERWORLD, HOLDING, OVERWORLD]
        player = server.player(bot.name)
        assert player["dimension"] == OVERWORLD and player["gameMode"] == "survival"
        assert near_spawn(player, result["spawn"])
    assert {"retire", "promote", "reconstruct", "validate", "total"} <= result["timingsMillis"].keys()


def test_dimensions_that_are_not_reset_keep_the_world_seed(server):
    status = server.status()
    assert levels(status)[HOLDING]["seed"] == LEVEL_SEED != status["globalSeed"]
    assert json.loads((server.library_data / "pinned-seed.json").read_text())["seed"] == LEVEL_SEED


def test_old_data_is_cleaned_up_after_commit(server):
    run_id = server.status()["lastActivation"]["runId"]

    def cleaned():
        return not (server.stage_dimensions / run_id).exists() \
            and not (server.library_data / "quarantine" / f"replaced-by-{run_id}").exists() \
            and not (server.library_data / "stages" / run_id).exists()

    wait_true(cleaned, 30, "post-commit cleanup")
    assert any((server.world / "dimensions" / "minecraft" / "overworld" / "region").glob("*.mca"))


def test_repeated_swaps_use_fresh_seeds_without_leaking_levels(server, players):
    seeds, identities = set(), set()
    for _ in range(5):
        server.rcon("terrarasa prepare")
        result = activate(server, players)
        seeds.add(result["seed"])
        identities.add(levels(server.status())[OVERWORLD]["identity"])
    assert len(seeds) == 5 and len(identities) == 5
    for bot in players:
        bot.assert_connected()
        assert bot.state.play_entered == 1
    gc = server.rcon_json("terrarasa gc")
    # Every retired level must become unreachable; the most recent generation may linger briefly.
    assert gc["retiredLevelsReachable"] <= len(RESETTABLE), gc
