package com.danielealbano.androidremotecontrolmcp.agent.core

import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmImage
import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmMessage
import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmToolCall
import com.danielealbano.androidremotecontrolmcp.agent.tools.AgentToolProfile
import com.danielealbano.androidremotecontrolmcp.agent.tools.AgentToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AgentRunContext")
class AgentRunContextTest {
    private val marker = AgentPrompts.SCREEN_TRUNCATED
    private val image = LlmImage(base64 = "AAA", mimeType = "image/jpeg")

    private fun screen(
        text: String,
        withImage: Boolean = true,
    ): AgentToolResult = AgentToolResult(text = text, image = if (withImage) image else null, isError = false)

    private fun ok(text: String = "done"): AgentToolResult = AgentToolResult(text = text, image = null, isError = false)

    private fun tap(
        x: Int = 1,
        id: String = "call_0",
    ): LlmToolCall = LlmToolCall(id = id, name = "tap", arguments = buildJsonObject { put("x", x) })

    private fun lastUserText(context: AgentRunContext): String =
        context.messages
            .filterIsInstance<LlmMessage.User>()
            .last()
            .text

    @Test
    fun `truncateAtLine returns short text unchanged`() {
        assertEquals("a\nb", truncateAtLine("a\nb", 10))
    }

    @Test
    fun `truncateAtLine cuts at line boundary and appends marker`() {
        val maxChars = marker.length + 1 + 7

        val result = truncateAtLine("aaaa\nbbbbbbb\n" + "c".repeat(200), maxChars)

        assertEquals("aaaa\n$marker", result)
        assertTrue(result.length <= maxChars)
    }

    @Test
    fun `truncateAtLine keeps trailing pagination note`() {
        val note = "note:more nodes available"
        val text = "l1\nl2\nl3\n" + "x".repeat(200) + "\n" + note
        val maxChars = marker.length + 1 + note.length + 1 + 5

        val result = truncateAtLine(text, maxChars)

        assertEquals("l1\nl2\n$marker\n$note", result)
        assertTrue(result.length <= maxChars)
    }

    @Test
    fun `truncateAtLine keeps line ending exactly at budget`() {
        val maxChars = marker.length + 1 + 10

        val result = truncateAtLine("aaaa\nbbbbb\n" + "c".repeat(200), maxChars)

        assertEquals("aaaa\nbbbbb\n$marker", result)
    }

    @Test
    fun `truncateAtLine single oversized line yields marker only`() {
        val maxChars = marker.length + 10

        val result = truncateAtLine("y".repeat(500), maxChars)

        assertEquals(marker, result)
    }

    @Test
    fun `truncateAtLine drops note longer than budget`() {
        val maxChars = marker.length + 50

        val result = truncateAtLine("a\nnote:" + "z".repeat(300), maxChars)

        assertFalse(result.contains("note:"))
        assertTrue(result.endsWith(marker))
        assertTrue(result.length <= maxChars)
    }

    @Test
    fun `starts with system prompt`() {
        val context = AgentRunContext("goal")

        assertEquals(LlmMessage.System(AgentPrompts.SYSTEM), context.messages.first())
    }

    @Test
    fun `addObservation keeps only latest image and screen`() {
        val context = AgentRunContext("goal")

        context.addObservation(1, 5, screen("first screen"))
        context.addObservation(2, 5, screen("second screen"))

        val users = context.messages.filterIsInstance<LlmMessage.User>()
        assertEquals(LlmMessage.User(AgentPrompts.OMITTED_OBSERVATION), users[0])
        assertTrue(users[1].text.contains("second screen"))
        assertNotNull(users[1].image)
        assertEquals(1, users.count { it.image != null })
    }

    @Test
    fun `addObservation truncates long screen`() {
        val context = AgentRunContext("goal")
        val longScreen = List(4_000) { "node_$it\tButton\tlabel $it" }.joinToString("\n")

        context.addObservation(1, 5, screen(longScreen))

        assertTrue(lastUserText(context).contains(marker))
    }

    @Test
    fun `addObservation includes goal step and pending hint once`() {
        val context = AgentRunContext("open settings")
        context.onMissingToolCall("no call")

        context.addObservation(1, 5, screen("s1"))
        val first = lastUserText(context)
        context.addObservation(2, 5, screen("s2"))
        val second = lastUserText(context)

        assertTrue(first.contains("Task: open settings"))
        assertTrue(first.contains("Step 1 of 5"))
        assertTrue(first.contains(AgentPrompts.NO_TOOL_CALL_HINT))
        assertFalse(second.contains(AgentPrompts.NO_TOOL_CALL_HINT))
    }

    @Test
    fun `addObservation compacts in batches`() {
        val context = AgentRunContext("goal")
        repeat(6) { context.recordAction(tap(x = it), "thought $it", ok()) }

        context.addObservation(1, 20, screen("s"))
        val afterSix = context.messages.filterIsInstance<LlmMessage.ToolResult>()
        assertTrue(afterSix.none { it.text == AgentPrompts.OMITTED_TOOL_RESULT })

        context.recordAction(tap(x = 6), "thought 6", ok())
        context.addObservation(2, 20, screen("s"))
        val toolResults = context.messages.filterIsInstance<LlmMessage.ToolResult>()
        val assistants = context.messages.filterIsInstance<LlmMessage.Assistant>()
        assertEquals(4, toolResults.count { it.text == AgentPrompts.OMITTED_TOOL_RESULT })
        assertEquals(3, toolResults.takeLast(3).count { it.text != AgentPrompts.OMITTED_TOOL_RESULT })
        assertEquals(4, assistants.count { it.text == null })

        val before = toolResults.map { it.text }
        context.addObservation(3, 20, screen("s"))
        assertEquals(before, context.messages.filterIsInstance<LlmMessage.ToolResult>().map { it.text })
    }

