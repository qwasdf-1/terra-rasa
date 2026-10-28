# Library reference

## Public utilities

| Utility | Responsibility |
|---|---|
| [`DimensionPolicy`](../src/main/java/dev/terrarasa/DimensionPolicy.java) | Classifies dimensions as staging, persistent service, resettable canonical, or external. The Overworld is always first in the reset order. `validate(...)` checks registered stems and conflicting policy entries. |
| [`PinnedSeed`](../src/main/java/dev/terrarasa/PinnedSeed.java) | Keeps persistent and external dimensions on the initial world seed while the server-global seed changes for each run. Call `setPolicy(...)` before world levels open. |
| [`SeedOverride`](../src/main/java/dev/terrarasa/SeedOverride.java) | Scopes a run seed to construction of one staged dimension, so preparation does not change the live server's global seed. It is used by the preparation service. |
| [`WorldPreparationService`](../src/main/java/dev/terrarasa/WorldPreparationService.java) | Creates isolated staged levels, discovers vanilla's initial spawn, preloads it, optionally pregenerates areas, flushes and closes levels, and writes a durable manifest. It manages a FIFO queue of up to three prepared runs. Use `setReadyLimit`, `readyRuns`, `begin`, `tick`, `takeReady`, `finishSoon`, `loadFromDisk`, and `fail` to drive it. |
| [`CanonicalActivationService`](../src/main/java/dev/terrarasa/CanonicalActivationService.java) | Replaces the loaded levels behind canonical keys. `activate(...)` returns the run id, seed, spawn, and phase timings; failures are rolled back and surfaced as `LifecycleException`. |
| [`LevelRetirement`](../src/main/java/dev/terrarasa/LevelRetirement.java) | Drains chunk work, saves, and closes an empty level before its storage is moved; late POI writes to a closed level are ignored. The activation service uses it for each retirement. |
| [`SessionReset`](../src/main/java/dev/terrarasa/session/SessionReset.java) | Resets server-owned run state and a connected player's state through live objects. It does not choose when a host should reset or where players should go. |
| [`CanonicalLevelEvents`](../src/main/java/dev/terrarasa/CanonicalLevelEvents.java) | Veto and replacement notifications for mods that retain level object references. |
| [`StageKeys`](../src/main/java/dev/terrarasa/StageKeys.java) | Creates collision-free staging identities and reads the owning run id from one. Staging identities are temporary and must not be used as player-facing dimension ids. |
| [`WorldPaths`](../src/main/java/dev/terrarasa/WorldPaths.java) | Resolves Terra Rasa metadata and vanilla dimension directories under the server's world root; rejects relative paths that escape that root. |
| [`AtomicFiles`](../src/main/java/dev/terrarasa/AtomicFiles.java) | Writes files completely through synced temporary files and atomic renames; moves directories atomically and refuses to copy if atomic promotion is unavailable. |

`RunManifest` describes a prepared run and its dimensions, seed, spawn, progress, and timings. `RegistryFingerprint` ensures a prepared run is only reused with the same Minecraft version, selected data packs, structure setting, and resettable level definitions. `StartupRecovery` runs automatically before vanilla opens levels and reconciles an interrupted activation from its `ActivationJournal`. Live pregeneration is also owned and ticked automatically by the library; hosts configure its per-dimension radii when constructing `WorldPreparationService` or with `setPregenerationRadii(...)` for preparations begun afterward.

## Preparation and pregeneration

Preparation uses `terrarasa:stage/<run-id>/<namespace>/<dimension-path>` identities and separate storage directories. Staged dimensions have the configured level stems and their own run seed; they are not replacements for canonical levels until activation. They never contain normal players, and their loaded chunks do not tick entities or blocks.

The preparation stages are:

1. Construct staged levels.
2. Run vanilla's initial-spawn search, examining loaded chunks in the vanilla spiral order.
3. Preload the configured chunk radius around the spawn.
4. Optionally pregenerate additional circular areas to the `spawn` chunk status.
5. Save and close all staged levels, then publish the `CLOSED_READY` manifest.

The constructor's `preloadRadius` is measured in chunks. Additional pregeneration radii are measured in blocks and keyed by resettable dimension. Areas are centered on the prepared spawn, scaled into the Nether, or centered on the End's fixed main island. The spawn area has priority. With players online, Terra Rasa runs at most six chunks at once; with an empty server, at most sixteen. When smoothed tick time exceeds 30 ms it pauses new work except for one chunk when needed to keep progress moving. An empty server stays awake until preparation or live pregeneration finishes. `progressDescription()` reports the active phase (and five-point pregeneration increments) so hosts do not display the coarse 97% flush value as if preparation were stuck.

If a start arrives during pregeneration, `finishSoon()` leaves the prepared spawn ready first. Remaining work transfers to the live canonical levels and is saved in `<world>/terrarasa/pregeneration.json` every few seconds and at shutdown; it resumes after restart. For large default areas, allow at least 4 GB of server heap.

