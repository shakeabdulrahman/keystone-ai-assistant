# Releasing

Nothing has been published. These decisions are needed before the first release.

## Decisions needed

1. **License.** None is chosen; the repository has no LICENSE file. Without one, others can't
   legally use the code. Apache-2.0 is common for Kotlin libraries, but it's your call.
2. **Coordinates.** Currently group `io.github.shakeabdulrahman`, artifacts
   `keystone-llm-core`, `-ktor`, `-openai`, `-gemini`, `-ondevice`, and package
   `io.github.shakeabdulrahman.keystone.llm`. A `io.github.<user>` namespace on Maven Central
   requires verifying the GitHub account in the Central Portal.
3. **Separate repository or not.** `sdk/` is self-contained except for the version catalog
   it reads from `../mobile`. Splitting it out means copying that catalog.

## Versioning

- [Semantic Versioning](https://semver.org). In `0.x`, minor versions may break the API.
  Breaking changes are listed under **Changed** or **Removed** in the changelog.
- The version lives in `gradle.properties` (`keystoneLlmVersion`). Snapshots end in `-SNAPSHOT`.
- `CHANGELOG.md` follows [Keep a Changelog](https://keepachangelog.com).

## API compatibility

- `explicitApi()` is on, so every public declaration is deliberate.
- `@InternalKeystoneLlmApi` marks cross-module internals (opt-in required, level ERROR).
- **Not yet configured:** a binary-compatibility validator. Kotlin's
  `org.jetbrains.kotlinx.binary-compatibility-validator` (with KLib support) would add
  `apiDump`/`apiCheck`. Its version couldn't be verified when this was written, so add it
  together with the first Gradle build and commit the `.api` dumps. Until then there is no
  binary-stability guarantee.

## Publishing (when the above is settled)

`gradle/publishing.gradle.kts` sets POM metadata only. Still missing:

- signing (`signing` plugin with an in-memory GPG key from CI secrets);
- an upload path: the Central Portal (for example via the `com.vanniktech.maven.publish` plugin,
  version to be confirmed) or a staging repository;
- the license in the POM.

Credentials belong in CI secrets or `~/.gradle/gradle.properties`, never in this repository.
`./gradlew publishToMavenLocal` works without any of this.
