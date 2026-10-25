"""Minimal Minecraft Java protocol primitives (protocol 776, Minecraft 26.2).

Packet ids are never hard-coded: they come from the vanilla data generator's ``packets.json`` report
for the exact server jar under test.
"""

from __future__ import annotations

import hashlib
import io
import json
import struct
import uuid
import zlib
from pathlib import Path
from typing import Any

PROTOCOL_VERSION = 776


class PacketIds:
    """Bidirectional packet-name/id lookup per protocol state, loaded from ``packets.json``."""

    def __init__(self, report: dict[str, Any]) -> None:
        self._by_name: dict[tuple[str, str, str], int] = {}
        self._by_id: dict[tuple[str, str, int], str] = {}
        for state, directions in report.items():
            for direction, packets in directions.items():
                for name, info in packets.items():
                    short = name.removeprefix("minecraft:")
                    self._by_name[(state, direction, short)] = info["protocol_id"]
                    self._by_id[(state, direction, info["protocol_id"])] = short

    @classmethod
    def load(cls, path: str | Path) -> "PacketIds":
        return cls(json.loads(Path(path).read_text(encoding="utf-8")))

    def serverbound(self, state: str, name: str) -> int:
        return self._by_name[(state, "serverbound", name)]

    def clientbound_name(self, state: str, packet_id: int) -> str:
        return self._by_id.get((state, "clientbound", packet_id), f"unknown_{packet_id}")


