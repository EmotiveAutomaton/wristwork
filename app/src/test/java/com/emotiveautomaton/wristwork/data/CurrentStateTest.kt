package com.emotiveautomaton.wristwork.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class CurrentStateTest {
    @Test
    fun matchingDismissalRetiresPendingPrompt() {
        assertTrue(shouldClearPendingPrompt(true, "question-2", "question-2"))
    }

    @Test
    fun olderNotificationCannotRetireNewerPrompt() {
        assertFalse(shouldClearPendingPrompt(true, "question-2", "question-1"))
    }

    @Test
    fun dismissalCannotClearAlreadyRetiredState() {
        assertFalse(shouldClearPendingPrompt(false, "question-2", "question-2"))
    }

    @Test
    fun markerRetiresAfterItLeavesSixHourTimeline() {
        val now = OffsetDateTime.parse("2026-09-11T12:00:00-07:00").toInstant().toEpochMilli()
        assertFalse(pendingPromptIsOutsideTimeline("2026-09-11T06:00:00-07:00", now))
        assertTrue(pendingPromptIsOutsideTimeline("2026-09-11T05:59:59-07:00", now))
    }

    @Test
    fun malformedPendingPromptCannotHoldFaceOnNew() {
        assertTrue(pendingPromptIsOutsideTimeline(null, 0L))
        assertTrue(pendingPromptIsOutsideTimeline("not-a-time", 0L))
    }
}
