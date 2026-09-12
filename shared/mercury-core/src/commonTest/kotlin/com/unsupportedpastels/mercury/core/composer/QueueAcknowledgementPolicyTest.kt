package com.unsupportedpastels.mercury.core.composer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueueAcknowledgementPolicyTest {
    @Test fun queuedAndIdleClaimAreBothAcceptedWithoutReplay() {
        assertEquals(QueueAcknowledgement.Queued, QueueAcknowledgementPolicy.classify("queued"))
        assertEquals(QueueAcknowledgement.Streaming, QueueAcknowledgementPolicy.classify("streaming"))
        assertTrue(QueueAcknowledgementPolicy.classify("streaming").accepted)
    }
    @Test fun olderAcceptedBehaviorIsNotMisreportedAsQueued() {
        for (status in listOf("steered", "redirected")) {
            assertEquals(QueueAcknowledgement.OtherAccepted, QueueAcknowledgementPolicy.classify(status))
            assertTrue(QueueAcknowledgementPolicy.classify(status).accepted)
        }
    }
    @Test fun unknownIsNotAcceptanceOrPermissionToReplay() {
        for (status in listOf(null, "", "ok", "future-status")) {
            assertEquals(QueueAcknowledgement.Unknown, QueueAcknowledgementPolicy.classify(status))
            assertFalse(QueueAcknowledgementPolicy.classify(status).accepted)
        }
    }
}
