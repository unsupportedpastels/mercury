package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import com.unsupportedpastels.hermesandroid.app.DurableSessionId

/**
 * Activity-owned, like the connection ViewModel that continues pending RPCs.
 * Recreating a presentation must not lose its captured draft/reference receipt.
 * Do not persist duplicate prompt contents in the saved-state Bundle, and never
 * replay an operation from this owner (including after process death).
 */
internal class ComposerSubmissionOwner : ViewModel() {
    private var scope: String? = null
    private var pending = mutableStateMapOf<DurableSessionId, PendingComposerSubmission>()

    fun submissions(scopeKey: String): SnapshotStateMap<DurableSessionId, PendingComposerSubmission> {
        if (scope != scopeKey) {
            scope = scopeKey
            // Replace, rather than clear: callbacks/effects from an old scope
            // keep their old map and cannot mutate the new scope's ownership.
            pending = mutableStateMapOf()
        }
        return pending
    }

    companion object {
        // Fail closed at admission; never evict an unresolved acknowledgement.
        const val MAX_PENDING = 64
    }
}
