package com.unsupportedpastels.hermesandroid.ui

import com.unsupportedpastels.hermesandroid.files.ManagedVideoMedia
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedVideoPresentationLeaseTest {
    @Test fun disposalClosesCurrentAndRejectsLateResults() {
        val owner = ManagedVideoPresentationLease()
        var closed = 0
        assertTrue(owner.adopt(ManagedVideoMedia(File("first.mp4"), "video/mp4") { closed++ }))
        owner.close()
        owner.close()
        assertEquals(1, closed)
        assertFalse(owner.adopt(ManagedVideoMedia(File("late.mp4"), "video/mp4") { closed++ }))
        assertEquals(2, closed)
    }

    @Test fun replacementClosesOnlyPreviousLease() {
        val owner = ManagedVideoPresentationLease()
        var firstClosed = 0
        var secondClosed = 0
        assertTrue(owner.adopt(ManagedVideoMedia(File("clip.mp4"), "video/mp4") { firstClosed++ }))
        assertTrue(owner.adopt(ManagedVideoMedia(File("clip.mp4"), "video/mp4") { secondClosed++ }))
        assertEquals(1, firstClosed)
        assertEquals(0, secondClosed)
        owner.close()
        assertEquals(1, secondClosed)
    }
}
