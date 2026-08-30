package com.unsupportedpastels.mercury.core

/**
 * Durable-vs-live session identity, made explicit at the type level.
 *
 * A stored session ID names the durable transcript and survives server
 * restarts; a live runtime ID names a transient in-memory runtime and must
 * never be persisted or treated as interchangeable with the stored ID.
 *
 * Phase 0 spike export: this type exists to exercise Kotlin -> Swift interop
 * (immutability, optionals, equality) before any real policy migrates.
 */
data class SessionRef(
  val storedSessionId: String,
  val liveRuntimeId: String? = null,
) {
  init {
    require(storedSessionId.isNotBlank()) { "storedSessionId must not be blank" }
    liveRuntimeId?.let {
      require(it.isNotBlank()) { "liveRuntimeId must be null or non-blank" }
    }
  }

  /** True when the session currently has a transient live runtime attached. */
  val isLive: Boolean get() = liveRuntimeId != null

  /** The same durable session with no live runtime (e.g. after disconnect). */
  fun detached(): SessionRef = copy(liveRuntimeId = null)
}
