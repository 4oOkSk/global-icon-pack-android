package com.richardluo.globalIconPack.iconPack.model

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.drawable.Drawable
import com.richardluo.globalIconPack.reflect.Resources
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

open class ResourceOwner(val context: Application, protected val pack: String) {
  @Volatile protected var res = context.packageManager.getResourcesForApplication(pack)
  private val idCache = ConcurrentHashMap<String, Int>()

  @SuppressLint("DiscouragedApi")
  fun getIdByName(name: String): Int =
    idCache.getOrPut("$pack/$name") { res.getIdentifier(name, "drawable", pack) }

  context(xposed: XposedInterface)
  fun getIconById(id: Int, iconDpi: Int): Drawable? =
    if (id == 0) null else Resources.getDrawableForDensity(res, id, iconDpi, null)

  context(xposed: XposedInterface)
  fun getIconByName(name: String, iconDpi: Int): Drawable? = getIconById(getIdByName(name), iconDpi)
}
