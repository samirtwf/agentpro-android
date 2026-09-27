# Vendored Linphone SDK

The `.aar` the app is built against, kept here on purpose so the build never contacts
`download.linphone.org`.

Upstream resolved `org.linphone` from their maven repository whenever no locally built SDK was
configured — which is the normal case — and `libs.versions.toml` asked for the version range
`5.5.+`. A range cannot be resolved from a local folder alone: gradle has to ask a repository
which version is newest, so **every build was a request to their server**, from whatever machine
happened to be building. It also meant two build paths could silently use different SDKs, which
is exactly what was happening: builds from the command line picked up 5.5.14-pre.4 out of
`~/.gradle`, while builds from the APK Builder picked up 5.5.16-pre.1 out of the bundled
`runtime/gradle` cache.

Now:

- `gradle/libs.versions.toml` pins an **exact** version, so nothing has to be looked up.
- `settings.gradle.kts` points at this folder, resolved from `settingsDir` so the project stays
  movable, and has **no remote fallback** — a missing SDK fails the build with a clear message
  instead of quietly fetching it.

## Moving to a newer SDK

1. Put the new `.aar` and `.pom` under
   `maven_repository/org/linphone/linphone-sdk-android/<version>/`.
2. Update `<version>` in `maven-metadata.xml`.
3. Change the `linphone = "..."` line in `gradle/libs.versions.toml` to match.

Nothing is downloaded at any point.

## Why this version

`5.5.14-pre.4+139bdf8bcb` is the build that was verified working against the customer's PBX.
`5.5.16-pre.1` was what the APK Builder's cache happened to hold; it is not pinned here because
it was never verified on hardware.

## Checking it really is local

Hide the cached copy and build offline — it must still succeed:

    mv runtime/gradle/caches/modules-2/files-2.1/org.linphone{,.hidden}
    gradlew assembleDebug --offline

`gradlew :app:dependencyInsight --dependency linphone-sdk-android --configuration debugRuntimeClasspath`
prints which repository answered.
