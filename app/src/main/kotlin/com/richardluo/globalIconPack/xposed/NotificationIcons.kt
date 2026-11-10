package com.richardluo.globalIconPack.xposed

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import com.richardluo.globalIconPack.iconPack.getSC
import com.richardluo.globalIconPack.iconPack.source.getComponentName
import com.richardluo.globalIconPack.utils.Logger.logE
import com.richardluo.globalIconPack.utils.Logger.logD
import com.richardluo.globalIconPack.utils.classOf
import com.richardluo.globalIconPack.utils.deoptimize
import com.richardluo.globalIconPack.utils.field
import com.richardluo.globalIconPack.utils.hookCompat
import com.richardluo.globalIconPack.utils.isInBinderCall
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Replaces presentation only; the app's notification and its small Icon stay intact. */
class NotificationIcons : Hook {
  private data class Key(val packageName: String, val density: Int, val configuration: String)

  private val cache = LruCache<Key, Bitmap>(64)
  private val pending = mutableMapOf<Key, MutableList<() -> Unit>>()
  private val loader = Executors.newSingleThreadExecutor { task -> Thread(task, "GIP-Notification") }

  context(xposed: XposedInterface)
  override fun onHookSystemUI(param: XposedModuleInterface.PackageReadyParam) {
    // Each entry point is optional: OEMs can change one without disabling the other.
    runCatching { hookStatusBar(param) }.onFailure { logE(it) }
    runCatching { hookHeader() }.onFailure { logE(it) }
  }

  context(xposed: XposedInterface)
  private fun hookStatusBar(param: XposedModuleInterface.PackageReadyParam) {
    if (Build.VERSION.SDK_INT < 28) return
    val clazz = classOf("com.android.systemui.statusbar.StatusBarIconView", param) ?: return
    val notification = clazz.field("mNotification") ?: return
    val conversation = clazz.field("mShowsConversation") ?: return
    val statusIcon = classOf("com.android.internal.statusbar.StatusBarIcon")?.field("icon") ?: return
    // Optimized SystemUI builds can retain only the boolean overload.
    val refresh = runCatching { clazz.getDeclaredMethod("updateDrawable") }.getOrElse {
      clazz.getDeclaredMethod("updateDrawable", Boolean::class.javaPrimitiveType)
    }.apply { isAccessible = true }
    val getIcon = clazz.declaredMethods.singleOrNull {
      it.name == "getIcon" && !Modifier.isStatic(it.modifiers) &&
        it.parameterTypes.map { type -> type.name } ==
          listOf("com.android.internal.statusbar.StatusBarIcon") &&
        android.graphics.drawable.Drawable::class.java.isAssignableFrom(it.returnType)
    } ?: return
    getIcon.apply { isAccessible = true }.hookCompat {
      after {
        // Never let a broken pack, changed field or drawable crash System UI.
        runCatching {
          val view = thisObject as? View ?: return@runCatching
          if (conversation.get(view) == true) return@runCatching
          val sbn = notification.get(view) as? StatusBarNotification ?: return@runCatching
          // Conversation avatars can be bound before mShowsConversation is updated.
          val shown = statusIcon.get(args[0]) as? Icon ?: return@runCatching
          val small = sbn.notification.smallIcon ?: return@runCatching
          if (shown.type != Icon.TYPE_RESOURCE || small.type != Icon.TYPE_RESOURCE ||
            shown.resId != small.resId || shown.resPackage != small.resPackage) return@runCatching
          val reference = WeakReference(view)
          val notificationKey = sbn.key
          val bitmap = bitmap(view.context, sbn.packageName) {
            reference.get()?.let { target ->
              target.post {
                runCatching {
                  val current = notification.get(target) as? StatusBarNotification
                  if (current?.key == notificationKey && conversation.get(target) != true) {
                    if (refresh.parameterCount == 0) refresh.invoke(target)
                    else refresh.invoke(target, true)
                  }
                }.onFailure { logE(it) }
              }
            }
          }
          if (bitmap != null) result = BitmapDrawable(view.resources, bitmap)
        }.onFailure { logE(it) }
      }
    }
    // These callers can contain an inlined getIcon() in optimized SystemUI APKs.
    refresh.deoptimize()
    clazz.declaredMethods.filter {
      it.name == "set" && it.parameterTypes.map { type -> type.name } ==
        listOf("com.android.internal.statusbar.StatusBarIcon")
    }.forEach { it.deoptimize() }
    logD("Notification status icon hook: $getIcon")
  }

  context(xposed: XposedInterface)
  private fun hookHeader() {
    val clazz = Notification.Builder::class.java
    val context = clazz.field("mContext") ?: return
    val bind = clazz.declaredMethods.singleOrNull {
      it.name == "bindSmallIcon" && it.returnType == Void.TYPE &&
        it.parameterTypes.size == 2 && it.parameterTypes[0] == RemoteViews::class.java
    } ?: return
    bind.apply { isAccessible = true }.hookCompat {
      after {
        runCatching {
          val appContext = context.get(thisObject) as? Context ?: return@runCatching
          val views = args[0] as? RemoteViews ?: return@runCatching
          val icon = bitmap(appContext, appContext.packageName) ?: return@runCatching
          views.setImageViewBitmap(android.R.id.icon, icon)
        }.onFailure { logE(it) }
      }
    }
    logD("Notification header hook: $bind")
  }

  context(xposed: XposedInterface)
  private fun bitmap(context: Context, packageName: String, onReady: (() -> Unit)? = null): Bitmap? {
    val res = context.resources
    val key = Key(packageName, res.displayMetrics.densityDpi, res.configuration.toString())
    cache.get(key)?.let { return it }
    // Header inflation normally runs on a worker. Main/binder callers only schedule a load.
    if (Looper.myLooper() != Looper.getMainLooper() && !isInBinderCall()) {
      return load(context, key)
    }
    synchronized(pending) {
      pending[key]?.let {
        if (onReady != null) it.add(onReady)
        return null
      }
      pending[key] = mutableListOf<() -> Unit>().apply { if (onReady != null) add(onReady) }
    }
    val appContext = context.applicationContext ?: context
    loader.execute {
      val loaded = load(appContext, key)
      val callbacks = synchronized(pending) { pending.remove(key).orEmpty() }
      if (loaded != null) callbacks.forEach { runCatching(it).onFailure { logE(it) } }
    }
    return null
  }

  context(xposed: XposedInterface)
  private fun load(context: Context, key: Key): Bitmap? = runCatching {
    cache.get(key)?.let { return@runCatching it }
    val source = getSC() ?: return@runCatching null
    val entry = source.getIconEntry(getComponentName(key.packageName)) ?: return@runCatching null
    var drawable = source.getIconNotAdaptive(entry, key.density) ?: return@runCatching null
    if (drawable is AdaptiveIconDrawable) {
      drawable =
        (if (Build.VERSION.SDK_INT >= 33) drawable.monochrome else null) ?: drawable.foreground
    }
    drawable = (drawable.constantState?.newDrawable(context.resources) ?: drawable).mutate()
    drawable.setTint(Color.WHITE)
    val size = (24 * key.density / 160f).roundToInt().coerceIn(1, 128)
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
      it.density = key.density
      drawable.setBounds(0, 0, size, size)
      drawable.draw(Canvas(it))
      cache.put(key, it)
    }
  }.onFailure { logE(it) }.getOrNull()
}
