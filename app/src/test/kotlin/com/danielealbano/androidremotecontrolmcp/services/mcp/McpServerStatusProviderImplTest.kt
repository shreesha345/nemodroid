package com.danielealbano.androidremotecontrolmcp.services.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("McpServerStatusProviderImpl")
class McpServerStatusProviderImplTest {
    @Test
    fun `status mirrors McpServerService serverStatus`() {
        // Arrange
        val provider = McpServerStatusProviderImpl()

        // Act
        val status = provider.status

        // Assert
        assertSame(McpServerService.serverStatus, status)
        assertEquals(McpServerService.serverStatus.value, status.value)
    }
}
