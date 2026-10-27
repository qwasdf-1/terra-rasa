"""Fixtures for Terra Rasa's real-server suite. Each test module gets its own fresh server and world,
driven through the test host's /terrarasa commands."""

from __future__ import annotations

import pytest

from helpers import make_server
from terra_rasa_testkit.server import environment


@pytest.fixture(scope="session")
def env():
    try:
        return environment()
    except KeyError as missing:
        pytest.exit(f"Run through ./gradlew integrationTest (missing environment variable {missing})", returncode=2)


@pytest.fixture(scope="module")
def server(env, request):
    process = make_server(env, request.module.__name__.rsplit(".", 1)[-1], request.module)
    process.start()
    yield process
    process.stop()
    problems = process.suspicious_lines(allow=getattr(request.module, "ALLOWED_LOG", ()))
    assert not problems, "unexpected errors in the server log:\n" + "\n".join(problems)


@pytest.fixture
def fresh_server(env, request):
    """A dedicated server per test, for crash and restart scenarios."""
    process = make_server(env, request.node.name[:60], request.module)
    yield process
    if process.running:
        process.stop()
