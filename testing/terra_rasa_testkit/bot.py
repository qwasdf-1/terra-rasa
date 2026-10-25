"""Headless protocol client that behaves like a connected vanilla player.

It answers keep-alives, pings and teleports, acknowledges chunk batches, and records what a real client
would observe: dimension transitions (login and respawn packets), health, chat and disconnects. Tests use
it to prove that players stay connected across canonical world swaps.
"""

from __future__ import annotations

import socket
import threading
import time
from dataclasses import dataclass, field
from typing import Callable

from .protocol import (PROTOCOL_VERSION, PacketIds, Reader, Writer, component_text, frame, offline_uuid,
                       unframe)


class BotError(AssertionError):
    pass


#: ClientboundGameEventPacket.WIN_GAME
GAME_EVENT_WIN_GAME = 4
#: ClientboundBossEventPacket.OperationType ordinals
BOSS_ADD, BOSS_REMOVE, BOSS_UPDATE_PROGRESS = 0, 1, 2

@dataclass
class DimensionChange:
    dimension: str
    via: str
    at: float


@dataclass
class BotState:
    phase: str = "handshake"
    dimension: str | None = None
    dimensions: list[DimensionChange] = field(default_factory=list)
    health: float | None = None
    food: int | None = None
    teleports: int = 0
    respawns: int = 0
    messages: list[str] = field(default_factory=list)
    action_bars: list[str] = field(default_factory=list)
    disconnected: bool = False
    disconnect_reason: str | None = None
    reconfigurations: int = 0
    play_entered: int = 0
    entity_id: int | None = None
    #: Registry ids of the mob effects the server says this player has.
    effects: set[int] = field(default_factory=set)
    #: Times the server rolled the end credits (game event WIN_GAME).
    credits: int = 0
    #: Boss bars on screen: bar UUID to (title, progress from 0 to 1).
    boss_bars: dict[str, tuple[str, float]] = field(default_factory=dict)


def status_ping(host: str, port: int, packets: PacketIds, timeout: float = 5.0) -> str:
    """Server List Ping; returns the status JSON. Proves the network listener accepts real clients."""
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        sock.sendall(frame(packets.serverbound("handshake", "intention"),
                           Writer().varint(PROTOCOL_VERSION).string(host).ushort(port).varint(1).bytes(), -1))
        sock.sendall(frame(packets.serverbound("status", "status_request"), b"", -1))
        data = bytearray()
        while True:
            chunk = sock.recv(65536)
            if not chunk:
                raise ConnectionError("status connection closed")
            data.extend(chunk)
            reader = Reader(bytes(data))
            try:
                length = reader.varint()
                if reader.remaining() < length:
                    continue
                packet_id, body = unframe(reader.take(length), False)
                return body.string()
            except EOFError:
                continue


