package com.danielealbano.androidremotecontrolmcp.di

import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmClient
import com.danielealbano.androidremotecontrolmcp.agent.llm.OpenAiCompatibleLlmClient
import com.danielealbano.androidremotecontrolmcp.agent.tools.AgentToolBridge
import com.danielealbano.androidremotecontrolmcp.agent.tools.LoopbackMcpToolBridge
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentModule {
    @Binds
    @Singleton
    abstract fun bindLlmClient(impl: OpenAiCompatibleLlmClient): LlmClient

    @Binds
    @Singleton
    abstract fun bindAgentToolBridge(impl: LoopbackMcpToolBridge): AgentToolBridge
}
