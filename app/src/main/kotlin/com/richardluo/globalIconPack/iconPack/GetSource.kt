package com.richardluo.globalIconPack.iconPack

import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.os.Looper
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
import com.richardluo.globalIconPack.utils.WorldPreference
import com.richardluo.globalIconPack.utils.isInBinderCall
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@Volatile private var sc: Source? = null
private val initializingAsync = AtomicBoolean(false)
private val initializingSource = ThreadLocal.withInitial { false }

context(xposed: XposedInterface)
fun getSC(): Source? {
  // Loading pack resources also constructs PackageItemInfo objects intercepted by ReplaceIcon.
  if (initializingSource.get() == true) return null
  sc?.let {
    return it
  }
  // Creating a source may wait for IconPackProvider's process to start. A binder thread must not
  // wait for it (or for the lock below): if it is handling a oneway call to ApplicationThread, the
  // system's notifyContentProviderPublishStatus() is queued behind that call, so the wait never
  // ends and the process freezes for 20s (a SystemUI ANR on every boot). Initialize in the
  // background instead and skip this call.
  if (
    isInBinderCall() ||
      (ActivityThread.currentPackageName() == "com.android.systemui" &&
        Looper.myLooper() == Looper.getMainLooper())
  ) {
    if (initializingAsync.compareAndSet(false, true))
      thread(name = "GIP-InitSource") {
        try {
          synchronized(Source::class) { if (sc == null) initSCGuarded() }
        } finally {
          initializingAsync.set(false)
        }
      }
    return null
  }
  synchronized(Source::class) {
    if (sc == null) {
      initSCGuarded()
    }
  }
  return sc
}

context(xposed: XposedInterface)
private fun initSCGuarded() {
  initializingSource.set(true)
  try {
    runCatching { initSC() }.onFailure { logE(it) }
  } finally {
    initializingSource.set(false)
  }
}

context(xposed: XposedInterface)
private fun initSC() {
  val context = ActivityThread.currentApplication() ?: return
  runCatching {
    val pref = WorldPreference.get()
    val pack = pref.get(Pref.ICON_PACK)
    val config = IconPackConfig(pref)
    sc =
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
              return
            }
          else -> LocalSource(context, xposed, pack, config)
        }
    context.registerComponentCallbacks(
      object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
          runCatching { sc?.updateResources() }.onFailure { logE(it) }
        }

        @Deprecated("Deprecated in Java") override fun onLowMemory() {}
      }
    )
  }
    .onFailure { logE(it) }
}
