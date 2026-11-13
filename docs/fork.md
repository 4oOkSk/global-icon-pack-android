# Fork maintenance

## Notification icons

Select a pack in Global Icon Pack, enable **Notification icons** in General, select **System UI**
in the module's LSPosed scope, then restart System UI. The option defaults to off and is read
when System UI starts. Restart System UI after changing the pack, variants or this option.

The selected pack's application mapping supplies the status bar notification small icon and
the small icon in standard notification headers. Share and provider modes use the saved
application variant; local mode uses the pack's application mapping. Icons retain the pack's
colors, including the background and foreground of adaptive icons. Replacement status icons
ignore silhouette tint while awake and accept System UI's filters during doze/AOD. Standard
headers opt out of monochrome coloring. Android 17's notification row app-icon provider also
uses the selected pack when its supported hook is available.

Status bar replacement requires Android 9 or later and the displayed resource icon to match the notification's
small icon. Conversation avatars, battery/network symbols, large icons and custom notification
layouts retain their own rendering. Missing mappings, unsupported hook signatures and load
failures preserve the original icon. The notification payload remains unchanged.

The status view supports both `updateDrawable()` and `updateDrawable(boolean)`. Its drawing
callers are deoptimized so optimized System UI builds still pass through the icon hook.

System UI's main thread and Binder callbacks only request background loads. Status bar views
refresh when their icon is ready. A standard header built on those threads keeps its original
icon until it is rebound; background header inflation can load directly. A provider unavailable
during early boot is retried on a later request.

Pending status-icon refreshes are coalesced per view and hold views weakly. The bitmap cache
includes the local date, so a calendar icon is reloaded on its next request after the date changes.
Resource lookup caches support concurrent notification workers and configuration refreshes.

## Android 17 behavior

Icon resource markers are applied only in client processes. The `android` package keeps the
system-server permission hooks without installing client resource/parcel hooks. Optional hook
registration failures are logged independently. Notification callbacks retain the system result
when module work fails.

Source initialization runs outside the main thread of every hooked process, including secondary
application processes. Concurrent or recursive callers keep their native icons while one source
is loading; they never wait on an initialization lock. Failed or unavailable sources retry on a
later request after a five-second cooldown. Generic parcelled lists initialize the source only
when they contain supported icon-bearing types; process exit history and other unrelated lists
pass through without requesting a source. Provider mapping lookups from Binder
callbacks and System UI's main thread return no mapping rather than waiting for IPC or a mapping
lock. Worker lookups can retry. The resource-replacement thread flag and provider disk-read
policy are restored even after an exception. Forced monochrome rendering obtains `Resources`
from either the resource receiver or the current application on the Android 17 package-manager
path. Calendar state updates preserve unknown or incompatible launcher state types.

Theme resource refresh skips recursive and concurrent requests while rebuilding fallback
drawables, allowing other callers to use the last published fallback without waiting on the
refresh worker. A failed refresh leaves the resource version unchanged so a later request can
retry. A newer configuration arriving during a rebuild is picked up on a subsequent request.
Shared and provider resource lookup maps support concurrent reads and invalidation.

## Builds and checks

Use JDK 21, Android SDK platform 37 and the checked-in Gradle wrapper. The build tasks are:

```sh
./gradlew :app:assembleDebugApp :app:testDebugUnitTest :app:lintDebugApp
./gradlew :app:assembleRelease
```

The `debugApp` build uses package ID `com.richardluo.globalIconPack.fork` so it can be installed
alongside upstream. Enable only one module for a target process when testing. Debug builds use
the Android debug key and need no `keystore.properties`. Release builds are
unsigned unless that ignored file provides `storeFile`, `storePassword`, `keyAlias` and
`keyPassword`. A signed fork cannot update an installed upstream APK with a different signing
certificate. Preserve any settings you need before switching installations.

`version.properties` retains the upstream major/minor/patch version. `forkRevision` appends
`-fork.N` to the app version and `forkVersionCode` supplies a monotonically increasing update
code for the fork APK.

JVM tests cover legacy strings, current persisted calendar state, unknown types, incompatible
return values, throwing implementations, recursive/concurrent fallback refresh, deferred source
initialization, concurrent/reentrant callers, failure cooldown and retry after resource or
executor failure. Compilation and these tests do not establish
bootloop safety on a device. Device verification must use a verified Android 17 framework and
a compatible LSPosed implementation, with logs from System UI, the launcher and system_server.

On the device, verify boot completion, notification posting/updating/removal, missing pack
mappings, conversation avatars, standard/custom headers, lock screen/AOD and light/dark mode.
Repeat with provider mode, including provider process death and notification arrival during
startup. If System UI or the launcher repeatedly restarts, disable the module's affected scope
through LSPosed and collect crash/ANR logs before enabling it again.
