package com.bydmate.app.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PollStepIsolationTest {

    @Test
    fun `success — returns the block's value, never calls onError`() = runBlocking {
        var errorCalls = 0

        val result = runIsolated("step", onError = { _, _ -> errorCalls++ }) { 42 }

        assertEquals(42, result)
        assertEquals(0, errorCalls)
    }

    @Test
    fun `failure — swallows the exception, reports it, returns null`() = runBlocking {
        var reportedLabel: String? = null
        var reportedError: Exception? = null

        val result = runIsolated(
            "odometerBuffer.onSample",
            onError = { label, error ->
                reportedLabel = label
                reportedError = error
            },
        ) {
            throw IllegalStateException("Room insert failed")
        }

        assertNull(result)
        assertEquals("odometerBuffer.onSample", reportedLabel)
        assertEquals("Room insert failed", reportedError?.message)
    }

    @Test
    fun `failure inside a suspending block is still caught`() = runBlocking {
        var errorCalls = 0

        val result = runIsolated("suspendingStep", onError = { _, _ -> errorCalls++ }) {
            delay(1)
            throw IllegalStateException("boom")
        }

        assertNull(result)
        assertEquals(1, errorCalls)
    }
}
