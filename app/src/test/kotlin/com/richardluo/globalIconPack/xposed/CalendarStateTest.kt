package com.richardluo.globalIconPack.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CalendarStateTest {
  class State(val values: List<String>) {
    fun withAdditionalValues(values: Array<String>) = State(this.values + values)
  }

  class ChangedReturnType {
    fun withAdditionalValues(values: Array<String>): String = values.joinToString()
  }

  class ThrowingState {
    fun withAdditionalValues(values: Array<String>): ThrowingState = error(values.joinToString())
  }

  @Test fun legacyStringIncludesDay() {
    assertEquals("base 9", calendarStateWithDay("base", 9))
  }

  @Test fun persistedStateKeepsItsTypeAndExistingValues() {
    val result = calendarStateWithDay(State(listOf("base")), 9) as State
    assertEquals(listOf("base", "9"), result.values)
  }

  @Test fun unknownStateIsPreserved() {
    val state = Any()
    assertSame(state, calendarStateWithDay(state, 9))
  }

  @Test fun incompatibleReturnTypeIsPreserved() {
    val state = ChangedReturnType()
    assertSame(state, calendarStateWithDay(state, 9))
  }

  @Test fun throwingImplementationIsPreserved() {
    val state = ThrowingState()
    assertSame(state, calendarStateWithDay(state, 9))
  }
}
