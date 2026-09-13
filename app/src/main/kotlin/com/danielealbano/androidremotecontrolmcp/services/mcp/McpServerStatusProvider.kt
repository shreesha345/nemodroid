package com.danielealbano.androidremotecontrolmcp.services.mcp

import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Read access to the MCP server lifecycle status for components outside [McpServerService]. */
interface McpServerStatusProvider {
    val status: StateFlow<ServerStatus>
}

class McpServerStatusProviderImpl
    @Inject
    constructor() : McpServerStatusProvider {
        override val status: StateFlow<ServerStatus>
            get() = McpServerService.serverStatus
    }
