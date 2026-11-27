"""Fixtures for Terra Rasa's real-server suite. Default-config modules share one session server,
driven through the test host's /terrarasa commands."""

from __future__ import annotations

import pytest

from helpers import DEFAULT_CONFIG, DEFAULT_LEVEL_SEED, make_server, wait_true
from mc_testkit.server import SUSPICIOUS, VANILLA_DIAGNOSTICS, ServerProcess, environment


def _is_default_module(module) -> bool:
    return (
        getattr(module, "HOST_CONFIG", DEFAULT_CONFIG) == DEFAULT_CONFIG
        and getattr(module, "LEVEL_SEED", DEFAULT_LEVEL_SEED) == DEFAULT_LEVEL_SEED
        and getattr(module, "ALLOWED_LOG", ()) == ()
    )


def _disconnect_bots(process: ServerProcess) -> None:
    if not process._bots:
        return
    for bot in list(process._bots):
        bot.disconnect()
    process._bots.clear()
    if process.running:
        wait_true(lambda: "There are 0 of a max of" in process.rcon("list"), 10, "all bots to disconnect")


def _new_suspicious_lines(process: ServerProcess, start_line: int, allow: tuple[str, ...]) -> list[str]:
    allowed = allow + VANILLA_DIAGNOSTICS
    with process._lines_lock:
        slice_lines = list(process.lines[start_line:])
    return [line for line in slice_lines if SUSPICIOUS.search(line) and not any(a in line for a in allowed)]


@pytest.fixture(scope="session")
def env():
    try:
        return environment()
    except KeyError as missing:
        pytest.exit(f"Run through ./gradlew integrationTest (missing environment variable {missing})", returncode=2)


@pytest.fixture(scope="session")
def _shared_default_server(env):
    state: dict[str, object] = {"process": None}
    yield state
    process = state["process"]
    if isinstance(process, ServerProcess):
        process.stop()


@pytest.fixture(scope="module")
def server(env, request, _shared_default_server):
    module = request.module
    allow = getattr(module, "ALLOWED_LOG", ())
    if _is_default_module(module):
        process = _shared_default_server["process"]
        if process is None:
            process = make_server(env, "default_shared", module)
            process.start()
            _shared_default_server["process"] = process
        log_start = len(process.lines)
        try:
            yield process
        finally:
            _disconnect_bots(process)
        problems = _new_suspicious_lines(process, log_start, allow)
        assert not problems, "unexpected errors in the server log:\n" + "\n".join(problems)
        return

    process = make_server(env, module.__name__.rsplit(".", 1)[-1], module)
    process.start()
    yield process
    process.stop()
    problems = process.suspicious_lines(allow=allow)
    assert not problems, "unexpected errors in the server log:\n" + "\n".join(problems)


@pytest.fixture
def fresh_server(env, request):
    """A dedicated server per test, for crash and restart scenarios."""
    process = make_server(env, request.node.name[:60], request.module)
    yield process
    if process.running:
        process.stop()
