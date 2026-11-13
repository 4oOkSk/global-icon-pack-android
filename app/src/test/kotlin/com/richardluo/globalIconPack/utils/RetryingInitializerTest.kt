package com.richardluo.globalIconPack.utils

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import org.junit.Assert.*
import org.junit.Test

class RetryingInitializerTest {
  private val unexpectedFailure: (Throwable) -> Unit = { throw AssertionError(it) }

  @Test fun deferredCallCoalescesRequestsAndPublishesOnlyAfterCompletion() {
    val queued = mutableListOf<Runnable>()
    val source = RetryingInitializer<String>({ 0 }, { queued.add(it) })
    assertNull(source.get(true, unexpectedFailure) { "ready" })
    repeat(100) { assertNull(source.get(true, unexpectedFailure) { error("Duplicate load") }) }
    assertEquals(1, queued.size)
    queued.single().run()
    assertEquals("ready", source.get(true, unexpectedFailure) { error("Already ready") })
  }

  @Test fun concurrentAndReentrantCallersNeverWaitForBlockedLoader() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    val source = RetryingInitializer<String>({ 0 }, { pool.execute(it) })
    try {
      val worker = pool.submit<String?> {
        source.get(false, unexpectedFailure) {
          assertNull(source.get(false, unexpectedFailure) { error("Recursive load") })
          entered.countDown()
          assertTrue(release.await(5, SECONDS))
          "ready"
        }
      }
      assertTrue(entered.await(2, SECONDS))
      // A caller must return before releasing the loader: detects lock inversions without sleeps.
      assertNull(pool.submit<String?> {
        source.get(false, unexpectedFailure) { error("Concurrent load") }
      }.get(2, SECONDS))
      release.countDown()
      assertEquals("ready", worker.get(2, SECONDS))
    } finally {
      release.countDown()
      pool.shutdownNow()
    }
  }

  @Test fun failuresAndUnavailableProvidersBackOffThenRecover() {
    var now = 0L
    var failures = 0
    val source = RetryingInitializer<String>({ now }, { it.run() })
    assertNull(source.get(false, { failures++ }) { error("Pack unavailable") })
    repeat(100) { assertNull(source.get(true, unexpectedFailure) { error("Retry storm") }) }
    now = 4_999
    assertNull(source.get(false, unexpectedFailure) { error("Too early") })
    now = 5_000
    assertNull(source.get(false, unexpectedFailure) { null })
    now = 9_999
    assertNull(source.get(false, unexpectedFailure) { error("Null result must back off too") })
    now = 10_000
    assertEquals("ready", source.get(false, unexpectedFailure) { "ready" })
    assertEquals(1, failures)
  }

  @Test fun rejectedBackgroundTaskCanRetry() {
    var now = 0L
    var reject = true
    var failures = 0
    val source = RetryingInitializer<String>({ now }, {
      if (reject) error("Cannot start thread") else it.run()
    })
    assertNull(source.get(true, { failures++ }) { "ready" })
    reject = false
    now = 5_000
    assertEquals("ready", source.get(true, unexpectedFailure) { "ready" })
    assertEquals(1, failures)
  }
}
