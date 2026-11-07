"""The prepared install matches the loader the suite runs against (./gradlew -Ploader=...).

These checks read the install itself rather than starting a server: everything they verify is the work
of ./gradlew prepareIntegrationServer, not of the mod under test.
"""

from __future__ import annotations

import pytest

from terra_rasa_testkit.server import (
    FABRIC,
    NEOFORGE,
    NEOFORGE_RUN_SCRIPTS,
    SHARED,
    SHARED_FABRIC_DIRECTORIES,
    SHARED_NEOFORGE,
    current_loader,
)


def test_loader_is_known():
    assert current_loader() in (FABRIC, NEOFORGE)


def test_fabric_install(env):
    if current_loader() != FABRIC:
        pytest.skip("the prepared install is a NeoForge one")
    install = env[0]
    # libraries/, .fabric/ and versions/ appear when the first session starts the launcher.
    for name in SHARED:
        if name not in SHARED_FABRIC_DIRECTORIES:
            assert (install / name).exists(), f"missing {name} in {install}"
    assert list((install / "mods").glob("*.jar")), "no mod jars installed"
    for absent in NEOFORGE_RUN_SCRIPTS:
        assert not (install / absent).exists(), f"Fabric install must not ship {absent}"


@pytest.mark.neoforge
def test_neoforge_install(env):
    if current_loader() != NEOFORGE:
        pytest.skip("the prepared install is a Fabric one")
    install = env[0]
    for name in (*SHARED_NEOFORGE, *NEOFORGE_RUN_SCRIPTS):
        assert (install / name).exists(), f"missing {name} in {install}"
    # The harness starts the server with these program arguments, exactly as run.sh does.
    arguments = sorted(install.glob("libraries/net/neoforged/neoforge/*/unix_args.txt"))
    assert len(arguments) == 1, f"expected one NeoForge arguments file, found {arguments}"
    text = arguments[0].read_text(encoding="utf-8")
    assert "net.neoforged.fml.startup.Server" in text and "--fml.neoForgeVersion" in text
    assert (install / "mods").is_dir() and list((install / "mods").glob("*.jar")), "no mod jars installed"
    # Nothing a Fabric install would have.
    assert not (install / "fabric-server-launch.jar").exists()
    assert not (install / "fabric-server-launcher.properties").exists()
    assert not (install / ".fabric").exists()
    assert not list((install / "mods").glob("*fabric-api*")), "Fabric API does not belong on a NeoForge server"
