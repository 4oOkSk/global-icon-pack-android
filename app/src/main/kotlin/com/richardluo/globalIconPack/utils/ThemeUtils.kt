package com.richardluo.globalIconPack.utils

import android.app.Application
import android.content.res.Configuration
import android.os.Build
import com.richardluo.globalIconPack.reflect.Resources

data class ThemeSig(val night: Int, val seq: Int)

fun currentThemeSig(context: Application): ThemeSig? {
  val c = context.resources?.configuration ?: return null
  return ThemeSig(
    c.uiMode and Configuration.UI_MODE_NIGHT_MASK,
    Resources.getAssetsSeq(c) ?: 0,
  )
}

class ThemeColorMonitor internal constructor(private val readAssetsSeq: () -> Int?) {
  constructor(context: Application) : this({
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Resources.getAssetsSeq(context.resources)
    else null
  })

  @Volatile private var assetsSeq: Int? = readAssetsSeq()
  private var updating = false

  fun update(doUpdate: () -> Unit) {
    val newAssetsSeq = readAssetsSeq()
    if (assetsSeq == newAssetsSeq) return
    synchronized(this) {
      // Rebuilding fallback drawables loads icons through this same monitor.
      if (updating || assetsSeq == newAssetsSeq) return
      updating = true
      try {
        doUpdate()
        assetsSeq = newAssetsSeq
      } finally {
        updating = false
      }
    }
  }
}
