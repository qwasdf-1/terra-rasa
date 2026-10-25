"""Minecraft RCON client that reassembles multi-packet responses."""

from __future__ import annotations

import socket
import struct
import threading

AUTH = 3
COMMAND = 2
RESPONSE = 0
MAX_PACKET = 1_048_576


class RconError(RuntimeError):
    pass


class Rcon:
    def __init__(self, host: str, port: int, password: str, timeout: float = 120.0) -> None:
        self._socket = socket.create_connection((host, port), timeout=timeout)
        self._socket.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self._lock = threading.Lock()
        self._next_id = 10
        self._send(1, AUTH, password)
        request_id, _, _ = self._read()
        if request_id != 1:
            raise RconError("RCON authentication failed")

    def command(self, command: str) -> str:
        """Runs one command and returns its full output.

        Minecraft splits long output into several packets. A trailing packet of an unknown type is answered
        only after the command finished, which marks the end of the response. Vanilla's RCON reader requires
        exactly one packet per socket read, so the sentinel is only sent once the first response arrived.
        """
        with self._lock:
            command_id = self._next_id
            sentinel_id = self._next_id + 1
            self._next_id += 2
            self._send(command_id, COMMAND, command)
            parts: list[str] = []
            request_id, _, payload = self._read()
            if request_id != command_id:
                raise RconError(f"unexpected RCON response id {request_id}")
            parts.append(payload)
            self._send(sentinel_id, RESPONSE, "")
            while True:
                request_id, _, payload = self._read()
                if request_id == sentinel_id:
                    return "".join(parts)
                if request_id == command_id:
                    parts.append(payload)

    def close(self) -> None:
        try:
            self._socket.close()
        except OSError:
            pass

    def _send(self, request_id: int, packet_type: int, payload: str) -> None:
        body = struct.pack("<ii", request_id, packet_type) + payload.encode("utf-8") + b"\0\0"
        self._socket.sendall(struct.pack("<i", len(body)) + body)

    def _read(self) -> tuple[int, int, str]:
        length = struct.unpack("<i", self._exact(4))[0]
        if length < 10 or length > MAX_PACKET:
            raise RconError(f"invalid RCON packet length {length}")
        data = self._exact(length)
        request_id, packet_type = struct.unpack("<ii", data[:8])
        return request_id, packet_type, data[8:-2].decode("utf-8", errors="replace")

    def _exact(self, count: int) -> bytes:
        data = bytearray()
        while len(data) < count:
            chunk = self._socket.recv(count - len(data))
            if not chunk:
                raise RconError("RCON connection closed")
            data.extend(chunk)
        return bytes(data)
