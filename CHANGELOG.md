# Changelog

## Unreleased

## 0.5.0

### Added

- Backport support for Minecraft 1.21.1 and 1.21.4 on both Fabric and NeoForge.
- Introduce `dev.terrarasa.ResourceIdentifier` neutral dimension wrapper in common sources, eliminating Mojang `Identifier` / `ResourceLocation` imports from `src/main`.
- Add `classic` overlay tier for 1.21.1 and 1.21.4 targeting legacy registry and level factory APIs with intermediary-safe dynamic reflection.

## 0.4.1

### Fixed

- Fabric builds now require the Fabric API build they were compiled against as a minimum
  (`fabric-api` of at least that version) instead of accepting any build at all. Newer builds for
  the same Minecraft version still work; older ones now produce a clear dependency error instead
  of a failed method call at runtime.
- The 26.3 Fabric build is compiled against Fabric API 0.165.2 (was 0.165.0), the newest published
  build for that version.

## 0.4.0

### Added

- Build for NeoForge beside Fabric, with one artifact per Minecraft version and loader. NeoForge
  builds cover 1.21.11 (NeoForge 21.11.48), 26.1.2 (26.1.2.126), 26.2 (26.2.0.93) and 26.3
  (26.3.0.101) through ModDevGradle 2.0.148; 26.1 and 26.1.1 stay Fabric-only because NeoForge
  publishes no stable build for them. The library API, the host-integration lifecycle and the
  unit tests are unchanged and loader-neutral.

### Fixed

- Register staged chunk ticket types through the loader's registry event, so preparation also
  works on NeoForge, where the registry is already frozen when a mod constructor runs.
- Let the FLUSHING preparation phase finish when the drain stops rather than waiting out a fixed
  sixty seconds. NeoForge keeps a staged chunk loaded after its ticket is released, so a swap
  that takes about four seconds on Fabric paid the whole wait; the same scenario now completes
  in about 4.4 seconds on NeoForge.
- Mark the saved-data storage mixin optional on NeoForge, which already writes NBT atomically
  and therefore has no target there.

## 0.3.2

### Added

- Let hosts pace separate staged-level constructions with a configurable tick interval, keeping the default at one level per tick.
- Report the server tick for each dimension constructed in the active preparation's diagnostics.

## 0.3.1

### Fixed

- Activate fresh Overworld data correctly on Minecraft 1.21.11 by swapping its region, entity, POI, and saved-data directories individually while preserving global files at the world root.
- Recover interrupted legacy storage swaps safely, and reject malformed activation journals and run manifests before they can direct filesystem changes.
- Bound and report preparation finalization failures, including timeouts while worlds are flushing to disk.

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
