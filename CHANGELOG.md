# Changelog

## Unreleased

## 0.3.0

### Added

- Enable the shared lifecycle entrypoint on integrated-server hosts across all six supported Minecraft targets. A rendered 26.2 single-player smoke test through Soul Link passed through Terra Rasa activation.

### Changed

- Publish the library as client-and-server compatible so integrated-server hosts can install it in their client profile. LAN guests do not need the embedded library.

## 0.2.0

### Added

- Separate builds for Minecraft 1.21.11 and 26.1 through 26.3, with version-specific adapters for level construction, seeds, storage paths, lifecycle events, and mixins.

### Changed

- Maven artifacts now include the Minecraft target in their artifact ID so builds for different game versions can coexist.
- GitHub releases now bundle every supported target, and Modrinth publishes one target-specific version per jar with checksum-verified, retry-safe uploads.
- Add phase-specific preparation progress, including visible flush and finalization states, instead of reporting a static 97% during disk flushing.
- Bound final manifest publication separately from spawn generation and allow optional pregeneration to stop at its deadline without failing a prepared spawn.
- Let hosts update per-dimension pregeneration radii for preparations started afterward.
- Use a concurrent cache for Minecraft's shared jigsaw structure palettes so parallel chunk generation cannot crash while a prepared world and the active world generate together.
- Process distance-manager chunk updates from stable batches so status callbacks cannot invalidate the iterator or drop newly queued work.
- Preserve recoverable staging data during shutdown and let vanilla close its levels and tickets; resumed startup recovery handles interrupted preparation.
- Add a configurable FIFO pool of up to three durable prepared runs, adopted in creation order after restart.
- When C2ME's density-function compiler is present, skip diagnostic DOT graph generation during staged-level construction while keeping compiled density functions enabled; regular level construction still produces the graphs.

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
