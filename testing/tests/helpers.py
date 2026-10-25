"""Shared assertions for the Terra Rasa suite."""

from __future__ import annotations

import math
import time
from typing import Any

from terra_rasa_testkit.bot import Bot
from terra_rasa_testkit.server import ServerProcess

OVERWORLD = "minecraft:overworld"
NETHER = "minecraft:the_nether"
END = "minecraft:the_end"
RESETTABLE = (OVERWORLD, NETHER, END)
#: The test host's holding dimension: persistent, never reset.
HOLDING = "terrarasa_testhost:holding"
# Small preloads keep preparation fast.
DEFAULT_CONFIG = {"preloadRadius": 2}
DEFAULT_LEVEL_SEED = 4242


def make_server(env, session: str, module) -> ServerProcess:
    """A server for one test session, configured by the module's HOST_CONFIG and LEVEL_SEED."""
    install, java, packets = env
    return ServerProcess(install, session, java, packets, getattr(module, "HOST_CONFIG", DEFAULT_CONFIG),
                         getattr(module, "LEVEL_SEED", DEFAULT_LEVEL_SEED))


def levels(status: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {level["key"]: level for level in status["levels"]}


def wait_true(predicate, timeout: float, what: str, interval: float = 0.25):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(interval)
    raise AssertionError(f"timed out waiting for {what}")


def request_activation(server: ServerProcess) -> dict[str, Any]:
    """Activates the prepared worlds (or queues it until they are ready) and returns the result."""
    previous = server.status()["lastActivation"]
    reply = server.rcon("terrarasa activate")
    assert "activated" in reply or "queued" in reply, reply
    return server.wait_until(lambda s: not s["activationPending"] and s["lastActivation"] != previous, 60,
                             "the activation")["lastActivation"]


def activate(server: ServerProcess, bots: list[Bot], timeout: float = 240.0) -> dict[str, Any]:
    """Waits for prepared worlds, activates them and waits until every bot is in the new Overworld."""
    ready = server.wait_prepared(timeout)
    marks = [bot.dimension_count() for bot in bots]
    result = request_activation(server)
    assert result["ok"], result
    assert result["runId"] == ready["runId"] and result["seed"] == ready["seed"]
    for bot, mark in zip(bots, marks):
        bot.wait_for_dimension(OVERWORLD, timeout=60, after=mark)
    return result


#: Vanilla places a new player on a collision-free spot within the respawn_radius game rule (default 10).
RESPAWN_RADIUS = 10


def near_spawn(player: dict[str, Any], spawn: list[int]) -> bool:
    return math.hypot(player["x"] - (spawn[0] + 0.5), player["z"] - (spawn[2] + 0.5)) <= RESPAWN_RADIUS * math.sqrt(2) + 1
