package com.unsupportedpastels.hermesandroid.core

import com.unsupportedpastels.mercury.core.SessionRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 0 spike: proves the app resolves and consumes the `:shared:mercury-core`
 * Android variant. Replaced by real call sites once Phase 1 migrates a policy.
 */
class SharedCoreConsumptionTest {

  @Test
  fun `shared SessionRef is usable from the app module`() {
    val ref = SessionRef(storedSessionId = "sess-1", liveRuntimeId = "run-9")
    assertEquals(SessionRef("sess-1"), ref.detached())
  }
}
