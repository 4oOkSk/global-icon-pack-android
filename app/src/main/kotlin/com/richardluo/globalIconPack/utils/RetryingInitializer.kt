package com.richardluo.globalIconPack.utils

import java.util.concurrent.atomic.AtomicBoolean

/** One initialization at a time; callers never wait for another thread's disk access or IPC. */
internal class RetryingInitializer<T : Any>(
  private val nowMillis: () -> Long,
  private val execute: (Runnable) -> Unit,
  private val retryDelayMillis: Long = 5_000,
) {
  @Volatile private var value: T? = null
  @Volatile private var retryAt = 0L
  private val loading = AtomicBoolean(false)

  fun get(defer: Boolean, onFailure: (Throwable) -> Unit, create: () -> T?): T? {
    value?.let { return it }
    if (nowMillis() < retryAt || !loading.compareAndSet(false, true)) return null
    // Another initializer may have finished between the first read and the claim.
    if (value != null || nowMillis() < retryAt) {
      loading.set(false)
      return value
    }
    val task = Runnable {
      try {
        value = create()
      } catch (error: Throwable) {
        onFailure(error)
      } finally {
        if (value == null) retryAt = nowMillis() + retryDelayMillis
        loading.set(false)
      }
    }
    if (defer) {
      try {
        execute(task)
      } catch (error: Throwable) {
        retryAt = nowMillis() + retryDelayMillis
        loading.set(false)
        onFailure(error)
      }
    } else task.run()
    return value
  }
}
