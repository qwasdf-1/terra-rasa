# Architecture decisions

| Decision | Current choice | Authority |
|---|---|---|
| Dimension identity | Promote fresh storage behind vanilla canonical keys instead of exposing staging dimensions to players. | Root README and `reference.md` |
| Host boundary | Library owns world lifecycle; host owns player flow, gameplay rules, and whether to reset session state. | `host-integration.md` |
| Staged level construction | Keep level construction synchronous on the server thread; default to one level per tick and let hosts increase the gap between separate constructors. This does not divide a single constructor's cost. | `host-integration.md`, `reference.md` |
| Recovery | Persist both a ready manifest and activation journal; reconcile interrupted activation before vanilla opens dimension storage. | `reference.md`, `StartupRecovery`, integration tests |
| Version compatibility | Build one artifact per exact Minecraft version and isolate API differences in legacy/modern overlays. | `gradle/minecraft-targets.json`, `src/versions/` |
| Chunky integration | Detect and adapt at runtime; do not add a mandatory compile-time dependency. | `host-integration.md`, compatibility adapter |

Update the matching source/contract and this page together when a choice
changes.
