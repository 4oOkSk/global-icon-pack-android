package com.richardluo.globalIconPack.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

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
}
