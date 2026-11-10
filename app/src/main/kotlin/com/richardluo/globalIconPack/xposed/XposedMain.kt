package com.richardluo.globalIconPack.xposed

import com.richardluo.globalIconPack.BuildConfig
import com.richardluo.globalIconPack.Pref
import com.richardluo.globalIconPack.get
import com.richardluo.globalIconPack.utils.Logger
import com.richardluo.globalIconPack.utils.WorldPreference
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class XposedMain : XposedModule() {
  init {
    Logger.init(this)
  }

  override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
    runCatching { BypassShortcutPermission.onHookSystem(param) }.onFailure { Logger.logE(it) }
    runCatching { BypassQueryPackage.onHookSystem(param) }.onFailure { Logger.logE(it) }
    runCatching { BypassCrossUserPermission.onHookSystem(param) }.onFailure { Logger.logE(it) }
  }

  override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
    runCatching { hookPackage(param) }.onFailure { Logger.logE(it) }
  }

  private fun hookPackage(param: XposedModuleInterface.PackageReadyParam) {
    if (!param.isFirstPackage) return
    // Resource-id markers must stay in client processes, never in system_server's parcelables.
    if (param.packageName == BuildConfig.APPLICATION_ID || param.packageName == "android") return

    val pref = runCatching { WorldPreference.get() }.getOrElse { Logger.logE(it); return }
    val hookList =
      listOfNotNull(
        ReplaceIcon(
          pref.get(Pref.SHORTCUT),
          pref.get(Pref.FORCE_ACTIVITY_ICON_FOR_TASK),
          pref.get(Pref.NON_ADAPTIVE_SCALE),
          pref.get(Pref.FORCE_MONOCHROME),
        ),
        NoForceShape(true),
        if (pref.get(Pref.NO_SHADOW)) NoShadow() else null,
        if (pref.get(Pref.FORCE_LOAD_CLOCK_AND_CALENDAR))
          CalendarAndClockHook(pref.get(Pref.CLOCK_USE_FALLBACK_MASK))
        else null,
        if (pref.get(Pref.RELOAD_ICON_ON_THEME_CHANGE)) ReloadIconOnThemeChange() else null,
        if (pref.get(Pref.NOTIFICATION_ICONS)) NotificationIcons() else null,
      )
    hookList.forEach { runCatching { it.onHookApp(param) }.onFailure { Logger.logE(it) } }
    when (param.packageName) {
      "com.android.launcher3",
      Pref.PIXEL_LAUNCHER_PACKAGE.def,
      pref.get(Pref.PIXEL_LAUNCHER_PACKAGE) -> hookList.forEach {
        runCatching { it.onHookPixelLauncher(param) }.onFailure { Logger.logE(it) }
      }
      "com.android.systemui" -> hookList.forEach {
        runCatching { it.onHookSystemUI(param) }.onFailure { Logger.logE(it) }
      }
      "com.android.settings" -> hookList.forEach {
        runCatching { it.onHookSettings(param) }.onFailure { Logger.logE(it) }
      }
    }
  }
}
