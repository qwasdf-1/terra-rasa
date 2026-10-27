"""A spawn-ready run survives the overall timeout while optional pregeneration is flushed."""

from __future__ import annotations

import pytest

from helpers import OVERWORLD, make_server

PREPARATION_TIMEOUT_SECONDS = 120
PREGENERATION_RADIUS_BLOCKS = 800
HOST_CONFIG = {
    "preloadRadius": 2,
    "preparationTimeoutSeconds": PREPARATION_TIMEOUT_SECONDS,
    "pregeneration": {OVERWORLD: PREGENERATION_RADIUS_BLOCKS},
}


def pregeneration_total(radius_chunks: int) -> int:
    return sum(
        1
        for x in range(-radius_chunks, radius_chunks + 1)
        for z in range(-radius_chunks, radius_chunks + 1)
        if x * x + z * z <= radius_chunks * radius_chunks
    )


@pytest.fixture(scope="module")
def timeout_server(env):
    class Module:
        HOST_CONFIG = HOST_CONFIG

    server = make_server(env, "test_05_preparation_timeout", Module)
    server.start()
    yield server
    server.stop()
    problems = server.suspicious_lines()
    assert not problems, "unexpected errors in the server log:\n" + "\n".join(problems)


def test_spawn_ready_run_finishes_flushing_after_timeout_stops_optional_pregeneration(timeout_server):
    timeout_server.rcon("terrarasa prepare")
    ready_status = timeout_server.wait_until(
        lambda status: status["preparation"].get("ready") is not None
        or status["preparation"].get("lastFailure") is not None,
        PREPARATION_TIMEOUT_SECONDS + 240,
        "the timed-out optional pregeneration to flush and publish its spawn-ready run",
    )
    assert "ready" in ready_status["preparation"], ready_status["preparation"]
    ready = ready_status["preparation"]["ready"]
    timings = ready["timingsMillis"]
    assert "lastFailure" not in ready_status["preparation"], ready_status["preparation"]
    assert timings["total"] >= PREPARATION_TIMEOUT_SECONDS * 1000, timings
    assert timings["flushing"] > 0, timings
    task = next(task for task in ready["pregeneration"] if task["dimension"] == OVERWORLD)
    assert task["next"] < pregeneration_total(task["radiusChunks"]), task
