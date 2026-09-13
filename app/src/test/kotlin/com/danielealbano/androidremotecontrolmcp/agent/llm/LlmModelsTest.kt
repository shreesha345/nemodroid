package com.danielealbano.androidremotecontrolmcp.agent.llm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("LlmModels")
class LlmModelsTest {
    @Test
    fun `endpoint toString masks api key`() {
        // Arrange
        val withKey = LlmEndpoint(baseUrl = "https://h/v1", apiKey = "secret-key", model = "m")
        val withoutKey = LlmEndpoint(baseUrl = "https://h/v1", apiKey = "", model = "m")

        // Act
        val masked = withKey.toString()
        val empty = withoutKey.toString()

        // Assert
        assertTrue(masked.contains("apiKey=***"))
        assertFalse(masked.contains("secret-key"))
        assertTrue(empty.contains("apiKey=,"))
        assertFalse(empty.contains("***"))
    }
}
