"""Runs a production-style Fabric dedicated server for one test session."""

from __future__ import annotations

import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import threading
import time
from pathlib import Path
from typing import Any, Callable

from .bot import Bot, status_ping
from .protocol import PacketIds
from .rcon import Rcon

DONE = re.compile(r"\[Server thread/INFO\].*: Done \(")
RCON_READY = re.compile(r"RCON running on ")
#: ERROR lines vanilla 26.2 logs on its own. "Detected unsafe terrain read during worldgen" comes from
#: WorldGenRegion when the sculk_patch_deep_dark feature reads two chunks away; whether it happens depends
#: on chunk generation order. Reproduced without any mod: seed -7840930131180687468, one player at spawn,
#: view-distance 5 logs the same 54 lines (view-distance 6 logs none). "Block-attached entity at invalid
#: position" comes from BlockAttachedEntity when a structure template stores an item frame or painting
#: with a block position far from the entity; the reference modpack's structure packs contain such templates.
VANILLA_DIAGNOSTICS = ("Detected unsafe terrain read during worldgen", "Block-attached entity at invalid position")
# Lines that must never appear in a healthy run. Expected, deliberately injected failures are filtered
# by the tests that cause them.
SUSPICIOUS = re.compile(r"/(ERROR|FATAL)\]|Exception in server tick loop|Encountered an unexpected exception")
SHARED = ("libraries", ".fabric", "versions", "mods", "fabric-server-launch.jar", "fabric-server-launcher.properties")


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


# Lets the suite arm crash points; production servers never set it.
FAULT_INJECTION_PROPERTY = "terrarasa.faultInjection"


