"""What a level leaves behind when it is retired.

A block change queues its POI update as a server task, and chunks finished while a level is drained
queue such tasks too; they run only after the swap has closed the level. The test host places a POI
block in every level just before it is retired, so those tasks always run against a closed level.
"""

from __future__ import annotations

import pytest

from helpers import activate


@pytest.fixture(scope="module")
def players(server):
    return [server.bot("Alice")]


def test_poi_updates_queued_before_retirement_are_ignored(server, players):
    server.rcon("terrarasa poi-before-retire true")
    server.rcon("terrarasa prepare")
    activate(server, players)
    server.rcon("terrarasa poi-before-retire false")
    # One bell per retired level; each update ran after its level closed and was ignored.
    assert server.status()["activation"]["ignoredPoiUpdates"] >= 3
    # The server keeps ticking: the queued updates neither block the server thread nor fail.
    server.rcon("terrarasa prepare")
    server.wait_prepared()
    for bot in players:
        bot.assert_connected()
