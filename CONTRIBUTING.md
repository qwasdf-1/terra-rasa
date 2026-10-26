# Contributing

Thanks for helping improve Terra Rasa. Start with the [host lifecycle](README.md#host-lifecycle) and [host integration guide](docs/host-integration.md), then read [CanonicalActivationService](src/main/java/dev/terrarasa/CanonicalActivationService.java) and [WorldPreparationService](src/main/java/dev/terrarasa/WorldPreparationService.java), which hold the two halves of the lifecycle.

## Build And Test

Use Java 21 for Minecraft 1.21.11 or Java 25 for Minecraft 26.x. The exact dependency matrix is in [`gradle/minecraft-targets.json`](gradle/minecraft-targets.json). The integration suite also needs [uv](https://docs.astral.sh/uv/), which installs Python 3.14 for it.

```sh
./gradlew build             # library, test host and JUnit tests
./gradlew integrationTest   # real dedicated-server suite, about ten minutes
./gradlew integrationTest -Ppytest.args="-s tests/test_01_swap.py"   # one module
```

CI runs `./gradlew build` on every push and pull request; the integration suite runs on manual dispatch. Run it locally before opening a pull request that changes the lifecycle.

## Changes

- Behaviour a host can observe needs an integration test. Drive it through the [test host](testhost/src/main/java/dev/terrarasa/testhost/Host.java), which wires the library the way a game mod does, and assert through `/terrarasa status`. Each module fails on unexpected `ERROR` lines in the server log.
- A new step in the activation protocol needs a rollback path, a fault point in [FaultInjection](src/main/java/dev/terrarasa/FaultInjection.java), and a recovery test. Steps that touch the disk also need a case in [ActivationJournalTest](src/test/java/dev/terrarasa/ActivationJournalTest.java).
- Work on the server thread is bounded per tick. If a change adds work to preparation or pregeneration, report its worst step in the manifest's timings and keep the existing tick-time assertions passing.
- Add a compatibility adapter only for a mod that keeps its own `ServerLevel` references, through `CanonicalLevelEvents`, and keep it inert when the mod is absent.
- Record user-visible changes under `Unreleased` in [CHANGELOG.md](CHANGELOG.md).

## Licensing

Contributions are licensed under the Apache License 2.0.
