"""Reads Anvil region files (region/, entities/, poi/) to prove saved dimension data is intact.

Every chunk a region file points at must be inside the file, decompress, and parse as one complete NBT
compound. That is what "no corrupt region/POI/entity data" means on disk.
"""

from __future__ import annotations

import gzip
import struct
import zlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .protocol import Reader

SECTOR = 4096
COMPRESSION_GZIP = 1
COMPRESSION_ZLIB = 2
COMPRESSION_NONE = 3
EXTERNAL_FLAG = 128


@dataclass
class RegionReport:
    files: int = 0
    chunks: int = 0
    problems: list[str] = field(default_factory=list)


def read_chunks(region: Path) -> list[tuple[int, int, Any]]:
    """Returns (local x, local z, root compound) for every chunk stored in one region file."""
    data = region.read_bytes()
    if not data:
        return []
    if len(data) < 2 * SECTOR:
        raise ValueError(f"{region.name}: truncated header ({len(data)} bytes)")
    chunks = []
    for index in range(1024):
        entry = struct.unpack(">I", data[index * 4:index * 4 + 4])[0]
        offset, sectors = entry >> 8, entry & 0xFF
        if entry == 0:
            continue
        start = offset * SECTOR
        if offset < 2 or start + 5 > len(data) or (offset + sectors) * SECTOR > len(data) + SECTOR:
            raise ValueError(f"{region.name}: chunk {index} points outside the file")
        length, compression = struct.unpack(">IB", data[start:start + 5])
        if compression & EXTERNAL_FLAG:
            x, z = region_coordinates(region)
            external = region.with_name(f"c.{x * 32 + index % 32}.{z * 32 + index // 32}.mcc")
            payload = external.read_bytes()
            compression &= ~EXTERNAL_FLAG
        else:
            if length < 1 or start + 4 + length > len(data):
                raise ValueError(f"{region.name}: chunk {index} has length {length} past the end of the file")
            payload = data[start + 5:start + 4 + length]
        reader = Reader(decompress(payload, compression))
        root = reader.named_nbt()
        if not isinstance(root, dict):
            raise ValueError(f"{region.name}: chunk {index} is not a compound")
        chunks.append((index % 32, index // 32, root))
    return chunks


def decompress(payload: bytes, compression: int) -> bytes:
    if compression == COMPRESSION_ZLIB:
        return zlib.decompress(payload)
    if compression == COMPRESSION_GZIP:
        return gzip.decompress(payload)
    if compression == COMPRESSION_NONE:
        return payload
    raise ValueError(f"unsupported chunk compression {compression}")


def region_coordinates(region: Path) -> tuple[int, int]:
    _, x, z, _ = region.name.split(".")
    return int(x), int(z)


def verify_dimension(root: Path) -> RegionReport:
    """Parses every chunk under a dimension's region/, entities/ and poi/ folders."""
    report = RegionReport()
    for folder in ("region", "entities", "poi"):
        for region in sorted((root / folder).glob("r.*.*.mca")):
            report.files += 1
            try:
                report.chunks += len(read_chunks(region))
            except (ValueError, OSError, EOFError, zlib.error) as problem:
                report.problems.append(f"{folder}/{problem}")
    return report


def entities_with_tag(root: Path, tag: str) -> int:
    """Counts saved entities carrying a scoreboard tag."""
    count = 0
    for region in (root / "entities").glob("r.*.*.mca"):
        for _, _, chunk in read_chunks(region):
            count += sum(1 for entity in chunk.get("Entities", []) if tag in entity.get("Tags", []))
    return count


def chunk_statuses(root: Path) -> dict[tuple[int, int], str]:
    """Generation status of every chunk saved under a dimension's region/ folder, by chunk position."""
    statuses = {}
    for region in (root / "region").glob("r.*.*.mca"):
        rx, rz = region_coordinates(region)
        for x, z, chunk in read_chunks(region):
            statuses[(rx * 32 + x, rz * 32 + z)] = chunk.get("Status", "?")
    return statuses
