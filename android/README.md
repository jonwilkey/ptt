# android/

The family push-to-talk Android app (Kotlin, Gradle), distributed as a signed, sideloaded APK.

This is the project skeleton (the build, static analysis, and CI) plus the network, database and
outbox layers, enrollment and roster sync, the admin tools (add a device with a QR, remove a device)
and the setup checklist, and receiving a page: FCM handling and alerting (`push`, `alert`). Sending
a page, the real talk screen (`TalkActivity` is a stub), the widget, live talk and the clip fallback
follow in the next PRs and milestones 3-5.
`version.txt` holds the app version that release-please bumps (tags `android-vX.Y.Z`). Release
signing is not configured yet (milestone 6), so release builds stay unsigned.

## Layout

| Path | Purpose |
| --- | --- |
| `app/` | The single `:app` module (`com.example.ptt`; application id `com.example.ptt`) |
| `app/src/main/kotlin/com/example/ptt/` | Packages named as in the spec (`push`, `setup`, ...), created as needed |
| `app/src/debug/res/xml/` | Debug network security config (adds cleartext to `10.0.2.2` and `localhost`) |
| `app/src/main/kotlin/com/example/ptt/crypto/` | Protocol crypto: Tink device keys (Android Keystore), strict JSON/b64u, signed objects, roster, enrollment, page envelopes |
| `app/src/main/kotlin/com/example/ptt/net/` | REST client (`PttApi`, OkHttp, TLS 1.3 only), auth interceptor, `DeviceTokenStore` (token encrypted by an Android Keystore AES-GCM key) |
| `app/src/main/kotlin/com/example/ptt/data/` | Room database (`contacts`, `roster`, `outbox`, `active_page`), repositories, DataStore settings |
| `app/src/main/kotlin/com/example/ptt/outbox/` | `Outbox` (persisted retries with backoff) and `OutboxWorker` (expedited WorkManager retry path) |
| `app/src/main/kotlin/com/example/ptt/setup/` | Enrollment, roster sync, routing, registration, the admin tools (`AddDeviceFlow`, `DeviceRevoker`; the QR is rendered with zxing core) and the setup checklist (`SetupChecklist`, `SystemProbe`) |
| `app/src/main/kotlin/com/example/ptt/push/` | `FcmService`, the UI-free `PushDispatcher` (every protocol section 9 message, the receive flow with `delivered`), `SessionKeys` (in memory only), `RosterSyncWorker` |
| `app/src/main/kotlin/com/example/ptt/alert/` | Notification channels, `PageNotifier` (every page notification), `AlertEngine` (ringing policy, free of Android classes), `AlertService` (foreground, `specialUse`), Android device adapters |
| `app/src/main/kotlin/com/example/ptt/talk/` | `TalkActivity` stub and `TalkIntents`, the intent contract the real talk screen will implement |
| `app/src/debug/kotlin/com/example/ptt/push/` | `FakePushReceiver`, debug builds only |
| `app/src/main/kotlin/com/example/ptt/di/` | Hilt wiring for the packages above |
| `app/schemas/` | Room schema JSON, exported by KSP and checked in; commit the new `N.json` with every `@Database` version bump |
| `buildSrc/` | `AppVersion` (parses `version.txt`, computes `versionCode`) and `DependencyFloors` (security version floors), with unit tests |
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

## Testing alerts without Firebase (debug builds)

Debug builds include `FakePushReceiver`, which turns a broadcast's string extras into an FCM data map and
hands it to `PushDispatcher`, the same code `FcmService` calls. It is in the debug source set (class and
manifest entry), so the release APK has neither, and it requires the `DUMP` permission, which only `adb`
holds. Every extra is a string, exactly as in docs/protocol.md section 9:

```sh
# Cancel, acknowledge elsewhere, sender-side states, test notification:
adb shell am broadcast -a com.example.ptt.debug.FAKE_PUSH -n com.example.ptt/.push.FakePushReceiver \
  --es type page_cancel --es page_id <page-uuid> --es reason missed
adb shell am broadcast -a com.example.ptt.debug.FAKE_PUSH -n com.example.ptt/.push.FakePushReceiver \
  --es type test

# Ring a normal or urgent page (skips the envelope check and `delivered`, which need a real signed
# envelope; "from_name" is shown as the caller). Cancel it afterwards with page_cancel and the same id:
adb shell am broadcast -a com.example.ptt.debug.FAKE_PUSH -n com.example.ptt/.push.FakePushReceiver \
  --es type debug_page --es level urgent --es from_name Mom --es page_id 11111111-1111-4111-8111-111111111111
```

A real `page` (signed envelope, `delivered`, active page) is covered by the `PushDispatcherTest` cases; on a
device it needs the server and Firebase configured.

## Alerting notes

- Channels (`AlertChannels`) are immutable once created on a phone: `page_normal`, `page_urgent`,
  `page_status`, `page_quiet` and `alert_service`. Changing a sound, importance or vibration needs a new
  channel id.
