package com.richardluo.globalIconPack.utils

import android.app.Application
import android.content.res.Configuration
import android.os.Build
import com.richardluo.globalIconPack.reflect.Resources
import java.util.concurrent.atomic.AtomicBoolean

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
  private val updating = AtomicBoolean(false)

  fun update(doUpdate: () -> Unit) {
    val newAssetsSeq = readAssetsSeq()
    if (assetsSeq == newAssetsSeq || !updating.compareAndSet(false, true)) return
    // A worker rebuilding resources can need Android locks held by a calling main/binder
    // thread. Never make that caller wait; it can use the last published fallback meanwhile.
    try {
      if (assetsSeq == newAssetsSeq) return
      doUpdate()
      assetsSeq = newAssetsSeq
    } finally {
      updating.set(false)
    }
  }
}
