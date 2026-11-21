# Fork maintenance

This fork maintains two additions over upstream (`RichardLuo0/global-icon-pack-android`): status-bar/standard-header **Notification icons** and **Android 17** compatibility. Detailed thread/cache/hook internals live in the source code and JVM tests.

## Functional and runtime boundaries

- **Notification icons** (Android 9+): enable **Notification icons** in General, select **System UI** in LSPosed scope, and **restart System UI** (also required after changing the pack, variants or this toggle). Replaces status-bar small icons, standard notification header small icons and Android 17 notification row app-icons while preserving conversation avatars, system status symbols, large icons and custom layouts. Missing mappings or load failures fall back to the original icon without mutating the notification payload.
- **Android 17 compatibility**: icon resource markers apply only in client processes (`android` keeps only system-server permission hooks); source loading and provider lookups never block System UI's main thread or Binder callbacks; hook failures fall back to native results.

## Builds and checks

Requires JDK 21, Android SDK platform 37 and the checked-in Gradle wrapper:

```sh
./gradlew :app:assembleDebugApp :app:testDebugUnitTest :app:lintDebugApp
./gradlew :app:assembleRelease
```

- **Coexistence and signing**: `debugApp` uses package ID `com.richardluo.globalIconPack.fork` (debug-signed, can coexist with upstream); enable **only one** module for a target process during testing. `assembleRelease` uses the upstream application ID and requires ignored `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) to sign; a different signing certificate cannot update an installed upstream APK in place—back up settings before switching.
- **Versioning**: `version.properties` tracks upstream version plus monotonic `forkRevision` (`-fork.N`) and `forkVersionCode`.
- **Device verification boundary**: compilation and JVM tests **do not establish bootloop safety**. Verify on a real Android 17 framework + compatible LSPosed with logs from System UI, launcher and `system_server` (covering boot, notification post/update/remove, missing mappings, conversation avatars, headers, AOD, light/dark and provider-mode process death). If System UI or the launcher enters a restart loop, **disable the affected scope in LSPosed** and collect crash/ANR logs before re-enabling.
