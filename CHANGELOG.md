# Changelog

## Unreleased

### Added

- Separate builds for Minecraft 1.21.11 and 26.1 through 26.3, with version-specific adapters for level construction, seeds, storage paths, lifecycle events, and mixins.

### Changed

- Maven artifacts now include the Minecraft target in their artifact ID so builds for different game versions can coexist.
- GitHub releases now bundle every supported target, and Modrinth publishes one target-specific version per jar with checksum-verified, retry-safe uploads.

## 0.1.0

The first public release, for Minecraft 26.2 on Fabric.

- Prepares freshly seeded dimensions in the background under staging keys, each with its own seed, and swaps them in as the canonical dimensions of a running dedicated server without reconnecting players.
- Journals every swap; failures roll back in process, and a crash at any point is reconciled at the next startup.
- Resets server-owned and per-player state to a new world's baseline (`SessionReset`).
- Keeps the world's original seed for dimensions that are not reset (`PinnedSeed`).
- Pregenerates a radius per dimension before a run starts, and continues in the live worlds after a start, resuming after restarts.
- Paces chunk generation by in-flight count and server load, with the spawn area ahead of pregeneration.
- `CanonicalLevelEvents` for mods that keep level references, with an adapter for Chunky.
- A Python test kit for real dedicated-server tests: server process, RCON, headless protocol clients and Anvil readers.
