# Terra Rasa

A Fabric library that gives a running dedicated server brand-new worlds without a restart. It prepares freshly seeded dimensions in the background, then swaps them in as the **real canonical dimensions** (`minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end`, plus any you opt in). Players stay connected throughout. Every step of a swap is journaled, so a crash at any point is reconciled at the next startup.

Because the new worlds are the canonical ones, vanilla handles everything that depends on them itself: portals, spawn selection, the End fight and its gateways, dimension advancements, `/seed`, and data packs that target `minecraft:overworld`. There are no per-feature shims.

Terra Rasa knows nothing about the game played in those worlds. A host mod decides when to swap, where players wait, and what a run is. [Soul Link](https://github.com/qwasdf-1/soul-link) is the first host.

## Versions

Minecraft **26.2** only (exact), Java 25, Fabric Loader 0.19.5, Fabric API 0.161.0+26.2, Loom 1.18.4. Server-side only; vanilla clients connect normally.

## Using it from a host mod

```groovy
dependencies {
    implementation "dev.terrarasa:terra-rasa:0.1.0"
    include "dev.terrarasa:terra-rasa:0.1.0"   // bundle it, so players install one jar
}
```

Until there is a public Maven repository, publish locally with `./gradlew publishToMavenLocal`, or use a Gradle composite build (`includeBuild('../terra-rasa')`), as Soul Link does.

A host must:

1. **Choose a [DimensionPolicy](src/main/java/dev/terrarasa/DimensionPolicy.java)**: the dimensions to reset (the Overworld always), and the persistent dimensions it owns, such as a lobby. Everything else is `EXTERNAL` and never touched. Pass it to [PinnedSeed](src/main/java/dev/terrarasa/PinnedSeed.java)`.setPolicy` in your mod initializer, so that dimensions that are not reset keep the world's original seed.
2. **Prepare** with [WorldPreparationService](src/main/java/dev/terrarasa/WorldPreparationService.java): `begin(server, seed, policy)`, then call `tick(server)` at the end of every server tick until `ready()` has a manifest. It needs a callback that moves a player out of a staged level, in case anyone ends up in one. `loadFromDisk` at startup adopts worlds prepared before a restart.
3. **Move every player out** of the dimensions being reset, into a persistent dimension of yours.
4. **Activate** with [CanonicalActivationService](src/main/java/dev/terrarasa/CanonicalActivationService.java)`.activate(server, takeReady(), policy)`, at the end of a server tick. On a `LifecycleException` the swap was rolled back and the old worlds are still live.
5. **Reset session state** with [SessionReset](src/main/java/dev/terrarasa/session/SessionReset.java) as your game requires (`resetServerState`, `resetPlayer`), then move players into the new worlds.

The [test host](testhost/src/main/java/dev/terrarasa/testhost/Host.java) is a complete minimal host in under 200 lines.

Mods that keep their own `ServerLevel` references follow swaps through [CanonicalLevelEvents](src/main/java/dev/terrarasa/CanonicalLevelEvents.java). `BEFORE_RETIRE` can veto a swap; `REPLACED` reports the new levels. Terra Rasa ships an adapter for Chunky.

## How a swap works

1. **Prepare:** build plain vanilla levels under staging keys `terrarasa:stage/<run>/<ns>/<path>`, each with its own seed ([SeedOverride](src/main/java/dev/terrarasa/SeedOverride.java)). Run vanilla's initial-spawn search, preload around the spawn, then save and fully close everything. The work is spread over ticks:
   - Chunk generation is paced by in-flight count and server load.
   - Staged chunks load to full status but never tick.
   - An empty server stays awake until preparation finishes.

   A durable manifest marks the run `CLOSED_READY`.
2. **Activate:** with everyone out, one synchronous server-thread section at the end of a tick does the following:
   - Retires the old canonical levels (drain, save, close).
   - Journals and renames directories: old data to quarantine, staged data into `dimensions/minecraft/...`.
   - Rebinds the server-global seed.
   - Constructs new vanilla `ServerLevel`s exactly as `createLevels()` does, and validates them.

   Any failure rolls back in process. A crash is reconciled from the [journal](src/main/java/dev/terrarasa/ActivationJournal.java) at the next startup, before vanilla opens any dimension.
3. **Reset session state** (host's choice):
   - On the live objects: scoreboard, teams, command storage, boss bars, scheduled functions, random sequences, weather, clocks and the wandering-trader timer. Then `#minecraft:load` runs again, so data packs set themselves up as in a new world.
   - Per player: inventory, ender chest, XP, effects, attributes, tags, respawn point, recipes, advancements, statistics and "seen the credits".

### Pregeneration

A host can give `WorldPreparationService` a radius in blocks per dimension. Once the spawn area is ready, preparation enters `PREGENERATING` and generates that area in the background:
- **Where:** a circle around the run spawn, or around the spawn scaled 1:8 for the Nether, or around the main island for the End. Nearest chunks come first.
- **How far:** each chunk goes to the `spawn` status, which includes terrain, structures, features, light and initial mobs. Vanilla does the last step, becoming a live chunk, on the server thread when a player first loads the chunk. Most pregenerated chunks are never visited, so that step is skipped for them.
- **Memory and IO:** the area is generated in 16×16-chunk tiles. Generating a chunk also loads partly generated neighbours about ten chunks out, so a tile keeps its finished chunks loaded until the whole tile is done, and the chunks in it share those neighbours. Then the tile is released a few chunks per tick, and vanilla saves and unloads it in spare time. Memory stays flat however large the area is. Releasing each chunk as soon as it was done cost about 70 region writes per chunk; with tiles it is about 13.

The pace is shared with the spawn area, which always goes first:
- At most 6 chunks generate at once.
- Nothing new starts while the server's smoothed tick time is over 30 ms.
- Up to 16 chunks generate at once while nobody is online, and an empty server stays awake until the work is done.

Give the server at least 4 GB of heap when pregeneration is on. On 2 GB with ZGC, pregeneration plus players exploring a freshly swapped world outruns the collector. ZGC then stalls allocating threads, the server thread included for about 35 ms, and a few ticks run to about 70 ms over a few minutes. On 4 GB there were no stalls.

A start never waits for pregeneration. `finishSoon()` stops it and readies the run in a few seconds. Whatever is left continues in the live worlds after the swap, at the same pace. That progress is saved in `pregeneration.json`, so it resumes after a restart. A restart while the next run is still being prepared discards that preparation, as before.

The library's files live in `<world>/terrarasa/`: the journal, stage manifests, the pinned seed, live pregeneration progress and quarantine.

## Building and testing

```sh
./gradlew build                # library and test host; JUnit covers the journal at every crash point
./gradlew integrationTest      # real dedicated-server suite (needs uv and Python 3.14)
./gradlew publishToMavenLocal  # dev.terrarasa:terra-rasa for hosts built against a release
```

`integrationTest` assembles a production-style server in `build/integration/server`: the official Fabric server launcher, the pinned vanilla jar, Fabric API, the library and the test host. The test host is a minimal host mod that exposes each step as a `/terrarasa` command. The suite covers:
- Swaps without reconnects.
- Isolation of staged worlds.
- Pinned seeds.
- Cleanup after commit.
- Leak checks.
- Tasks a level queues while it is retired: POI updates from block changes run after the swap closed the level, and are ignored instead of reading its closed storage.
- Rollback at every activation phase.
- Real JVM halts in the middle of a swap.
- Pregeneration: before the run is ready, cut short by a start and continued live, resumed after a restart, and the chunk statuses on disk.

The Python test kit in [testing/terra_rasa_testkit](testing/terra_rasa_testkit) provides the server process, RCON, headless protocol clients that behave like vanilla players, and Anvil region readers. Host mods reuse it: subclass `ServerProcess` and point it at your config file and status command.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Changes are listed in [CHANGELOG.md](CHANGELOG.md).

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
