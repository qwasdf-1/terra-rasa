# Project reference

Terra Rasa is a server-side Fabric library for preparing fresh seeded worlds
and replacing the loaded levels behind vanilla's canonical dimension keys.
Hosts decide which dimensions reset, how players move, and which session state
to clear.

## Supported targets

Terra Rasa builds a separate artifact for each exact Minecraft release. The
canonical target matrix is `gradle/minecraft-targets.json`; it pins Minecraft,
Java, Loom, Fabric Loader, and Fabric API. The matrix currently covers
1.21.11 and 26.1, 26.1.1, 26.1.2, 26.2, and 26.3. The 1.21.11 target uses the
legacy source overlay; the 26.x targets use the modern overlay.

## Public responsibilities

- `DimensionPolicy` classifies staging, persistent, resettable canonical, and
  external dimensions.
- `PinnedSeed` and `SeedOverride` keep service dimensions on the initial seed
  while each staged run uses its own seed.
- `WorldPreparationService` constructs staged levels, finds spawn, preloads and
  optionally pregenerates areas, then publishes a durable ready manifest.
- `CanonicalActivationService` retires old levels and promotes staged storage
  behind vanilla dimension keys, with rollback on ordinary failures.
- `StartupRecovery` uses the activation journal before vanilla opens dimension
  storage after an interrupted swap.
- `SessionReset` provides optional host-callable resets for vanilla-owned
  session/player state. It does not decide when a host resets or where players
  go.

## Where truth lives

| Need | Start here |
|---|---|
| Target toolchains | `gradle/minecraft-targets.json` |
| Host lifecycle and API usage | `host-integration.md` |
| Utility contracts, persistence, compatibility | `reference.md` |
| Test-host example | `testhost/` |
| Per-target release and Modrinth behavior | `publishing.md` |

The source and integration tests define actual behavior. A host must use the
library artifact matching its exact Minecraft target.
