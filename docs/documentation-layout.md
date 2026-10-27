# Documentation layout

| Content | Location |
|---|---|
| Product scope, targets, source map | `project-reference.md` |
| Present implementation status | `current-state.md` |
| Lifecycle and ownership | `architecture.md`, `host-integration.md` |
| Utility behavior and storage format | `reference.md` |
| Release setup and per-target artifacts | `publishing.md` |
| Testable integration example | `testhost/` |

Keep implementation statements tied to source, tests, target configuration, or
release artifacts. Keep host-owned decisions out of the library contract.
