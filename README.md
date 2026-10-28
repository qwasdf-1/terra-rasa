# Terra Rasa

Terra Rasa is a Fabric library for replacing dedicated or integrated server worlds while the server keeps running. It prepares fresh, seeded dimensions in the background, then promotes them under their **real canonical dimension keys**. Players stay connected, and vanilla continues to see `minecraft:overworld`, `minecraft:the_nether`, and `minecraft:the_end` as the active worlds.

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

Terra Rasa provides a separate build for each supported Minecraft version:

| Minecraft | Java | Fabric Loom | Fabric API |
|---|---:|---:|---|
| 1.21.11 | 21 | 1.17.21 | 0.141.6+1.21.11 |
| 26.1 | 25 | 1.18.4 | 0.145.1+26.1 |
| 26.1.1 | 25 | 1.18.4 | 0.145.4+26.1.1 |
| 26.1.2 | 25 | 1.18.4 | 0.155.3+26.1.2 |
| 26.2 | 25 | 1.18.4 | 0.161.0+26.2 |
| 26.3 | 25 | 1.18.4 | 0.165.0+26.3 |

Each build uses Fabric Loader 0.19.5. The full target list is maintained in [`gradle/minecraft-targets.json`](gradle/minecraft-targets.json), and CI builds every target. Terra Rasa 0.3.0 introduced support for dedicated servers and integrated single-player/LAN hosts across the listed targets. Version 0.3.1 fixes legacy Overworld activation on Minecraft 1.21.11. A rendered 26.2 single-player smoke test through Soul Link passed; vanilla LAN guest login remains unverified. The host needs Terra Rasa installed in the client profile for integrated play; remote LAN guests can remain vanilla.

## Add it to a host mod

There is not yet a public Maven repository. Publish the library to your local Maven repository:

```sh
./gradlew publishToMavenLocal
```

Then add `mavenLocal()` and the Minecraft-specific artifact to the host's Gradle build. With Fabric Loom, include the library in the host mod's jar so server owners install one mod. For example, the 26.3 build is:

```groovy
repositories {
    mavenLocal()
}

dependencies {
    implementation "dev.terrarasa:terra-rasa-mc26.3:0.1.0"
    include "dev.terrarasa:terra-rasa-mc26.3:0.1.0"
}
```

Build and publish the matching variant by selecting its Minecraft, Loom, Fabric API, Loader and Java versions from the target matrix. For example, to publish the 26.3 build locally:

```sh
./gradlew publishToMavenLocal \\
  -Pminecraft_version=26.3 \\
  -Ploom_version=1.18.4 \\
  -Pfabric_loader_version=0.19.5 \\
  -Pfabric_api_version=0.165.0+26.3 \\
  -Pjava_version=25
```

For Minecraft 1.21.11, use Fabric API `0.141.6+1.21.11`, Loom `1.17.21`, and Java 21 instead. Do not use a jar built for a different Minecraft version. Each target has its own Maven artifact ID.

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