    @Test
    fun `recordAction appends assistant call and truncated tool result`() {
        val context = AgentRunContext("goal")
        val longResult = List(1_000) { "line $it" }.joinToString("\n")

        context.recordAction(tap(), "t1", ok(longResult))
        context.recordAction(tap(x = 2), "t2", ok())

        val toolResult = context.messages.filterIsInstance<LlmMessage.ToolResult>().first()
        assertTrue(toolResult.text.length <= AgentRunContext.MAX_TOOL_RESULT_CHARS)
        assertTrue(toolResult.text.endsWith(marker))
        assertTrue(
            context.steps
                .first()
                .resultPreview.length <= AgentRunContext.MAX_PREVIEW_CHARS,
        )
        assertEquals(listOf(1, 2), context.steps.map { it.index })
    }

    @Test
    fun `recordAction keeps model requested screen as pending observation`() {
        val context = AgentRunContext("goal")
        val page = screen("page two", withImage = false)
        val call = LlmToolCall("c1", AgentToolProfile.GET_SCREEN_STATE, buildJsonObject { put("cursor", "s.2") })

        context.recordAction(call, null, page)

        assertEquals(
            AgentPrompts.PAGE_SHOWN_AS_OBSERVATION,
            context.messages
                .filterIsInstance<LlmMessage.ToolResult>()
                .single()
                .text,
        )
        assertEquals(page, context.takePendingScreen())
        assertNull(context.takePendingScreen())
    }

    @Test
    fun `recordAction does not keep failed screen request`() {
        val context = AgentRunContext("goal")
        val failure = AgentToolResult(text = "snapshot gone", image = null, isError = true)
        val call = LlmToolCall("c1", AgentToolProfile.GET_SCREEN_STATE, JsonObject(emptyMap()))

        context.recordAction(call, null, failure)

        assertNull(context.takePendingScreen())
        assertEquals(
            "snapshot gone",
            context.messages
                .filterIsInstance<LlmMessage.ToolResult>()
                .single()
                .text,
        )
    }

    @Test
    fun `recordAction condenses thought`() {
        val context = AgentRunContext("goal")

        context.recordAction(tap(x = 1), "hidden reasoning</think>  Tap settings  ", ok())
        context.recordAction(tap(x = 2), "t".repeat(2_000), ok())
        context.recordAction(tap(x = 3), "   ", ok())

        assertEquals("Tap settings", context.steps[0].thought)
        assertEquals(AgentRunContext.MAX_THOUGHT_CHARS, context.steps[1].thought?.length)
        assertNull(context.steps[2].thought)
    }

    @Test
    fun `recordAction makes tool call ids unique per step`() {
        val context = AgentRunContext("goal")

        context.recordAction(tap(x = 1, id = "call_0"), null, ok())
        context.recordAction(tap(x = 2, id = "call_0"), null, ok())

        val callIds = context.messages.filterIsInstance<LlmMessage.Assistant>().map { it.toolCalls.single().id }
        val resultIds = context.messages.filterIsInstance<LlmMessage.ToolResult>().map { it.toolCallId }
        assertEquals(listOf("s1_call_0", "s2_call_0"), callIds)
        assertEquals(callIds, resultIds)
    }

    @Test
    fun `recordAction resets missing tool call streak`() {
        val context = AgentRunContext("goal")

        context.onMissingToolCall(null)
        context.onMissingToolCall(null)
        context.recordAction(tap(), null, ok())

        assertNull(context.onMissingToolCall(null))
        assertNull(context.onMissingToolCall(null))
    }

    @Test
    fun `onMissingToolCall fails on third consecutive miss`() {
        val context = AgentRunContext("goal")

        assertNull(context.onMissingToolCall("a"))
        assertNull(context.onMissingToolCall("b"))
        assertTrue(context.onMissingToolCall("c") is AgentRunState.Failed)
    }

    @Test
    fun `three identical actions set repeated action hint`() {
        val context = AgentRunContext("goal")
        repeat(3) { context.recordAction(tap(x = 7), null, ok()) }

        context.addObservation(1, 10, screen("s"))
        val withHint = lastUserText(context)
        repeat(2) { context.recordAction(tap(x = 7), null, ok()) }
        context.addObservation(2, 10, screen("s"))

        assertTrue(withHint.contains(AgentPrompts.REPEATED_ACTION_HINT))
        assertFalse(lastUserText(context).contains(AgentPrompts.REPEATED_ACTION_HINT))
    }

    @Test
    fun `different arguments do not trigger repetition hint`() {
        val context = AgentRunContext("goal")
        (1..3).forEach { context.recordAction(tap(x = it), null, ok()) }

        context.addObservation(1, 10, screen("s"))

        assertFalse(lastUserText(context).contains(AgentPrompts.REPEATED_ACTION_HINT))
    }

    @Test
    fun `finished uses summary argument or falls back to condensed thought`() {
        val context = AgentRunContext("goal")
        val withSummary = LlmToolCall("c1", AgentPrompts.FINISH_TOOL, buildJsonObject { put("summary", "All set") })
        val withoutSummary = LlmToolCall("c2", AgentPrompts.FINISH_TOOL, JsonObject(emptyMap()))

        assertEquals("All set", context.finished(withSummary, "ignored").summary)
        assertEquals("done", context.finished(withoutSummary, "reasoning</think>done").summary)
    }
}
