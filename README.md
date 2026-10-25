# Terra Rasa

Terra Rasa is a Fabric library for replacing a dedicated server's worlds while the server keeps running. It prepares fresh, seeded dimensions in the background, then promotes them under their **real canonical dimension keys**. Players stay connected, and vanilla continues to see `minecraft:overworld`, `minecraft:the_nether`, and `minecraft:the_end` as the active worlds.

That canonical identity is the key difference from temporary-world or dimension-alias approaches: vanilla itself continues to own portal travel, spawn selection, the End fight and gateways, credits, `/seed`, advancements, and data packs that target those dimensions. Terra Rasa replaces the levels behind the keys; it does not emulate each feature around a temporary dimension.

## What the library provides

| Utility | What it does |
|---|---|
| `DimensionPolicy` | Declares which dimensions are replaced, which service dimensions persist, and leaves every other dimension untouched. |
| `PinnedSeed` | Keeps persistent and external dimensions on the world's original seed while resettable dimensions use the new world seed. |
| `WorldPreparationService` | Builds fresh levels under isolated staging identities, finds the vanilla spawn, preloads it, optionally pregenerates configured areas, and publishes a durable ready manifest. |
| Live pregeneration | Paces the remaining area after activation, records progress, and resumes it after a restart. The host configures per-dimension radii through `WorldPreparationService`. |
| `CanonicalActivationService` | Drains old levels, promotes staged world data, constructs new vanilla levels under canonical keys, and rolls back on failure. |
| Startup recovery | Uses the activation journal to finish or undo an interrupted swap before vanilla opens dimension storage. |
| `SessionReset` | Resets vanilla's server-owned and per-player run state through live server objects. Hosts choose whether and when to call it. |
| `CanonicalLevelEvents` | Lets mods that cache level objects veto retirement or rebind to replacement levels. A Chunky adapter is included and loaded only when Chunky is present. |

The host mod owns the game rules, player flow, and the decision to reset session state. See [Host integration](docs/host-integration.md) for the lifecycle and [Library reference](docs/reference.md) for the utilities and storage model.

## Requirements

Minecraft **26.2** (exact), Java 25, Fabric Loader 0.19.5, Fabric API 0.161.0+26.2. This is a dedicated-server library; clients can remain vanilla.

## Add it to a host mod

There is not yet a public Maven repository. Publish the library to your local Maven repository:

```sh
./gradlew publishToMavenLocal
```

Then add `mavenLocal()` and the dependency to the host's Gradle build. With Fabric Loom, include the library in the host mod's jar so server owners install one mod:

```groovy
repositories {
    mavenLocal()
}

dependencies {
    implementation "dev.terrarasa:terra-rasa:0.1.0"
    include "dev.terrarasa:terra-rasa:0.1.0"
}
```

## Host lifecycle

1. During mod initialization, register a `DimensionPolicy` with `PinnedSeed.setPolicy(...)`, before the server opens world levels.
2. When the server starts, create `WorldPreparationService` and `CanonicalActivationService`, then call `loadFromDisk(...)` to adopt a complete prepared run from a previous session.
3. Start preparation with `begin(server, seed, policy)` and call `tick(server)` at the end of every server tick. A run is ready only after all staged levels are saved and closed; `ready()` exposes its manifest. `finishSoon()` lets a host favor a requested start over optional pregeneration.
4. Move every player out of resettable dimensions. Activate the manifest with `CanonicalActivationService.activate(...)` on the server thread.
5. If desired, reset server and player state with `SessionReset`, then move players into the new worlds. Activation failures are rolled back and reported as `LifecycleException`.

The [test host](testhost/src/main/java/dev/terrarasa/testhost/Host.java) is a compact working integration example. See [Host integration](docs/host-integration.md) for dimension policy setup, tick handling, and level-cache listeners.

## Build and test

```sh
./gradlew build
./gradlew integrationTest  # real dedicated server; requires uv and Python 3.14
```

The integration suite exercises world swaps, recovery, player continuity, seed handling, and pregeneration on a real Fabric server. The test host and Python server test kit are included in this repository.

## Documentation

- [Documentation map](docs/README.md)
- [Host integration](docs/host-integration.md)
- [Library reference](docs/reference.md)
- [Publishing to Modrinth](docs/publishing.md)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