- `AlertEngine` is the single place that starts and stops ringing. The foreground `AlertService` only
  keeps the process alive and stops itself when nothing rings (the engine never calls `stopService`, which
  can race a pending start); when it is destroyed for any reason it calls `AlertEngine.shutdown()`, which
  restores the alarm volume, stops the tone and vibration and releases the wake lock.
- The alarm volume to restore is persisted (`alert_state` preferences) before an urgent page raises it, and
  put back at app start and when the engine is created, so a crash or force-stop cannot leave it at maximum.
- Ringing page notifications are ongoing; if one is removed anyway while the page still rings, it is posted
  again. The foreground notice opens the highest-level ringing page.
- Database failures (active page row, outbox) are logged and never stop a page from alerting.
- `TalkActivity` is a stub. Its intent contract is documented on `TalkIntents` (extras `PAGE_ID`, `MODE`,
  `CALLER_NAME`; modes `answer`, `incoming`, `outgoing`).

### Expected behaviour by phone mode

| Phone mode | Normal page | Urgent page |
| --- | --- | --- |
| Normal ringer | Notification sound (notification stream) and vibration, repeated every 30 s | Looped tone on the alarm stream at maximum volume, long vibration, full-screen screen |
| Vibrate ringer | Vibrates (the channel's own pattern), no sound | Same as above (alarm stream is not muted by the ringer) |
| Silent ringer | No sound; the engine vibrates the short pattern itself on every ring | Same as above |
| Do Not Disturb (priority only, alarms allowed) | The notification is suppressed; the engine vibrates the short pattern itself on every ring | Breaks through: the channel bypasses DND (needs DND access) and the tone is an alarm |
| Do Not Disturb "total silence" | Suppressed; the engine's vibration is also blocked | Blocked too: total silence blocks alarms. This is a platform limit the app cannot override |

### The urgent channel and Do Not Disturb

Android drops "override Do Not Disturb" on a channel that is created while the app lacks Do Not Disturb
access, and the grant happens after first launch. The channels are therefore created again at every app
start, whenever the setup checklist sees the access, and before each urgent notification. The checklist row
"Urgent pages ignore Do Not Disturb" is red until the channel really bypasses it, and opens that channel's
settings. On a device, check it with:

```sh
adb shell dumpsys notification | grep -A12 'page_urgent'      # look for bypassDnd=true
adb shell cmd notification allow_dnd com.example.ptt          # grants DND access on a test phone
```

Manual test notes (on top of the spec's Testing list): with DND access granted, confirm `bypassDnd=true`
on `page_urgent`; then with the ringer on silent, with DND on (priority only), and with vibrate mode,
send a normal and an urgent page and compare with the table above; confirm that the alarm volume is back to
its earlier value after the page is answered and after force-stopping the app mid-alert.

## Crypto tests and shared vectors

The unit tests under `app/src/test/kotlin/com/example/ptt/crypto/` read
`../testdata/protocol-vectors.json` (generated by the Go server module; see `testdata/README.md`)
through the `ptt.protocolVectors` system property that `app/build.gradle.kts` sets. Device keys are
Tink keysets behind the `DeviceKeyStore` interface: `AndroidDeviceKeyStore` encrypts each keyset
with its own Android Keystore key (`ptt_signing_master`, `ptt_hpke_master`); the JVM tests use an
in-memory store because Robolectric has no AndroidKeyStore.

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
Lint tool that have high or critical advisories are raised to the floors in
`buildSrc/src/main/kotlin/DependencyFloors.kt` (applied by `eachDependency` rules in the root
`build.gradle.kts`, for the build's plugin classpath and for all configurations). A floor only
raises a version; a newer request is left alone.
Remove a floor once the tool's own dependency has moved past the fixed version. Every
`org.bouncycastle:*-jdk18on` artifact has the same floor, and `BouncyCastleAlignmentRule`
(buildSrc) puts them in one virtual platform so they all resolve to the highest version any of
them requests, never less than the floor: mixing versions breaks AGP's debug-keystore generation.

Robolectric's `android-all-instrumented` jar (the API 36 sandbox) is resolved by Gradle in the
`robolectricAndroidAll` configuration, so it is locked and checksum-verified like everything
else. `syncRobolectricDeps` copies it to `app/build/robolectric-deps`, and the test tasks set
`robolectric.offline=true` and `robolectric.dependency.dir` so Robolectric never downloads
anything itself. When Robolectric or `robolectric.properties` `sdk=` changes, update
`robolectricAndroidAll36` in the version catalog to the version Robolectric then requests
(its first offline failure names the missing file).

`FullScreenProbeTest` runs at `@Config(sdk = [33, 34])` to cover both sides of the Android 14
split, so the API 33 and 34 jars are resolved the same way in `robolectricAndroidAll33` and
`robolectricAndroidAll34` (one configuration cannot hold two versions of the same module).

Renovate groups Gradle updates monthly (`renovate.json`). Dependency verification metadata and
lock files are not refreshed by the hosted Renovate app, so run the commands above on its PR
branch before merging.
