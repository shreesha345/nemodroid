package com.danielealbano.androidremotecontrolmcp.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

@DisplayName("runCatchingNonCancellation")
class AgentCoroutinesTest {
    @Test
    fun `wraps ordinary exceptions`() {
        // Act
        val result = runCatchingNonCancellation<Int> { throw IllegalStateException("boom") }

        // Assert
        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `rethrows CancellationException`() {
        assertThrows(CancellationException::class.java) {
            runCatchingNonCancellation<Int> { throw CancellationException("stop") }
        }
    }

    @Test
    fun `returns success value`() {
        assertEquals(42, runCatchingNonCancellation { 42 }.getOrNull())
    }
}