class Bot:
    def __init__(self, name: str, host: str, port: int, packets: PacketIds) -> None:
        self.name = name
        self.uuid = offline_uuid(name)
        self._host = host
        self._port = port
        self._ids = packets
        self._socket: socket.socket | None = None
        self._send_lock = threading.Lock()
        self._condition = threading.Condition()
        self._compression = -1
        self._thread: threading.Thread | None = None
        self.state = BotState()
        self.errors: list[BaseException] = []

    # ---------------------------------------------------------------------------------------------
    # Connection lifecycle

    def connect(self, timeout: float = 60.0) -> "Bot":
        self._socket = socket.create_connection((self._host, self._port), timeout=10.0)
        self._socket.settimeout(None)
        self._socket.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self._send("handshake", "intention", Writer().varint(PROTOCOL_VERSION).string(self._host)
                   .ushort(self._port).varint(2).bytes())
        self.state.phase = "login"
        self._send("login", "hello", Writer().string(self.name).uuid(self.uuid).bytes())
        self._thread = threading.Thread(target=self._read_loop, name=f"bot-{self.name}", daemon=True)
        self._thread.start()
        self.wait_for(lambda s: s.dimension is not None, timeout, "join the game")
        return self

    def disconnect(self) -> None:
        sock = self._socket
        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            sock.close()
        if self._thread is not None:
            self._thread.join(timeout=5)

    # ---------------------------------------------------------------------------------------------
    # Observations

    def wait_for(self, predicate: Callable[[BotState], bool], timeout: float, what: str) -> BotState:
        deadline = time.monotonic() + timeout
        with self._condition:
            while not predicate(self.state):
                if self.errors:
                    raise BotError(f"{self.name} failed while waiting to {what}: {self.errors[0]!r}")
                if self.state.disconnected and not predicate(self.state):
                    raise BotError(f"{self.name} disconnected while waiting to {what}: {self.state.disconnect_reason}")
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise BotError(f"{self.name} timed out waiting to {what}; state={self.state}")
                self._condition.wait(remaining)
            return self.state

    def wait_for_dimension(self, dimension: str, timeout: float = 30.0, after: int = 0) -> None:
        self.wait_for(lambda s: any(c.dimension == dimension for c in s.dimensions[after:]), timeout,
                      f"enter {dimension}")

    def wait_for_message(self, fragment: str, timeout: float = 30.0) -> str:
        found: list[str] = []

        def matches(state: BotState) -> bool:
            for message in state.messages:
                if fragment in message:
                    found.append(message)
                    return True
            return False

        self.wait_for(matches, timeout, f"receive a message containing {fragment!r}")
        return found[0]

    def assert_connected(self) -> None:
        if self.state.disconnected:
            raise BotError(f"{self.name} was disconnected: {self.state.disconnect_reason}")
        if self.errors:
            raise BotError(f"{self.name} protocol error: {self.errors[0]!r}")

    def dimension_count(self) -> int:
        return len(self.state.dimensions)

    # ---------------------------------------------------------------------------------------------
    # Actions

    def command(self, command: str) -> None:
        """Runs a command as this player (unsigned chat command)."""
        self._send("play", "chat_command", Writer().string(command.removeprefix("/")).bytes())

    def respawn(self) -> None:
        """Clicks "Respawn" on the death screen."""
        self._send("play", "client_command", Writer().varint(0).bytes())

    # ---------------------------------------------------------------------------------------------
    # Protocol

    def _send(self, state: str, name: str, body: bytes) -> None:
        packet_id = self._ids.serverbound(state, name)
        data = frame(packet_id, body, self._compression)
        with self._send_lock:
            if self._socket is None:
                raise BotError("not connected")
            self._socket.sendall(data)

    def _read_exact(self, count: int) -> bytes:
        assert self._socket is not None
        chunks = bytearray()
        while len(chunks) < count:
            chunk = self._socket.recv(count - len(chunks))
            if not chunk:
                raise ConnectionError("connection closed by server")
            chunks.extend(chunk)
        return bytes(chunks)

    def _read_varint_from_socket(self) -> int:
        result = 0
        for shift in range(0, 35, 7):
            byte = self._read_exact(1)[0]
            result |= (byte & 0x7F) << shift
            if not byte & 0x80:
                return result
        raise ValueError("VarInt too long")

    def _read_loop(self) -> None:
        try:
            while True:
                length = self._read_varint_from_socket()
                payload = self._read_exact(length)
                packet_id, reader = unframe(payload, self._compression >= 0)
                self._handle(packet_id, reader)
        except (ConnectionError, OSError) as error:
            self._mark_disconnected(self.state.disconnect_reason or f"socket closed: {error}")
        except BaseException as error:  # noqa: BLE001 - surfaced to the waiting test
            with self._condition:
                self.errors.append(error)
                self._condition.notify_all()
            self._mark_disconnected(f"protocol error: {error!r}")

    def _mark_disconnected(self, reason: str) -> None:
        with self._condition:
            self.state.disconnected = True
            if self.state.disconnect_reason is None:
                self.state.disconnect_reason = reason
            self._condition.notify_all()

    def _update(self, change: Callable[[BotState], None]) -> None:
        with self._condition:
            change(self.state)
            self._condition.notify_all()

    def _handle(self, packet_id: int, reader: Reader) -> None:
        phase = self.state.phase
        name = self._ids.clientbound_name(phase, packet_id)
        handler = getattr(self, f"_{phase}_{name}", None)
        if handler is not None:
            handler(reader)

    # Login -----------------------------------------------------------------------------------------

    def _login_login_compression(self, reader: Reader) -> None:
        self._compression = reader.varint()

    def _login_login_finished(self, reader: Reader) -> None:
        self._send("login", "login_acknowledged", b"")
        self._update(lambda s: setattr(s, "phase", "configuration"))

    def _login_login_disconnect(self, reader: Reader) -> None:
        self._update(lambda s: setattr(s, "disconnect_reason", reader.string()))

    def _login_custom_query(self, reader: Reader) -> None:
        transaction = reader.varint()
        self._send("login", "custom_query_answer", Writer().varint(transaction).boolean(False).bytes())

    def _login_cookie_request(self, reader: Reader) -> None:
        key = reader.string()
        self._send("login", "cookie_response", Writer().string(key).boolean(False).bytes())

    # Configuration ---------------------------------------------------------------------------------

    def _configuration_select_known_packs(self, reader: Reader) -> None:
        packs = [(reader.string(), reader.string(), reader.string()) for _ in range(reader.varint())]
        writer = Writer().varint(len(packs))
        for namespace, pack_id, version in packs:
            writer.string(namespace).string(pack_id).string(version)
        self._send("configuration", "select_known_packs", writer.bytes())

    def _configuration_finish_configuration(self, reader: Reader) -> None:
        self._send("configuration", "finish_configuration", b"")
        self._update(lambda s: setattr(s, "phase", "play"))

    def _configuration_keep_alive(self, reader: Reader) -> None:
        self._send("configuration", "keep_alive", Writer().long(reader.long()).bytes())

    def _configuration_ping(self, reader: Reader) -> None:
        self._send("configuration", "pong", Writer().int(reader.int()).bytes())

    def _configuration_code_of_conduct(self, reader: Reader) -> None:
        self._send("configuration", "accept_code_of_conduct", b"")

    def _configuration_cookie_request(self, reader: Reader) -> None:
        key = reader.string()
        self._send("configuration", "cookie_response", Writer().string(key).boolean(False).bytes())

    def _configuration_disconnect(self, reader: Reader) -> None:
        self._update(lambda s: setattr(s, "disconnect_reason", component_text(reader.nbt())))

    # Play ------------------------------------------------------------------------------------------

    def _read_spawn_info(self, reader: Reader) -> str:
        reader.varint()  # dimension type registry id
        return reader.string()  # dimension key

    def _play_login(self, reader: Reader) -> None:
        entity_id = reader.int()
        self._update(lambda s: setattr(s, "entity_id", entity_id))
        reader.boolean()
        for _ in range(reader.varint()):
            reader.string()
        reader.varint()
        reader.varint()
        reader.varint()
        reader.boolean()
        reader.boolean()
        reader.boolean()
        dimension = self._read_spawn_info(reader)
        self._enter_dimension(dimension, "login")
        self._update(lambda s: setattr(s, "play_entered", s.play_entered + 1))
        self._send("play", "player_loaded", b"")

    def _play_respawn(self, reader: Reader) -> None:
        self._update(lambda s: s.effects.clear())
        dimension = self._read_spawn_info(reader)
        self._enter_dimension(dimension, "respawn")
        self._update(lambda s: setattr(s, "respawns", s.respawns + 1))
        self._send("play", "player_loaded", b"")

    def _enter_dimension(self, dimension: str, via: str) -> None:
        def change(state: BotState) -> None:
            state.dimension = dimension
            state.dimensions.append(DimensionChange(dimension, via, time.monotonic()))

        self._update(change)

    def _play_game_event(self, reader: Reader) -> None:
        if reader.ubyte() != GAME_EVENT_WIN_GAME:
            return
        self._update(lambda s: setattr(s, "credits", s.credits + 1))
        # The vanilla client asks to respawn once the credits screen closes; this one skips them.
        self._send("play", "client_command", Writer().varint(0).bytes())

    def _play_update_mob_effect(self, reader: Reader) -> None:
        entity_id, effect = reader.varint(), reader.varint()
        if entity_id == self.state.entity_id:
            self._update(lambda s: s.effects.add(effect))

    def _play_remove_mob_effect(self, reader: Reader) -> None:
        entity_id, effect = reader.varint(), reader.varint()
        if entity_id == self.state.entity_id:
            self._update(lambda s: s.effects.discard(effect))

    def _play_boss_event(self, reader: Reader) -> None:
        bar, operation = str(reader.uuid()), reader.varint()

        def change(state: BotState) -> None:
            if operation == BOSS_ADD:
                title = component_text(reader.nbt())
                state.boss_bars[bar] = (title, reader.float())
            elif operation == BOSS_REMOVE:
                state.boss_bars.pop(bar, None)
            elif operation == BOSS_UPDATE_PROGRESS and bar in state.boss_bars:
                state.boss_bars[bar] = (state.boss_bars[bar][0], reader.float())

        self._update(change)

    def _play_keep_alive(self, reader: Reader) -> None:
        self._send("play", "keep_alive", Writer().long(reader.long()).bytes())

    def _play_ping(self, reader: Reader) -> None:
        self._send("play", "pong", Writer().int(reader.int()).bytes())

    def _play_player_position(self, reader: Reader) -> None:
        teleport_id = reader.varint()
        self._send("play", "accept_teleportation", Writer().varint(teleport_id).bytes())
        self._update(lambda s: setattr(s, "teleports", s.teleports + 1))

    def _play_chunk_batch_finished(self, reader: Reader) -> None:
        self._send("play", "chunk_batch_received", Writer().float(25.0).bytes())

    def _play_set_health(self, reader: Reader) -> None:
        health = reader.float()
        food = reader.varint()

        def change(state: BotState) -> None:
            state.health = health
            state.food = food

        self._update(change)

    def _play_system_chat(self, reader: Reader) -> None:
        text = component_text(reader.nbt())
        overlay = reader.boolean()
        self._update(lambda s: (s.action_bars if overlay else s.messages).append(text))

    def _play_start_configuration(self, reader: Reader) -> None:
        self._send("play", "configuration_acknowledged", b"")

        def change(state: BotState) -> None:
            state.phase = "configuration"
            state.reconfigurations += 1

        self._update(change)

    def _play_disconnect(self, reader: Reader) -> None:
        self._update(lambda s: setattr(s, "disconnect_reason", component_text(reader.nbt())))
