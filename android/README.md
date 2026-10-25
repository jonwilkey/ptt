# android/

The family push-to-talk Android app (Kotlin, Gradle), distributed as a signed, sideloaded APK.

This is the project skeleton: an empty "Not set up yet" screen, the build, static analysis, and
CI. Enrollment and paging (milestone 2) come next; the widget, live talk, and clip fallback
follow in milestones 3-5. `version.txt` holds the app version that release-please bumps (tags
`android-vX.Y.Z`). Release signing is not configured yet (milestone 6), so release builds stay
unsigned.

## Layout

| Path | Purpose |
| --- | --- |
| `app/` | The single `:app` module (`com.example.ptt`; application id `com.example.ptt`) |
| `app/src/main/kotlin/com/example/ptt/` | Packages named as in the spec (`push`, `setup`, ...), created as needed |
| `app/src/debug/res/xml/` | Debug network security config (adds cleartext to `10.0.2.2` and `localhost`) |
| `buildSrc/` | `AppVersion`: parses `version.txt` and computes `versionCode`, with unit tests |
| `gradle/libs.versions.toml` | Version catalog: every dependency and plugin version lives here |
| `config/detekt/detekt.yml` | detekt deviations from the defaults |
| `gradle.lockfile` files, `gradle/verification-metadata.xml`, `buildSrc/gradle/verification-metadata.xml` | Dependency locking and verification |

`versionName` is `version.txt`; `versionCode` is `major*1_000_000 + minor*1_000 + patch`
(minimum 1, because Android rejects 0). A malformed `version.txt` fails the build.

Security-relevant configuration: `allowBackup="false"` with data-extraction rules that exclude
everything, and a network security config that forbids cleartext and trusts only ISRG Root X1
and X2 (the certificates are in `res/raw`, fingerprints in the config's comment).
The `FirebaseInitProvider` is removed and Firebase's auto-init and default data collection are off.

## Local setup

- JDK 21 (`JAVA_HOME`).
- Android SDK with platform `android-37.2` and build-tools `37.0.0` (`ANDROID_HOME`, or
  `sdk.dir` in a gitignored `local.properties`).
- Use the wrapper: `./gradlew` (Gradle 9.8.2, checksum-pinned in
  `gradle/wrapper/gradle-wrapper.properties`).

## Checks

Run from `android/`; this is what CI runs:

```sh
./gradlew -p buildSrc test      # version-code logic
./gradlew ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug
```

`./gradlew ktlintFormat` fixes style issues. Android Lint treats warnings as errors.
Robolectric tests run on API 36 (`app/src/test/resources/robolectric.properties`) because the
Robolectric release in use does not support the API 37 sandbox yet. They run offline against a
Gradle-resolved, checksum-verified `android-all` jar (see below).

## Firebase configuration

The app has no `google-services` plugin or `google-services.json`. Put these in
`~/.gradle/gradle.properties` (or export the `PTT_FIREBASE_*` variables); placeholders shown:

```properties
ptt.firebase.project-id=example-ptt
ptt.firebase.app-id=1:000000000000:android:0000000000000000
ptt.firebase.api-key=EXAMPLE_API_KEY
ptt.firebase.sender-id=000000000000
```

Without them the build still works and the app logs that FCM is unavailable. Where the values
come from: `docs/runbooks/firebase-setup.md`, step 7.

## Dependency locking and verification

Every configuration is locked (`app/gradle.lockfile`, `buildSrc/gradle.lockfile`,
`buildscript-gradle.lockfile`), which is what OSV-Scanner reads in CI, and every downloaded
artifact must match a checksum in `gradle/verification-metadata.xml`. A build fails if either is
out of date, so **after any dependency, plugin, or Gradle version change** (including each
Renovate monthly PR, which needs this step pushed to its branch) refresh both:

```sh
cd android
# Always use an EMPTY Gradle home: with a warm cache Gradle skips artifacts it already has, and
# the metadata comes out incomplete (CI, which starts cold, then fails).
GH="$(mktemp -d)"
FLAGS="--gradle-user-home $GH --no-daemon --no-configuration-cache --no-build-cache --rerun-tasks"
TASKS="ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug assembleRelease"
# 1. Remove stale checksums and locks (write mode must not see them).
rm gradle/verification-metadata.xml buildSrc/gradle/verification-metadata.xml
find . -name '*.lockfile' -not -path '*/build/*' -delete
# 2. Checksums, then locks.
./gradlew $FLAGS --write-verification-metadata sha256 $TASKS
./gradlew $FLAGS $TASKS --write-locks
# buildSrc is a separate build with its own verification file: use ANOTHER empty Gradle home.
rm buildSrc/gradle/verification-metadata.xml buildSrc/gradle.lockfile
./gradlew --gradle-user-home "$(mktemp -d)" --no-daemon --no-configuration-cache -p buildSrc test --rerun-tasks --write-locks \
  --write-verification-metadata sha256
# 3. Confirm with a second empty Gradle home, strict, exactly as CI runs it. An empty
# ANDROID_USER_HOME also forces AGP to generate a debug keystore, as on a CI runner.
GH="$(mktemp -d)"
export ANDROID_USER_HOME="$(mktemp -d)"
./gradlew --gradle-user-home "$GH" --no-daemon -p buildSrc test
./gradlew --gradle-user-home "$GH" --no-daemon ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug
```

Review the diff: only the changed dependencies' entries should move. The lock files are
written from the first resolution of each configuration. AGP resolves the runtime classpaths a
second time with Kotlin "common" attributes, which adds `kotlin-stdlib-common`; that is why
the app declares it as a `runtimeOnly` dependency (see the comment in
`gradle/libs.versions.toml`). If a refresh fails with "not part of the dependency lock state",
the same kind of module needs the same treatment.

OSV-Scanner reads the lockfiles and both verification files, so a vulnerable build-tool
dependency is reported even though it never ships in the APK. Transitives of AGP and the Android
Lint tool that have high or critical advisories are raised with `eachDependency` rules in the
root `build.gradle.kts` (one copy for the build's plugin classpath, one for all configurations).
Remove a rule once the tool's own dependency has moved past the fixed version. All
`org.bouncycastle:*-jdk18on` artifacts are pinned together: mixing versions breaks AGP's
debug-keystore generation.

Robolectric's `android-all-instrumented` jar (the API 36 sandbox) is resolved by Gradle in the
`robolectricAndroidAll` configuration, so it is locked and checksum-verified like everything
else. `syncRobolectricDeps` copies it to `app/build/robolectric-deps`, and the test tasks set
`robolectric.offline=true` and `robolectric.dependency.dir` so Robolectric never downloads
anything itself. When Robolectric or `robolectric.properties` `sdk=` changes, update
`robolectricAndroidAll36` in the version catalog to the version Robolectric then requests
(its first offline failure names the missing file).

Renovate groups Gradle updates monthly (`renovate.json`). Dependency verification metadata and
lock files are not refreshed by the hosted Renovate app, so run the commands above on its PR
branch before merging.
