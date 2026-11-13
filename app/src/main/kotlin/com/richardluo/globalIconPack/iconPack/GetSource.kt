package com.richardluo.globalIconPack.iconPack

import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.os.Looper
import android.os.SystemClock
import com.richardluo.globalIconPack.MODE_PROVIDER
import com.richardluo.globalIconPack.MODE_SHARE
import com.richardluo.globalIconPack.Pref
import com.richardluo.globalIconPack.get
import com.richardluo.globalIconPack.iconPack.model.IconPackConfig
import com.richardluo.globalIconPack.iconPack.source.BootNotCompleted
import com.richardluo.globalIconPack.iconPack.source.EmptySource
import com.richardluo.globalIconPack.iconPack.source.LocalSource
import com.richardluo.globalIconPack.iconPack.source.RemoteSource
import com.richardluo.globalIconPack.iconPack.source.ShareSource
import com.richardluo.globalIconPack.iconPack.source.Source
import com.richardluo.globalIconPack.reflect.ActivityThread
import com.richardluo.globalIconPack.utils.Logger.log
import com.richardluo.globalIconPack.utils.Logger.logE
import com.richardluo.globalIconPack.utils.RetryingInitializer
import com.richardluo.globalIconPack.utils.WorldPreference
import com.richardluo.globalIconPack.utils.isInBinderCall
import io.github.libxposed.api.XposedInterface
import kotlin.concurrent.thread

private val source = RetryingInitializer<Source>(
  nowMillis = SystemClock::uptimeMillis,
  execute = { task -> thread(name = "GIP-InitSource") { task.run() } },
)

context(xposed: XposedInterface)
fun getSC(): Source? {
  // Framework constructors run before the Application exists; that is not a failed load.
  val context = ActivityThread.currentApplication() ?: return null
  // XML can block on incremental APK reads even in local mode. This applies to every
  // hooked application's main thread, including secondary processes during Application.onCreate.
  // Binder callbacks must also return so provider publication can be delivered. Reentrant and
  // concurrent callers keep their native icons while one initializer is running.
  return source.get(
    defer = isInBinderCall() || Looper.myLooper() == Looper.getMainLooper(),
    onFailure = { logE(it) },
  ) {
    initSC(context)
  }
}

context(xposed: XposedInterface)
private fun initSC(context: android.app.Application): Source? {
  val pref = WorldPreference.get()
  val pack = pref.get(Pref.ICON_PACK)
  val config = IconPackConfig(pref)
  val sc =
    if (pack.isEmpty()) {
      log("No icon pack is set")
      EmptySource()
    } else
      when (pref.get(Pref.MODE)) {
        MODE_SHARE -> ShareSource(context, xposed, pack, config)
        MODE_PROVIDER ->
          try {
            RemoteSource(context, xposed, pack, config)
          } catch (e: BootNotCompleted) {
            log(e.message.orEmpty())
            return null
          }
        else -> LocalSource(context, xposed, pack, config)
      }
  context.registerComponentCallbacks(
    object : ComponentCallbacks {
      override fun onConfigurationChanged(newConfig: Configuration) {
        runCatching { sc.updateResources() }.onFailure { logE(it) }
      }

      @Deprecated("Deprecated in Java") override fun onLowMemory() {}
    }
  )
  return sc
}
