package com.danielealbano.androidremotecontrolmcp.data.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AgentConfig")
class AgentConfigTest {
    @Test
    fun `toString masks non empty api key`() {
        // Arrange
        val config = AgentConfig(llmBaseUrl = "https://h/v1", llmApiKey = "secret-key-123")

        // Act
        val text = config.toString()

        // Assert
        assertTrue(text.contains("llmApiKey=***"))
        assertFalse(text.contains("secret-key-123"))
        assertTrue(text.contains("llmBaseUrl=https://h/v1"))
    }

    @Test
    fun `toString shows empty api key as empty`() {
        // Arrange
        val config = AgentConfig()

        // Act
        val text = config.toString()

        // Assert
        assertTrue(text.contains("llmApiKey=,"))
        assertFalse(text.contains("***"))
    }
}
