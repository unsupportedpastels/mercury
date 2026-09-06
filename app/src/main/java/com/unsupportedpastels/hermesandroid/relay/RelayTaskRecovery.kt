package com.unsupportedpastels.hermesandroid.relay

import com.unsupportedpastels.hermesandroid.gateway.*

/** Native ID/JSON adapter only; all provenance and reconciliation lives in MercuryCore. */
internal fun BackgroundTasks.recoverRelayTasks(
    snapshot: RelayLeaseSnapshot,
    durableId: String,
    profile: String,
    runtime: RuntimeSessionId? = null,
): BackgroundTasks = shared().recoverRelayTasks(snapshot.shared(), durableId, profile, runtime?.value).native()