def offline_uuid(name: str) -> uuid.UUID:
    """Matches ``UUIDUtil.createOfflinePlayerUUID``: a version-3 UUID of ``OfflinePlayer:<name>``."""
    # auditor-ignore[insecure_cryptography]: vanilla's offline-mode UUID algorithm is defined as MD5; not a security use
    digest = bytearray(hashlib.md5(f"OfflinePlayer:{name}".encode("utf-8"), usedforsecurity=False).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return uuid.UUID(bytes=bytes(digest))


class Writer:
    def __init__(self) -> None:
        self._buffer = io.BytesIO()

    def varint(self, value: int) -> "Writer":
        value &= 0xFFFFFFFF
        while True:
            byte = value & 0x7F
            value >>= 7
            if value:
                self._buffer.write(bytes([byte | 0x80]))
            else:
                self._buffer.write(bytes([byte]))
                return self

    def string(self, value: str) -> "Writer":
        data = value.encode("utf-8")
        self.varint(len(data))
        self._buffer.write(data)
        return self

    def ushort(self, value: int) -> "Writer":
        self._buffer.write(struct.pack(">H", value))
        return self

    def long(self, value: int) -> "Writer":
        self._buffer.write(struct.pack(">q", value))
        return self

    def int(self, value: int) -> "Writer":
        self._buffer.write(struct.pack(">i", value))
        return self

    def float(self, value: float) -> "Writer":
        self._buffer.write(struct.pack(">f", value))
        return self

    def boolean(self, value: bool) -> "Writer":
        self._buffer.write(b"\x01" if value else b"\x00")
        return self

    def uuid(self, value: uuid.UUID) -> "Writer":
        self._buffer.write(value.bytes)
        return self

    def raw(self, data: bytes) -> "Writer":
        self._buffer.write(data)
        return self

    def bytes(self) -> bytes:
        return self._buffer.getvalue()


class Reader:
    def __init__(self, data: bytes) -> None:
        self._data = data
        self._offset = 0

    def remaining(self) -> int:
        return len(self._data) - self._offset

    def take(self, count: int) -> bytes:
        if self._offset + count > len(self._data):
            raise EOFError("packet underflow")
        chunk = self._data[self._offset:self._offset + count]
        self._offset += count
        return chunk

    def varint(self) -> int:
        result = 0
        for shift in range(0, 35, 7):
            byte = self.take(1)[0]
            result |= (byte & 0x7F) << shift
            if not byte & 0x80:
                return result - (1 << 32) if result & (1 << 31) else result
        raise ValueError("VarInt too long")

    def string(self) -> str:
        return self.take(self.varint()).decode("utf-8")

    def boolean(self) -> bool:
        return self.take(1) != b"\x00"

    def byte(self) -> int:
        return struct.unpack(">b", self.take(1))[0]

    def ubyte(self) -> int:
        return self.take(1)[0]

    def int(self) -> int:
        return struct.unpack(">i", self.take(4))[0]

    def long(self) -> int:
        return struct.unpack(">q", self.take(8))[0]

    def float(self) -> float:
        return struct.unpack(">f", self.take(4))[0]

    def double(self) -> float:
        return struct.unpack(">d", self.take(8))[0]

    def uuid(self) -> uuid.UUID:
        return uuid.UUID(bytes=self.take(16))

    def nbt(self) -> Any:
        """Reads one unnamed network NBT tag (used for chat components)."""
        tag_type = self.take(1)[0]
        return None if tag_type == 0 else self._nbt_payload(tag_type)

    def named_nbt(self) -> Any:
        """Reads one named root tag, the on-disk form (region chunks, .dat files)."""
        tag_type = self.take(1)[0]
        if tag_type == 0:
            return None
        self._nbt_string()
        return self._nbt_payload(tag_type)

    def _nbt_payload(self, tag_type: int) -> Any:
        read = _NBT_READERS.get(tag_type)
        if read is None:
            raise ValueError(f"unknown NBT tag type {tag_type}")
        return read(self)

    def _nbt_string(self) -> str:
        length = struct.unpack(">H", self.take(2))[0]
        return self.take(length).decode("utf-8", errors="replace")

    def _nbt_list(self) -> list[Any]:
        element_type = self.take(1)[0]
        return [self._nbt_payload(element_type) for _ in range(self.int())]

    def _nbt_compound(self) -> dict[str, Any]:
        compound: dict[str, Any] = {}
        while (child_type := self.take(1)[0]) != 0:
            name = self._nbt_string()
            compound[name] = self._nbt_payload(child_type)
        return compound


_NBT_READERS: dict[int, Any] = {
    1: Reader.byte,
    2: lambda r: struct.unpack(">h", r.take(2))[0],
    3: Reader.int,
    4: Reader.long,
    5: Reader.float,
    6: Reader.double,
    7: lambda r: r.take(r.int()),
    8: Reader._nbt_string,
    9: Reader._nbt_list,
    10: Reader._nbt_compound,
    11: lambda r: [r.int() for _ in range(r.int())],
    12: lambda r: [r.long() for _ in range(r.int())],
}


def component_text(component: Any) -> str:
    """Flattens a chat component (as decoded NBT) into plain text for assertions."""
    if component is None:
        return ""
    if isinstance(component, str):
        return component
    if isinstance(component, list):
        return "".join(component_text(part) for part in component)
    if isinstance(component, dict):
        text = component.get("text", "")
        if not text and "translate" in component:
            args = component.get("with", [])
            text = component["translate"] + ("(" + ",".join(component_text(a) for a in args) + ")" if args else "")
        return str(text) + "".join(component_text(part) for part in component.get("extra", []))
    return str(component)


def frame(packet_id: int, body: bytes, compression_threshold: int) -> bytes:
    payload = Writer().varint(packet_id).raw(body).bytes()
    if compression_threshold >= 0:
        if len(payload) >= compression_threshold:
            payload = Writer().varint(len(payload)).raw(zlib.compress(payload)).bytes()
        else:
            payload = Writer().varint(0).raw(payload).bytes()
    return Writer().varint(len(payload)).raw(payload).bytes()


def unframe(payload: bytes, compression_enabled: bool) -> tuple[int, Reader]:
    reader = Reader(payload)
    if compression_enabled:
        data_length = reader.varint()
        if data_length:
            reader = Reader(zlib.decompress(reader.take(reader.remaining())))
    return reader.varint(), reader
