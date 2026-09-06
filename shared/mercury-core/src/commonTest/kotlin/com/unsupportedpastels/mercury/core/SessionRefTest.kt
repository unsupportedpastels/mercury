package com.unsupportedpastels.mercury.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionRefTest {

  @Test
  fun `stored-only ref is not live`() {
    val ref = SessionRef(storedSessionId = "sess-1")
    assertFalse(ref.isLive)
    assertEquals(null, ref.liveRuntimeId)
  }

  @Test
  fun `ref with runtime is live and detaches to the same durable session`() {
    val ref = SessionRef(storedSessionId = "sess-1", liveRuntimeId = "run-9")
    assertTrue(ref.isLive)
    assertEquals(SessionRef("sess-1"), ref.detached())
  }

  @Test
  fun `value equality holds`() {
    assertEquals(
      SessionRef("sess-1", "run-9"),
      SessionRef("sess-1", "run-9"),
    )
  }

  @Test
  fun `blank ids are rejected`() {
    assertFailsWith<IllegalArgumentException> { SessionRef(storedSessionId = " ") }
    assertFailsWith<IllegalArgumentException> { SessionRef("sess-1", liveRuntimeId = "") }
  }
}
