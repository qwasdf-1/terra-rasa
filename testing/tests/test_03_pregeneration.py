"""Pregeneration: the next run's wider area is generated in the background once its spawn area is ready,
a start never waits for it, and what is left continues in the live world, across restarts.

Chunks are generated to the "spawn" status, everything except becoming a live chunk, which vanilla
finishes when a player first loads them.
"""

from __future__ import annotations

import json
import math
import re
import time

import pytest

from helpers import END, NETHER, OVERWORLD, make_server, request_activation, wait_true
from terra_rasa_testkit.anvil import chunk_statuses

HOST_CONFIG = {"preloadRadius": 2,
               "pregeneration": {OVERWORLD: 160, NETHER: 48, END: 64}}
GENERATED = {"minecraft:spawn", "minecraft:full"}
PERCENTILES = re.compile(r"P50: ([\d.]+)ms P95: ([\d.]+)ms P99: ([\d.]+)ms")


@pytest.fixture(scope="module")
def players(server):
    return [server.bot("Alice"), server.bot("Bob")]


def circle(center: tuple[int, int], radius_blocks: int) -> set[tuple[int, int]]:
    """The chunks Terra Rasa pregenerates: within the radius (in chunks, rounded up) of the center chunk."""
    r = (radius_blocks + 15) >> 4
    return {(center[0] + dx, center[1] + dz) for dx in range(-r, r + 1) for dz in range(-r, r + 1) if dx * dx + dz * dz <= r * r}


def centers(spawn: dict | list) -> dict[str, tuple[int, int]]:
    x, z = (spawn["x"], spawn["z"]) if isinstance(spawn, dict) else (spawn[0], spawn[2])
    return {OVERWORLD: (x >> 4, z >> 4), NETHER: (math.floor(x / 8) >> 4, math.floor(z / 8) >> 4), END: (0, 0)}


def missing(root, expected: set[tuple[int, int]]) -> set[tuple[int, int]]:
    statuses = chunk_statuses(root)
    return {pos for pos in expected if statuses.get(pos) not in GENERATED}


def test_pregeneration_finishes_before_the_run_is_ready(server, players):
    # The first preparation after startup also warms up the JIT for world generation; measure the second.
    server.rcon("terrarasa prepare")
    first = server.wait_prepared(300)
    server.rcon("terrarasa prepare")
    ready = server.wait_until(lambda s: (s["preparation"].get("ready") or {}).get("runId") not in (None, first["runId"]),
                              300, "the second preparation")["preparation"]["ready"]
    assert all(task["next"] == len(circle((0, 0), HOST_CONFIG["pregeneration"][task["dimension"]]))
               for task in ready["pregeneration"]), ready["pregeneration"]
    timings = ready["timingsMillis"]
    chunks = sum(task["next"] for task in ready["pregeneration"])
    print(f"pregenerated {chunks} chunks in {timings['pregenerating']} ms with two players online:",
          {k: v for k, v in timings.items() if "pregenerating" in k})
    # Paced: no tick runs late while generating (spare-time unloading can fill a tick up to 50 ms).
    assert timings["maxServerTick.pregenerating"] < 60, timings
    for dimension in ready["dimensions"]:
        namespace, path = dimension["stage"].split(":", 1)
        root = server.world / "dimensions" / namespace / path
        expected = circle(centers(ready["spawn"])[dimension["target"]], HOST_CONFIG["pregeneration"][dimension["target"]])
        assert not missing(root, expected), f"{dimension['target']}: chunks not pregenerated"
    for bot in players:
        bot.assert_connected()


#: An area that takes minutes, so a start always arrives in the middle of it.
LARGE = {OVERWORLD: 800}


@pytest.fixture(scope="module")
def large(env):
    class Module:
        HOST_CONFIG = {"preloadRadius": 2, "pregeneration": LARGE}

    process = make_server(env, "test_03_pregeneration-large", Module)
    process.start()
    yield process
    problems = process.suspicious_lines()
    process.stop()
    assert not problems, "unexpected errors in the server log:\n" + "\n".join(problems)


def tick_p95(server) -> float:
    return float(PERCENTILES.search(server.rcon("tick query")).group(2))


def live(server) -> dict[str, dict]:
    return {area["dimension"]: area for area in server.status()["pregeneration"]["live"]}


def test_a_start_stops_pregeneration_and_the_rest_continues_live(large):
    players = [large.bot("Alice"), large.bot("Bob")]
    large.rcon("terrarasa prepare")
    wait_true(lambda: (large.status()["preparation"].get("active") or {}).get("pregenerated", 0) > 200, 180,
              "pregeneration to be under way")
    requested = time.monotonic()
    result = request_activation(large)
    waited = time.monotonic() - requested
    assert result["ok"], result
    print(f"start during pregeneration took {waited:.1f} s")
    assert waited < 30, "a start does not wait for pregeneration"
    for bot in players:
        bot.wait_for(lambda s: s.dimension == OVERWORLD, 30, "the new Overworld")
    area = live(large)[OVERWORLD]
    assert 0 < area["done"] < area["total"]
    wait_true(lambda: live(large)[OVERWORLD]["done"] > area["done"] + 100, 60, "pregeneration to continue live")
    p95 = tick_p95(large)
    print(f"server tick P95 with two players and live pregeneration: {p95} ms")
    assert p95 < 50
    for bot in players:
        bot.assert_connected()


def test_live_pregeneration_resumes_after_a_restart_and_completes(large):
    spawn = large.status()["lastActivation"]["spawn"]
    large.stop()
    saved = json.loads((large.library_data / "pregeneration.json").read_text())
    assert [task["dimension"] for task in saved] == [OVERWORLD]
    stopped_at = saved[0]["next"]
    large.start()
    assert live(large)[OVERWORLD]["done"] >= stopped_at, "progress survives the restart"
    # Nobody is online: the server stays awake and generates faster until the area is done.
    wait_true(lambda: not large.status()["pregeneration"]["live"], 900, "live pregeneration to finish", interval=2)
    assert not (large.library_data / "pregeneration.json").exists()
    overworld = large.world / "dimensions" / "minecraft" / "overworld"
    assert not missing(overworld, circle(centers(spawn)[OVERWORLD], LARGE[OVERWORLD]))
