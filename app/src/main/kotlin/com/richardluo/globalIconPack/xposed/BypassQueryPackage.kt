package com.richardluo.globalIconPack.xposed

import com.richardluo.globalIconPack.Pref
import com.richardluo.globalIconPack.get
import com.richardluo.globalIconPack.utils.WorldPreference
import com.richardluo.globalIconPack.utils.allMethods
import com.richardluo.globalIconPack.utils.classOf
import com.richardluo.globalIconPack.utils.hookCompat
import com.richardluo.globalIconPack.utils.Logger.logE
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface

object BypassQueryPackage {
  context(xposed: XposedInterface)
  fun onHookSystem(param: XposedModuleInterface.SystemServerStartingParam) {
    val getPackageNameM =
      classOf("com.android.server.pm.pkg.PackageState", param.classLoader)
        ?.getMethod("getPackageName") ?: return
    classOf("com.android.server.pm.AppsFilterBase", param.classLoader)
      ?.allMethods("shouldFilterApplication")
      ?.hookCompat {
        after {
           if (result !is Boolean || result == false) return@after
           runCatching {
             val target = args.getOrNull(3) ?: return@runCatching
             if (!getPackageNameM.declaringClass.isInstance(target)) return@runCatching
             if (getPackageNameM.invoke(target) == WorldPreference.get().get(Pref.ICON_PACK))
               result = false
           }.onFailure { logE(it) }
        }
      }
  }
}
