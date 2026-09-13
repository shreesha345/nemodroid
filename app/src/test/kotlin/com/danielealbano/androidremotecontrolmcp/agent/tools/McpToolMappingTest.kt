package com.danielealbano.androidremotecontrolmcp.agent.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("McpToolMapping")
class McpToolMappingTest {
    private val running = ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1")
    private val httpConfig = ServerConfig(bearerToken = "token")
    private val tapSchema =
        ToolSchema(
            properties = buildJsonObject { putJsonObject("x") { put("type", "number") } },
            required = listOf("x"),
        )

    private fun tool(
        name: String,
        schema: ToolSchema = tapSchema,
        description: String? = "Tap",
    ): Tool = Tool(name = name, inputSchema = schema, description = description)

    @Test
    fun `precondition error when server not running`() {
        listOf(ServerStatus.Stopped, ServerStatus.Starting, ServerStatus.Stopping, ServerStatus.Error("boom"))
            .forEach { status -> assertNotNull(loopbackPreconditionError(status, httpConfig), "status $status") }
    }

    @Test
    fun `precondition error when https enabled in config or running status`() {
        val fromConfig = loopbackPreconditionError(running, httpConfig.copy(httpsEnabled = true))
        val fromStatus = loopbackPreconditionError(running.copy(httpsEnabled = true), httpConfig)

        listOf(fromConfig, fromStatus).forEach { assertTrue(it.orEmpty().contains("HTTPS")) }
    }

    @Test
    fun `precondition error when bearer enabled with empty token`() {
        val error = loopbackPreconditionError(running, ServerConfig(bearerToken = "", bearerTokenEnabled = true))

        assertTrue(error.orEmpty().contains("Set a bearer token"))
    }

    @Test
    fun `precondition error when bearer disabled and oauth enabled`() {
        val error = loopbackPreconditionError(running, httpConfig.copy(bearerTokenEnabled = false, oauthEnabled = true))

        assertTrue(error.orEmpty().contains("bearer token authentication"))
    }

    @Test
    fun `no precondition error with bearer enabled over http`() {
        assertNull(loopbackPreconditionError(running, httpConfig))
    }

    @Test
    fun `no precondition error when both auth methods disabled`() {
        assertNull(loopbackPreconditionError(running, ServerConfig(bearerTokenEnabled = false, oauthEnabled = false)))
    }

    @Test
    fun `toAgentDefinition strips prefix for profile tool`() {
        val definition = tool("android_tap").toAgentDefinition("android_")

        assertNotNull(definition)
        assertEquals("tap", definition?.name)
        assertEquals("Tap", definition?.description)
        val parameters = definition?.parameters ?: JsonObject(emptyMap())
        assertEquals("object", parameters.getValue("type").jsonPrimitive.content)
        assertEquals(tapSchema.properties, parameters.getValue("properties"))
        assertEquals(listOf("x"), parameters.getValue("required").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `toAgentDefinition honors device slug prefix`() {
        assertEquals("tap", tool("android_pixel7_tap").toAgentDefinition("android_pixel7_")?.name)
    }

    @Test
    fun `toAgentDefinition returns null for non profile tool`() {
        assertNull(tool("android_delete_file").toAgentDefinition("android_"))
    }

    @Test
    fun `toAgentDefinition returns null for other prefix`() {
        assertNull(tool("android_pixel7_tap").toAgentDefinition("android_"))
    }

    @Test
    fun `toAgentDefinition defaults missing properties and required`() {
        val definition =
            tool("android_press_back", ToolSchema(properties = null, required = null), description = null)
                .toAgentDefinition("android_")

        val parameters = definition?.parameters ?: JsonObject(emptyMap())
        assertEquals(JsonObject(emptyMap()), parameters.getValue("properties"))
        assertTrue(parameters.getValue("required").jsonArray.isEmpty())
        assertEquals("", definition?.description)
    }

    @Test
    fun `toAgentResult joins text and takes first image`() {
        val result =
            CallToolResult(
                content =
                    listOf(
                        TextContent(text = "warning"),
                        TextContent(text = "nodes"),
                        ImageContent(data = "AAA", mimeType = "image/jpeg"),
                        ImageContent(data = "BBB", mimeType = "image/png"),
                    ),
                isError = true,
            ).toAgentResult()

        assertEquals("warning\nnodes", result.text)
        assertEquals("AAA", result.image?.base64)
        assertEquals("image/jpeg", result.image?.mimeType)
        assertTrue(result.isError)
    }

    @Test
    fun `toAgentResult maps isError null to false`() {
        assertFalse(CallToolResult(content = listOf(TextContent(text = "ok")), isError = null).toAgentResult().isError)
    }
}
