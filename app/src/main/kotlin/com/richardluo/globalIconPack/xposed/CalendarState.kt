package com.richardluo.globalIconPack.xposed

/** Launcher state changed from String to PersistedItemState; preserve unknown OEM types. */
internal fun calendarStateWithDay(state: Any, dayOffset: Int): Any {
  if (state is String) return "$state $dayOffset"
  return runCatching {
    val method = state.javaClass.getDeclaredMethod("withAdditionalValues", Array<String>::class.java)
    method.isAccessible = true
    method.invoke(state, arrayOf(dayOffset.toString()))?.takeIf { state.javaClass.isInstance(it) }
      ?: state
  }.getOrDefault(state)
}
