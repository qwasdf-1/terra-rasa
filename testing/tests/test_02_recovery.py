"""Failure containment and crash recovery.

In-process failures are injected at every activation phase and must roll back with players still
connected. Crashes are real: the JVM halts mid-swap, and the next start reconciles the canonical
directories and the seed from the journal.
"""

from __future__ import annotations

import json

import pytest

from helpers import OVERWORLD, RESETTABLE, activate, levels, request_activation
from terra_rasa_testkit.rcon import RconError

#: FaultInjection.HALT_STATUS
HALT_STATUS = 86
# Injected failures are logged at ERROR level on purpose.
ALLOWED_LOG = ("Injected failure", "Canonical activation failed", "Run preparation", "Fault injection")


@pytest.fixture(scope="module")
def players(server):
    return [server.bot("Alice"), server.bot("Bob")]


def journal(server) -> dict:
    return json.loads((server.library_data / "active-journal.json").read_text())


@pytest.mark.parametrize("point", ["retire", "promote", "reconstruct", "validate"])
def test_injected_failure_rolls_back_in_process(server, players, point):
    if "ready" not in server.status()["preparation"]:
        server.rcon("terrarasa prepare")
    ready = server.wait_prepared()
    before = server.status()
    server.rcon(f"terrarasa fail {point}")
    result = request_activation(server)
    assert not result["ok"] and "rolled back" in result["error"], result

    status = server.status()
    assert status["journal"]["phase"] == "ROLLED_BACK"
    assert status["globalSeed"] == before["globalSeed"]
    by_key = levels(status)
    for key in RESETTABLE:
        assert by_key[key]["dimension"] == key and by_key[key]["seed"] == before["globalSeed"]
    assert not [k for k, v in by_key.items() if v["class"] == "STAGE"]
    assert status["preparation"]["ready"]["runId"] == ready["runId"], "the prepared worlds stay adoptable"
    assert not (server.library_data / "quarantine" / f"replaced-by-{ready['runId']}").exists()
    for bot in players:
        bot.assert_connected()
        assert bot.state.play_entered == 1
    # The same prepared worlds activate cleanly afterwards.
    assert activate(server, players)["runId"] == ready["runId"]


def crash_during_activation(server, point: str) -> tuple[dict, dict]:
    server.start()
    server.rcon("terrarasa prepare")
    first = activate(server, [])
    server.rcon("terrarasa prepare")
    ready = server.wait_prepared()
    server.rcon(f"terrarasa fail {point}")
    # The activation runs inside the command, so the halt drops the RCON connection before any reply.
    with pytest.raises(RconError):
        server.rcon("terrarasa activate")
    assert server.wait_exit(60) == HALT_STATUS, "the server should have halted at the fault point"
    return first, ready


def test_crash_mid_promotion_rolls_back_at_startup(fresh_server):
    first, ready = crash_during_activation(fresh_server, "halt-after-first-move")
    assert journal(fresh_server)["phase"] == "PROMOTING"
    fresh_server.start()
    status = fresh_server.status()
    assert status["journal"]["phase"] == "ROLLED_BACK"
    assert status["globalSeed"] == first["seed"] == levels(status)[OVERWORLD]["seed"]
    assert status["preparation"]["ready"]["runId"] == ready["runId"], "rollback restored the staged worlds"
    assert activate(fresh_server, [])["runId"] == ready["runId"]
    assert not fresh_server.suspicious_lines(allow=ALLOWED_LOG)


def test_crash_after_promotion_rolls_forward_at_startup(fresh_server):
    _, ready = crash_during_activation(fresh_server, "halt-after-promote")
    assert journal(fresh_server)["phase"] == "PROMOTED"
    fresh_server.start()
    status = fresh_server.status()
    assert status["journal"]["phase"] == "COMMITTED"
    assert status["globalSeed"] == ready["seed"] == levels(status)[OVERWORLD]["seed"]
    assert not fresh_server.suspicious_lines(allow=ALLOWED_LOG)


def test_crash_after_commit_keeps_the_committed_worlds(fresh_server):
    _, ready = crash_during_activation(fresh_server, "halt-after-commit")
    assert journal(fresh_server)["phase"] == "COMMITTED"
    fresh_server.start()
    status = fresh_server.status()
    assert status["globalSeed"] == ready["seed"]
    assert not (fresh_server.library_data / "quarantine" / f"replaced-by-{ready['runId']}").exists()
    assert not fresh_server.suspicious_lines(allow=ALLOWED_LOG)
