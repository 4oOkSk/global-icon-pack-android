package com.richardluo.globalIconPack.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

class ThemeColorMonitorTest {
  @Test fun fallbackLoadingCanReenterResourceUpdate() {
    var sequence = 1
    val monitor = ThemeColorMonitor { sequence }
    var rebuilds = 0
    sequence = 2
    monitor.update {
      rebuilds++
      monitor.update { error("Fallback loading must not start another rebuild") }
    }
    monitor.update { rebuilds++ }
    assertEquals(1, rebuilds)
    sequence = 3
    monitor.update { rebuilds++ }
    assertEquals(2, rebuilds)
  }

  @Test fun failedRebuildCanBeRetried() {
    var sequence = 1
    val monitor = ThemeColorMonitor { sequence }
    sequence = 2
    assertThrows(IllegalStateException::class.java) {
      monitor.update { error("Resource temporarily unavailable") }
    }
    var rebuilt = false
    monitor.update { rebuilt = true }
    assertEquals(true, rebuilt)
    monitor.update { error("Successful rebuild must not repeat") }
  }

  @Test fun concurrentCallerDoesNotWaitAndNewerConfigurationIsRetried() {
    var sequence = 1
    val monitor = ThemeColorMonitor { sequence }
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    sequence = 2
    try {
      val worker = pool.submit {
        monitor.update {
          entered.countDown()
          assertTrue(release.await(5, SECONDS))
        }
      }
      assertTrue(entered.await(2, SECONDS))
      sequence = 3
      pool.submit {
        monitor.update { error("Must keep published resources while a rebuild is pending") }
      }.get(2, SECONDS)
      release.countDown()
      worker.get(2, SECONDS)
      var rebuilds = 0
      monitor.update { rebuilds++ }
      monitor.update { error("Latest sequence must be committed") }
      assertEquals(1, rebuilds)
    } finally {
      release.countDown()
      pool.shutdownNow()
    }
  }
}
