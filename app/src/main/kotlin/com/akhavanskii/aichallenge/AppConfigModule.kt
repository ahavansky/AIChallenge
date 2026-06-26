package com.akhavanskii.aichallenge

import com.akhavanskii.aichallenge.core.network.DEEPSEEK_API_KEY_NAME
import com.akhavanskii.aichallenge.core.network.GEMINI_API_KEY_NAME
import com.akhavanskii.aichallenge.core.network.HUGGINGFACE_API_KEY_NAME
import com.akhavanskii.aichallenge.core.network.MCP_SAVE_SERVER_URL_NAME
import com.akhavanskii.aichallenge.core.network.MCP_SEARCH_SERVER_URL_NAME
import com.akhavanskii.aichallenge.core.network.MCP_SERVER_URL_NAME
import com.akhavanskii.aichallenge.core.network.MCP_SUMMARIZE_SERVER_URL_NAME
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.net.URI
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object AppConfigModule {
    @Provides
    @Named(GEMINI_API_KEY_NAME)
    fun provideGeminiApiKey(): String = BuildConfig.GEMINI_API_KEY

    @Provides
    @Named(HUGGINGFACE_API_KEY_NAME)
    fun provideHuggingFaceApiKey(): String = BuildConfig.HUGGINGFACE_API_KEY

    @Provides
    @Named(DEEPSEEK_API_KEY_NAME)
    fun provideDeepSeekApiKey(): String = BuildConfig.DEEPSEEK_API_KEY

    @Provides
    @Named(MCP_SERVER_URL_NAME)
    fun provideMcpServerUrl(): String = BuildConfig.MCP_SERVER_URL.toAndroidMcpServerUrl()

    @Provides
    @Named(MCP_SEARCH_SERVER_URL_NAME)
    fun provideMcpSearchServerUrl(): String = BuildConfig.MCP_SEARCH_SERVER_URL.toAndroidMcpServerUrl()

    @Provides
    @Named(MCP_SUMMARIZE_SERVER_URL_NAME)
    fun provideMcpSummarizeServerUrl(): String = BuildConfig.MCP_SUMMARIZE_SERVER_URL.toAndroidMcpServerUrl()

    @Provides
    @Named(MCP_SAVE_SERVER_URL_NAME)
    fun provideMcpSaveServerUrl(): String = BuildConfig.MCP_SAVE_SERVER_URL.toAndroidMcpServerUrl()
}

internal fun String.toAndroidMcpServerUrl(): String {
    val trimmed = trim()
    if (trimmed.isBlank()) return DEFAULT_ANDROID_MCP_SERVER_URL

    val endpoint = if ("://" in trimmed) trimmed else "http://$trimmed"
    return runCatching {
        val uri = URI(endpoint)
        val host = uri.host?.lowercase() ?: return endpoint
        if (host != "localhost" && host != "127.0.0.1") return endpoint

        URI(
            uri.scheme ?: "http",
            uri.userInfo,
            ANDROID_EMULATOR_HOST_LOOPBACK,
            uri.port,
            uri.path,
            uri.query,
            uri.fragment,
        ).toASCIIString()
    }.getOrElse { endpoint }
}

private const val ANDROID_EMULATOR_HOST_LOOPBACK = "10.0.2.2"
internal const val DEFAULT_ANDROID_MCP_SERVER_URL = "http://10.0.2.2:8765/mcp"
internal const val DEFAULT_ANDROID_MCP_SEARCH_SERVER_URL = "http://10.0.2.2:8766/mcp"
internal const val DEFAULT_ANDROID_MCP_SUMMARIZE_SERVER_URL = "http://10.0.2.2:8767/mcp"
internal const val DEFAULT_ANDROID_MCP_SAVE_SERVER_URL = "http://10.0.2.2:8768/mcp"
