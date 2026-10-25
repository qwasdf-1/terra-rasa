# Publishing to Modrinth

The GitHub release workflow builds the mod jar and creates a GitHub Release. The Modrinth workflow takes that same release jar, checks it against the attached SHA-256 file, and publishes it as a Modrinth version. The GitHub release notes become the Modrinth version changelog.

The workflow does not edit the Modrinth project page description, gallery, categories, or other project settings. Keep that project information up to date on Modrinth.

## One-time setup

1. Create and configure the Modrinth project. Set its source link to:
   https://github.com/qwasdf-1/terra-rasa
2. Create a Modrinth token with the VERSION_CREATE permission.
3. In this GitHub repository, add the Actions secret MODRINTH_TOKEN with that token.
4. For the first publish, add the Actions variable MODRINTH_PROJECT_URL with the project's full Modrinth URL, such as https://modrinth.com/mod/your-project-slug.
5. Run the Publish to Modrinth workflow from Actions and enter an existing release tag, such as v0.1.0.

The workflow resolves the URL through Modrinth, checks that the project links back to this GitHub repository, and prints the resolved project ID in the run summary. Then set the repository Actions variable MODRINTH_PROJECT_ID to that ID. Future publishes use the ID directly, even if the URL variable remains set. The ID remains stable if the project slug changes.

## Future releases

Push a version tag such as v0.2.0. The release workflow builds and attaches the jar and SHA-256 checksum. When the GitHub Release is published, the Modrinth workflow uploads the runtime jar with the matching Minecraft version, Fabric loader, dedicated-server environment, and Fabric API dependency.

If a GitHub Release already exists and needs to be published on Modrinth, run Publish to Modrinth manually with its tag. Each version number can only be published once on Modrinth.

## Checks and failures

The workflow stops if the tag does not match the mod version at that tag, if the expected runtime jar or checksum is missing, if the checksum fails, or if the Modrinth project's source URL points elsewhere. The Modrinth token is only used for version creation; project lookups are public API requests.
