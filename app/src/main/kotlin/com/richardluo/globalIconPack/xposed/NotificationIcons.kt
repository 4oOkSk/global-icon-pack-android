package com.richardluo.globalIconPack.xposed

import android.app.Notification
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
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
import java.time.LocalDate
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Replaces presentation only; the app's notification and its small Icon stay intact. */
class NotificationIcons : Hook {
  private data class Key(
    val packageName: String,
    val density: Int,
    val configuration: String,
    val day: LocalDate,
  )

  private val cache = LruCache<Key, Bitmap>(64)
  private val pending = mutableMapOf<Key, WeakHashMap<View, () -> Unit>>()
  private val loader = Executors.newSingleThreadExecutor { task -> Thread(task, "GIP-Notification") }

  context(xposed: XposedInterface)
  override fun onHookSystemUI(param: XposedModuleInterface.PackageReadyParam) {
    // Each entry point is optional: OEMs can change one without disabling the other.
    runCatching { hookStatusBar(param) }.onFailure { logE(it) }
    runCatching { hookHeader() }.onFailure { logE(it) }
    runCatching { hookRowAppIcon(param) }.onFailure { logE(it) }
  }

  context(xposed: XposedInterface)
  private fun hookStatusBar(param: XposedModuleInterface.PackageReadyParam) {
    if (Build.VERSION.SDK_INT < 28) return
    val clazz = classOf("com.android.systemui.statusbar.StatusBarIconView", param) ?: return
    val notification = clazz.field("mNotification") ?: return
    val conversation = clazz.field("mShowsConversation") ?: return
    val doze = clazz.field("mDozeAmount")
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
          val bitmap = bitmap(view.context, sbn.packageName, view) {
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
          if (bitmap != null) {
            // Keep the policy on this drawable: recycled views and conversation avatars never
            // inherit a disabled tint. SystemUI still supplies its normal filters during doze.
            result = ColorIconDrawable(view.resources, bitmap) {
              runCatching {
                reference.get()?.let { doze?.getFloat(it)?.let { amount -> amount > 0f } }
              }.getOrNull() ?: true
            }
          }
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
          // CachingIconView's NO_COLOR sentinel. bindSmallIcon has already appended its
          // monochrome color action; the final action must opt out for the replacement.
          views.setInt(android.R.id.icon, "setOriginalIconColor", 1)
          if (Build.VERSION.SDK_INT >= 31) {
            views.setColorStateList(android.R.id.icon, "setImageTintList", null)
          }
        }.onFailure { logE(it) }
      }
    }
    logD("Notification header hook: $bind")
  }

  context(xposed: XposedInterface)
  private fun hookRowAppIcon(param: XposedModuleInterface.PackageReadyParam) {
    // Android 17 can obtain an app icon independently of the RemoteViews small icon.
    val clazz = classOf(
      "com.android.systemui.statusbar.notification.row.icon." +
        "NotificationRowIconViewInflaterFactory\$createIconProvider\$2", param,
    ) ?: return
    val context = clazz.field("\$context") ?: return
    val notification = clazz.field("\$sbn") ?: return
    val launcherIcon = clazz.getDeclaredMethod("getLauncherIcon").apply { isAccessible = true }
    launcherIcon.hookCompat {
      after {
        runCatching {
          val appContext = context.get(thisObject) as? Context ?: return@runCatching
          val sbn = notification.get(thisObject) as? StatusBarNotification ?: return@runCatching
          val icon = bitmap(appContext, sbn.packageName) ?: return@runCatching
          result = ColorIconDrawable(appContext.resources, icon)
        }.onFailure { logE(it) }
      }
    }
    // The framework may inline this tiny interface implementation at either load entry point.
    classOf("com.android.internal.widget.NotificationRowIconView")?.declaredMethods?.filter {
      it.name in setOf("loadAppIcon", "setImageIcon", "setImageIconAsync")
    }?.forEach { it.deoptimize() }
    logD("Notification row app icon hook: $launcherIcon")
  }

  context(xposed: XposedInterface)
  private fun bitmap(
    context: Context,
    packageName: String,
    target: View? = null,
    onReady: (() -> Unit)? = null,
  ): Bitmap? {
    val res = context.resources
    val key = Key(packageName, res.displayMetrics.densityDpi, res.configuration.toString(), LocalDate.now())
    cache.get(key)?.let { return it }
    // Header inflation normally runs on a worker. Main/binder callers only schedule a load.
    if (Looper.myLooper() != Looper.getMainLooper() && !isInBinderCall()) {
      return load(context, key)
    }
    synchronized(pending) {
      // A load can finish between the first cache read and acquiring this lock.
      cache.get(key)?.let { return it }
      pending[key]?.let {
        if (target != null && onReady != null) it[target] = onReady
        return null
      }
      pending[key] = WeakHashMap<View, () -> Unit>().apply {
        if (target != null && onReady != null) put(target, onReady)
      }
    }
    val appContext = context.applicationContext ?: context
    loader.execute {
      val loaded = load(appContext, key)
      val callbacks = synchronized(pending) { pending.remove(key)?.values?.toList().orEmpty() }
      if (loaded != null) callbacks.forEach { runCatching(it).onFailure { logE(it) } }
    }
    return null
  }

  context(xposed: XposedInterface)
  private fun load(context: Context, key: Key): Bitmap? = runCatching {
    cache.get(key)?.let { return@runCatching it }
    val source = getSC() ?: return@runCatching null
    val entry = source.getIconEntry(getComponentName(key.packageName)) ?: return@runCatching null
    val original = source.getIconNotAdaptive(entry, key.density) ?: return@runCatching null
    val drawable = (original.constantState?.newDrawable(context.resources) ?: original).mutate()
    val size = (24 * key.density / 160f).roundToInt().coerceIn(1, 128)
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
      it.density = key.density
      drawable.setBounds(0, 0, size, size)
      drawable.draw(Canvas(it))
      cache.put(key, it)
    }
  }.onFailure { logE(it) }.getOrNull()
}

/** Ignore silhouette tint only for artwork supplied by the pack. */
private class ColorIconDrawable(
  resources: Resources,
  bitmap: Bitmap,
  private val useSystemFilter: () -> Boolean = { false },
) : BitmapDrawable(resources, bitmap) {
  override fun setTintList(tint: ColorStateList?) = super.setTintList(null)

  override fun setColorFilter(colorFilter: ColorFilter?) =
    super.setColorFilter(if (useSystemFilter()) colorFilter else null)
}
