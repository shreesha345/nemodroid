package com.danielealbano.androidremotecontrolmcp.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.danielealbano.androidremotecontrolmcp.data.model.AgentConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("AgentSettingsImpl")
class AgentSettingsImplTest {
    @TempDir
    lateinit var tempDir: File

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val serverLog = RecordingServerLogRepository()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settings: AgentSettingsImpl

    private var fileCounter = 0

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0

        fileCounter++
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { File(tempDir, "agent_settings_$fileCounter.preferences_pb") },
            )
        settings = AgentSettingsImpl(dataStore, SettingsChangeLogger(serverLog, testDispatcher, WINDOW))
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `agentConfig emits defaults on empty store`() =
        testScope.runTest {
            assertEquals(AgentConfig(), settings.agentConfig.first())
            assertEquals("", settings.getAgentConfig().llmBaseUrl)
            assertEquals(AgentConfig.DEFAULT_MODEL, settings.getAgentConfig().llmModel)
            assertTrue(settings.getAgentConfig().sendScreenshot)
            assertEquals(AgentConfig.DEFAULT_MAX_STEPS, settings.getAgentConfig().maxSteps)
        }

    @Test
    fun `updateAgentLlmBaseUrl persists value`() =
        testScope.runTest {
            settings.updateAgentLlmBaseUrl("https://x.trycloudflare.com/v1")
            assertEquals("https://x.trycloudflare.com/v1", settings.getAgentConfig().llmBaseUrl)
        }

    @Test
    fun `updateAgentLlmApiKey persists value`() =
        testScope.runTest {
            settings.updateAgentLlmApiKey("key-123")
            assertEquals("key-123", settings.getAgentConfig().llmApiKey)
        }

    @Test
    fun `updateAgentLlmApiKey with empty string clears key`() =
        testScope.runTest {
            settings.updateAgentLlmApiKey("key-123")
            settings.updateAgentLlmApiKey("")
            assertEquals("", settings.getAgentConfig().llmApiKey)
        }

    @Test
    fun `updateAgentLlmModel persists value`() =
        testScope.runTest {
            settings.updateAgentLlmModel("nemotron-nano-12b-v2-vl")
            assertEquals("nemotron-nano-12b-v2-vl", settings.getAgentConfig().llmModel)
        }

    @Test
    fun `updateAgentSendScreenshot persists value`() =
        testScope.runTest {
            settings.updateAgentSendScreenshot(false)
            assertFalse(settings.getAgentConfig().sendScreenshot)
        }

    @Test
    fun `updateAgentMaxSteps persists value`() =
        testScope.runTest {
            settings.updateAgentMaxSteps(12)
            assertEquals(12, settings.getAgentConfig().maxSteps)
        }

    @Test
    fun `api key and base url changes never log values`() =
        testScope.runTest {
            settings.updateAgentLlmBaseUrl("https://secret-host.trycloudflare.com/v1")
            settings.updateAgentLlmApiKey("super-secret-api-key")
            advanceUntilIdle()

            val messages = serverLog.ofType(ServerLogEntry.Type.SETTINGS).map { it.message }
            assertTrue(messages.contains("Agent LLM endpoint changed"))
            assertTrue(messages.contains("Agent LLM API key changed"))
            assertTrue(messages.none { it.contains("secret-host") || it.contains("super-secret-api-key") })
        }

    @Test
    fun `validateAgentLlmBaseUrl accepts http and https with host`() {
        assertEquals(
            "http://127.0.0.1:8000/v1",
            settings.validateAgentLlmBaseUrl(" http://127.0.0.1:8000/v1 ").getOrNull(),
        )
        assertEquals(
            "https://x.trycloudflare.com/v1",
            settings.validateAgentLlmBaseUrl("https://x.trycloudflare.com/v1").getOrNull(),
        )
    }

    @Test
    fun `validateAgentLlmBaseUrl accepts registry based hostnames`() {
        assertTrue(settings.validateAgentLlmBaseUrl("http://gpu_box:8000/v1").isSuccess)
    }

    @Test
    fun `validateAgentLlmBaseUrl rejects blank, missing host, other schemes, malformed`() {
        listOf("", "https://", "http://:8000/v1", "ftp://h/v1", "ht tp://x").forEach { url ->
            assertTrue(settings.validateAgentLlmBaseUrl(url).isFailure, "expected failure for '$url'")
        }
    }

    @Test
    fun `validateAgentLlmBaseUrl rejects embedded credentials`() {
        assertTrue(settings.validateAgentLlmBaseUrl("https://user:pass@h/v1").isFailure)
        assertTrue(settings.validateAgentLlmBaseUrl("http://user@gpu_box/v1").isFailure)
    }

    @Test
    fun `validateAgentLlmModel trims and bounds length`() {
        assertEquals("m", settings.validateAgentLlmModel(" m ").getOrNull())
        assertTrue(settings.validateAgentLlmModel("a".repeat(AgentConfig.MAX_MODEL_LENGTH)).isSuccess)
        assertTrue(settings.validateAgentLlmModel("a".repeat(AgentConfig.MAX_MODEL_LENGTH + 1)).isFailure)
        assertTrue(settings.validateAgentLlmModel("   ").isFailure)
    }

    @Test
    fun `validateAgentMaxSteps bounds`() {
        assertTrue(settings.validateAgentMaxSteps(AgentConfig.MIN_MAX_STEPS).isSuccess)
        assertTrue(settings.validateAgentMaxSteps(AgentConfig.MAX_MAX_STEPS).isSuccess)
        assertTrue(settings.validateAgentMaxSteps(AgentConfig.MIN_MAX_STEPS - 1).isFailure)
        assertTrue(settings.validateAgentMaxSteps(AgentConfig.MAX_MAX_STEPS + 1).isFailure)
    }

    companion object {
        private const val WINDOW = 1_000L
    }
}