class ServerProcess:
    """One isolated server directory (fresh world) sharing the prepared install's libraries and mods.

    The defaults drive Terra Rasa's test host; a host mod's suite subclasses this and points the three
    class attributes at its own config file and commands.
    """

    #: Written to config/ before startup with the module's configuration.
    CONFIG_FILE = "terrarasa-testhost.json"
    #: Returns the host's status JSON, which includes the library's "preparation" and "levels".
    STATUS_COMMAND = "terrarasa status"
    #: Returns one player's JSON; "{name}" is replaced.
    PLAYER_COMMAND = "terrarasa player {name}"
    #: Merged under every module's configuration.
    CONFIG_DEFAULTS: dict[str, Any] = {}

    def __init__(self, install: Path, session: str, java: str, packets: PacketIds,
                 config: dict[str, Any] | None = None, level_seed: int = 4242) -> None:
        self.install = install
        self.directory = install / "sessions" / session
        self.java = java
        self.packets = packets
        self.port = free_port()
        self.rcon_port = free_port()
        self.password = secrets.token_urlsafe(16)
        self.lines: list[str] = []
        self._lines_lock = threading.Lock()
        self._process: subprocess.Popen[str] | None = None
        self._rcon: Rcon | None = None
        self._bots: list[Bot] = []
        self._prepare(config or {}, level_seed)

    # ---------------------------------------------------------------------------------------------
    # Setup

    def _prepare(self, config: dict[str, Any], level_seed: int) -> None:
        if self.directory.exists():
            shutil.rmtree(self.directory)
        self.directory.mkdir(parents=True)
        for name in SHARED:
            source = self.install / name
            if name in ("libraries", ".fabric", "versions"):
                source.mkdir(exist_ok=True)
            (self.directory / name).symlink_to(source)
        (self.directory / "eula.txt").write_text("eula=true\n", encoding="utf-8")
        properties = {
            "online-mode": "false",
            "server-ip": "127.0.0.1",
            "server-port": self.port,
            "enable-rcon": "true",
            "rcon.port": self.rcon_port,
            "rcon.password": self.password,
            "broadcast-rcon-to-ops": "false",
            "level-name": "world",
            "level-seed": level_seed,
            "spawn-protection": 0,
            "view-distance": 4,
            "simulation-distance": 4,
            "network-compression-threshold": 256,
            "max-players": 8,
            "difficulty": "normal",
            "sync-chunk-writes": "false",
            # Keep headless integration servers responsive to RCON after their last bot disconnects.
            "pause-when-empty-seconds": -1,
        }
        (self.directory / "server.properties").write_text(
            "".join(f"{key}={value}\n" for key, value in properties.items()), encoding="utf-8")
        config_dir = self.directory / "config"
        config_dir.mkdir()
        (config_dir / self.CONFIG_FILE).write_text(json.dumps({**self.CONFIG_DEFAULTS, **config}), encoding="utf-8")

    @property
    def library_data(self) -> Path:
        """Terra Rasa's own files: journal, stage manifests, pinned seed and quarantine."""
        return self.world / "terrarasa"

    @property
    def stage_dimensions(self) -> Path:
        """Where staged dimension data lives until promotion."""
        return self.world / "dimensions" / "terrarasa" / "stage"

    # ---------------------------------------------------------------------------------------------
    # Process control

    def start(self, timeout: float = 240.0) -> "ServerProcess":
        start_index = len(self.lines)
        # On 2 GB, ZGC stalls allocating threads (the server thread included, for about 35 ms) while two
        # worlds generate at once: pregeneration, and players exploring a freshly swapped world.
        self._process = subprocess.Popen(
            [self.java, "-Xms1G", f"-Xmx{os.environ.get('MC_TEST_HEAP', '4G')}", "-XX:+UseZGC", f"-D{FAULT_INJECTION_PROPERTY}=true",
             "-jar", "fabric-server-launch.jar", "nogui"],
            cwd=self.directory, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", bufsize=1)
        threading.Thread(target=self._pump, args=(self._process,), daemon=True).start()
        self.wait_for_log(DONE, timeout, start=start_index)
        # Vanilla opens the RCON listener only after "Done"; connecting earlier races it.
        self.wait_for_log(RCON_READY, 30, start=start_index)
        self._rcon = Rcon("127.0.0.1", self.rcon_port, self.password)
        self._wait_network_ready()
        return self

    def _wait_network_ready(self, timeout: float = 30.0) -> None:
        # Connections arriving in the first moments after "Done" can be dropped before login; a
        # successful status ping proves the listener serves real clients.
        deadline = time.monotonic() + timeout
        while True:
            try:
                status_ping("127.0.0.1", self.port, self.packets)
                return
            except (OSError, ConnectionError, EOFError, ValueError):
                if time.monotonic() > deadline:
                    raise
                time.sleep(0.25)

    def _pump(self, process: subprocess.Popen[str]) -> None:
        assert process.stdout is not None
        with open(self.directory / "console.log", "a", encoding="utf-8") as log:
            for line in process.stdout:
                log.write(line)
                log.flush()
                with self._lines_lock:
                    self.lines.append(line.rstrip("\n"))

    def stop(self, timeout: float = 120.0) -> None:
        for bot in self._bots:
            bot.disconnect()
        self._bots.clear()
        if self._process is None:
            return
        if self._process.poll() is None:
            try:
                self.rcon("stop")
            except Exception:  # noqa: BLE001 - fall back to stdin
                assert self._process.stdin is not None
                self._process.stdin.write("stop\n")
                self._process.stdin.flush()
            self._process.wait(timeout)
        self._close_rcon()
        self._process = None

    def kill(self) -> None:
        """Simulates a crash: no save, no shutdown hooks."""
        for bot in self._bots:
            bot.disconnect()
        self._bots.clear()
        if self._process is not None:
            self._process.kill()
            self._process.wait(30)
        self._close_rcon()
        self._process = None

    def wait_exit(self, timeout: float = 60.0) -> int:
        assert self._process is not None
        code = self._process.wait(timeout)
        for bot in self._bots:
            bot.disconnect()
        self._bots.clear()
        self._close_rcon()
        self._process = None
        return code

    def restart(self) -> "ServerProcess":
        self.stop()
        return self.start()

    def _close_rcon(self) -> None:
        if self._rcon is not None:
            self._rcon.close()
            self._rcon = None

    @property
    def running(self) -> bool:
        return self._process is not None and self._process.poll() is None

    # ---------------------------------------------------------------------------------------------
    # Interaction

    def rcon(self, command: str) -> str:
        if self._rcon is None:
            raise RuntimeError("server is not running")
        return self._rcon.command(command)

    def rcon_json(self, command: str) -> dict[str, Any]:
        output = self.rcon(command)
        try:
            return json.loads(output)
        except json.JSONDecodeError as error:
            raise AssertionError(f"{command!r} did not return JSON: {output[:500]}") from error

    def status(self) -> dict[str, Any]:
        return self.rcon_json(self.STATUS_COMMAND)

    def player(self, name: str) -> dict[str, Any]:
        return self.rcon_json(self.PLAYER_COMMAND.format(name=name))

    def bot(self, name: str) -> Bot:
        bot = Bot(name, "127.0.0.1", self.port, self.packets).connect()
        self._bots.append(bot)
        return bot

    def disconnect_bot(self, name: str) -> None:
        """Disconnects one client, like a player quitting the game."""
        for bot in list(self._bots):
            if bot.name == name:
                bot.disconnect()
                self._bots.remove(bot)

    def wait_until(self, predicate: Callable[[dict[str, Any]], bool], timeout: float, what: str,
                   interval: float = 0.25) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        last: dict[str, Any] = {}
        while time.monotonic() < deadline:
            last = self.status()
            if predicate(last):
                return last
            time.sleep(interval)
        raise AssertionError(f"timed out waiting for {what}; last status: {json.dumps(last)[:2000]}")

    def wait_prepared(self, timeout: float = 240.0) -> dict[str, Any]:
        status = self.wait_until(lambda s: "ready" in s["preparation"], timeout, "a prepared run")
        return status["preparation"]["ready"]

    def wait_for_log(self, pattern: re.Pattern[str] | str, timeout: float, start: int = 0) -> str:
        regex = re.compile(pattern) if isinstance(pattern, str) else pattern
        deadline = time.monotonic() + timeout
        index = start
        while time.monotonic() < deadline:
            with self._lines_lock:
                lines = self.lines[index:]
            for line in lines:
                if regex.search(line):
                    return line
            index += len(lines)
            if self._process is not None and self._process.poll() is not None:
                raise AssertionError(f"server exited while waiting for {regex.pattern!r}:\n" + "\n".join(self.lines[-40:]))
            time.sleep(0.1)
        raise AssertionError(f"timed out waiting for log {regex.pattern!r}")

    def suspicious_lines(self, allow: tuple[str, ...] = ()) -> list[str]:
        allowed = allow + VANILLA_DIAGNOSTICS
        with self._lines_lock:
            return [line for line in self.lines if SUSPICIOUS.search(line) and not any(a in line for a in allowed)]

    @property
    def world(self) -> Path:
        return self.directory / "world"


def environment() -> tuple[Path, str, PacketIds]:
    install = Path(os.environ["MC_TEST_SERVER_DIR"])
    java = os.environ.get("MC_TEST_JAVA", "java")
    packets = PacketIds.load(os.environ["MC_TEST_PACKETS_JSON"])
    return install, java, packets
