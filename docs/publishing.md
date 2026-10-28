# Publishing

A version tag builds one jar for every supported Minecraft target and attaches all of them, plus a combined `SHA256SUMS`, to one GitHub Release. The Modrinth workflow publishes one version per attached jar. It uses the matching Minecraft version and a unique version number such as `0.3.1-mc1.21.11` or `0.3.1-mc26.3`.

The Modrinth workflow does not edit the project page description, gallery, categories, or other project settings.

## One-time setup

1. Create and configure the Modrinth project. Set its source link to `https://github.com/qwasdf-1/terra-rasa`.
2. Create a Modrinth token with `PROJECT_READ` and `VERSION_CREATE` permissions. `PROJECT_READ` lets the publisher resolve draft projects; `VERSION_CREATE` lets it upload versions.
3. In this GitHub repository, add the Actions secret `MODRINTH_TOKEN` with that token.
4. Add the Modrinth project ID as the Actions variable `MODRINTH_PROJECT_ID`.

The publisher looks up the configured ID and confirms that the project's source link points to this repository. It also resolves Fabric API's project ID from Modrinth's public API for each version's required dependency.

## Versioning and compatibility

The existing `v0.1.0` and `v0.2.0` versions remain `dedicated_server_only`; `v0.3.0` remains `client_and_server`. Starting with `v0.3.1`, new versions use Modrinth's `server_only` environment: Terra Rasa runs its functionality on the server and also supports integrated single-player servers. Bump `mod_version` before tagging; the tag must match the value in `gradle.properties`.

The release workflow builds and tests every target in [`gradle/minecraft-targets.json`](../gradle/minecraft-targets.json), creates one GitHub Release with every runtime jar, and dispatches the Modrinth publisher. Each Modrinth version is tied to exactly one Minecraft target and contains that jar, the shared release notes, Fabric Loader, the matching Fabric API dependency, and the environment declaration for that release. The workflow does not change project-level Modrinth settings; update the project environment separately when needed.

To publish an existing GitHub Release again, run **Publish to Modrinth** manually with its tag. The publisher derives the targets from the release's attached jars and verifies their checksums.

## Checks and failures

Publishing stops if the tag does not match `mod_version`, a target jar or checksum is missing, a checksum fails, the Modrinth project's source link points elsewhere, or Modrinth rejects an upload. The token lets the workflow resolve the project, including while it is a draft, and create versions. The publisher does not change project visibility; submit the draft for Modrinth review in project settings before expecting it to appear publicly. On retries, an existing target version is skipped only if its metadata and file hash match the GitHub release. Conflicting content fails instead of being overwritten.