If the server stops during preparation, Terra Rasa flushes its manifest writes and leaves the registered staging levels and their tickets for vanilla's normal level shutdown. It keeps the interrupted staging data so startup recovery can clean it up safely; shutdown must not release those tickets or delete their data while the levels are closing.

## Activation and recovery

Activation runs on the server thread between ticks. It verifies preconditions, drains and closes the old levels, records each directory move in `active-journal.json`, moves old data into quarantine, promotes staged directories, rebinds the global seed, and constructs ordinary vanilla `ServerLevel` instances under the canonical keys. It validates that each key resolves to the replacement and that the prepared spawn chunk loads before marking the journal committed.

Minecraft 1.21.11 stores the Overworld at the world root, alongside global files such as `level.dat` and player data. For that legacy layout, activation journals and swaps only the Overworld storage folders `region`, `entities`, `poi`, and `data`; root metadata and player files stay in place. Newer versions use a separate dimension directory and continue to swap the complete directory. This keeps the 1.21.11 world root in place while still giving each run fresh dimension-owned storage.

Ordinary failures roll back to the prior levels and seed. If the process stops mid-swap, Terra Rasa reads and validates every journaled path and run label before vanilla opens dimension storage, then completes either rollback or commit according to the last durable phase. Legacy root-folder swaps recover each of the four storage folders from that same journal. Malformed or path-escaping journal data stops startup before recovery touches files. On success, the old world data and stage metadata are deleted asynchronously after commit. The server's filesystem must support atomic directory renames within the world root; activation checks this before making changes.

## `SessionReset`

`resetServerState(server)` clears the state commonly owned by a world session:

- Scoreboard objectives, display slots, and teams; command storage; custom boss bars; scheduled functions; random sequences.
- Weather and the wandering-trader timer. On Minecraft 26.x it also resets world clocks and synchronizes them to connected players; 1.21.11 has no world-clock state to reset.
- Data-pack setup through a fresh `#minecraft:load` run on the next function tick.

`resetPlayer(server, player, gameMode)` clears inventory and ender chest, XP, effects and attribute modifiers, health, absorption, hunger, saturation, fire, freezing, air, fall distance and motion, entity tags, respawn and last-death locations, recipes, advancements, statistics, scoreboard scores, ender pearls, vehicles, and the “seen credits” state. It also closes containers and synchronizes the changes to the client.

Game rules, difficulty, operator and whitelist state, host-owned state, and saved map data are preserved. A host that needs any of those reset must handle them itself.

## Extension and compatibility notes

- Every replacement creates new `ServerLevel` objects, including a rollback that reconstructs old data. A mod holding a level reference must release or rebind it on `REPLACED`.
- `BEFORE_RETIRE` is the last safe point to cancel a swap while old levels are still live.
- Chunky is detected at startup without a compile-time dependency. Active tasks targeting retired dimensions are canceled; a selection for a replaced dimension is rebound. If Chunky's API changes, Terra Rasa refuses activation when it cannot safely cancel a task.
- When C2ME's density-function compiler is present, Terra Rasa skips its diagnostic DOT graph generation only while constructing a staged level. The compiler still generates and uses compiled density-function classes; ordinary level construction still writes the graphs. This optional mixin targets a private C2ME call site and uses `require = 0`, so a C2ME change disables the optimization without blocking startup. With C2ME 0.4.2-alpha.0.60 on 26.2 and the pinned 21-mod reference pack, the same-seed staged-construction sample fell from 2.9 s total / 2.36 s worst step to 1.52 s / 1.11 s; compiled-class output increased and graph-file count stayed unchanged during staging. The two runs selected different spawn coordinates, so their full preparation times are not comparable.
- Only configured resettable dimensions are replaced. Persistent and unknown dimensions keep their level data and pinned seed; runtime-added dimensions are not implicitly enrolled.
- The library targets Minecraft 1.21.11 and 26.1 through 26.3, with a separate build for each exact version. Version 0.3.0 introduced dedicated and integrated-server support; 0.3.1 fixes activation of legacy root-level Overworld storage on 1.21.11. LAN guests do not need this embedded library. A rendered 26.2 integrated single-player smoke through Soul Link passed; standalone library lifecycle verification on all targets remains pending. See [`gradle/minecraft-targets.json`](../gradle/minecraft-targets.json) for Java, Loom, Loader and Fabric API versions.
- The 1.21.11 build uses the legacy source overlay; the 26.x builds use the modern overlay. Compatibility fixes must be checked against both families when they touch shared API boundaries.

## World files

Terra Rasa's metadata is stored under `<world>/terrarasa/`:

| File or directory | Purpose |
|---|---|
| `active-journal.json` | Durable phase and directory moves for the current or most recent activation. |
| `pinned-seed.json` | Original seed used by persistent and external dimensions. |
| `pregeneration.json` | Remaining pregeneration in live dimensions. |
| `stages/<run-id>/manifest.json` | Prepared run metadata and generation context. |
| `quarantine/` | Temporary location for the old dimension data during activation; removed after commit or recovery. |
