package com.akhavanskii.aichallenge.core.network

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NetworkDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HuggingFaceCallFactory

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PipelineSearchMcpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PipelineSummarizeMcpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PipelineSaveMcpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DevProjectMcpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DevBuildMcpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DevDeviceMcpClient

@Module
@InstallIn(SingletonComponent::class)
interface NetworkBindings {
    @Binds
    @Singleton
    fun bindLlmAgent(agent: RoutingLlmAgent): LlmAgent

    @Binds
    @Singleton
    fun bindGeminiTextClient(client: RestGeminiTextClient): GeminiTextClient

    @Binds
    @Singleton
    fun bindHuggingFaceTextClient(client: RestHuggingFaceTextClient): HuggingFaceTextClient

    @Binds
    @Singleton
    fun bindMcpClient(client: RestMcpClient): McpClient
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @OptIn(ExperimentalSerializationApi::class)
    @Provides
    @Singleton
    fun provideJson(): Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .callTimeout(120, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideCallFactory(client: OkHttpClient): Call.Factory = client

    @Provides
    @Singleton
    @HuggingFaceCallFactory
    fun provideHuggingFaceCallFactory(): Call.Factory =
        OkHttpClient
            .Builder()
            .callTimeout(90, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()

    @Provides
    @Named(GEMINI_ENDPOINT_NAME)
    fun provideGeminiEndpoint(): String = GEMINI_GENERATE_CONTENT_ENDPOINT

    @Provides
    @Named(HUGGINGFACE_ENDPOINT_NAME)
    fun provideHuggingFaceEndpoint(): String = HUGGINGFACE_CHAT_COMPLETIONS_ENDPOINT

    @Provides
    @Named(DEEPSEEK_ENDPOINT_NAME)
    fun provideDeepSeekEndpoint(): String = DEEPSEEK_CHAT_COMPLETIONS_ENDPOINT

    @Provides
    @NetworkDispatcher
    fun provideNetworkDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @PipelineSearchMcpClient
    fun providePipelineSearchMcpClient(
        @Named(MCP_SEARCH_SERVER_URL_NAME) serverUrl: String,
        callFactory: Call.Factory,
        json: Json,
        @NetworkDispatcher dispatcher: CoroutineDispatcher,
    ): McpClient =
        RestMcpClient(
            serverUrl = serverUrl,
            callFactory = callFactory,
            json = json,
            dispatcher = dispatcher,
        )

    @Provides
    @Singleton
    @PipelineSummarizeMcpClient
    fun providePipelineSummarizeMcpClient(
        @Named(MCP_SUMMARIZE_SERVER_URL_NAME) serverUrl: String,
        callFactory: Call.Factory,
        json: Json,
        @NetworkDispatcher dispatcher: CoroutineDispatcher,
    ): McpClient =
        RestMcpClient(
            serverUrl = serverUrl,
            callFactory = callFactory,
            json = json,
            dispatcher = dispatcher,
        )

    @Provides
    @Singleton
    @PipelineSaveMcpClient
    fun providePipelineSaveMcpClient(
        @Named(MCP_SAVE_SERVER_URL_NAME) serverUrl: String,
        callFactory: Call.Factory,
        json: Json,
        @NetworkDispatcher dispatcher: CoroutineDispatcher,
    ): McpClient =
        RestMcpClient(
            serverUrl = serverUrl,
            callFactory = callFactory,
            json = json,
            dispatcher = dispatcher,
        )

    @Provides
    @Singleton
    @DevProjectMcpClient
    fun provideDevProjectMcpClient(
        @Named(MCP_DEV_PROJECT_SERVER_URL_NAME) serverUrl: String,
        callFactory: Call.Factory,
        json: Json,
        @NetworkDispatcher dispatcher: CoroutineDispatcher,
    ): McpClient =
        RestMcpClient(
            serverUrl = serverUrl,
            callFactory = callFactory,
            json = json,
            dispatcher = dispatcher,
        )

    @Provides
    @Singleton
    @DevBuildMcpClient
    fun provideDevBuildMcpClient(
        @Named(MCP_DEV_BUILD_SERVER_URL_NAME) serverUrl: String,
        callFactory: Call.Factory,
        json: Json,
        @NetworkDispatcher dispatcher: CoroutineDispatcher,
    ): McpClient =
        RestMcpClient(
            serverUrl = serverUrl,
            callFactory = callFactory,
            json = json,
            dispatcher = dispatcher,
        )

    @Provides
    @Singleton
    @DevDeviceMcpClient
    fun provideDevDeviceMcpClient(
        @Named(MCP_DEV_DEVICE_SERVER_URL_NAME) serverUrl: String,
        callFactory: Call.Factory,
        json: Json,
        @NetworkDispatcher dispatcher: CoroutineDispatcher,
    ): McpClient =
        RestMcpClient(
            serverUrl = serverUrl,
            callFactory = callFactory,
            json = json,
            dispatcher = dispatcher,
        )
}
