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

@Module
@InstallIn(SingletonComponent::class)
interface NetworkBindings {
    @Binds
    @Singleton
    fun bindLlmAgent(agent: GeminiAgent): LlmAgent

    @Binds
    @Singleton
    fun bindGeminiTextClient(client: RestGeminiTextClient): GeminiTextClient

    @Binds
    @Singleton
    fun bindHuggingFaceTextClient(client: RestHuggingFaceTextClient): HuggingFaceTextClient
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
            .callTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
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
    @NetworkDispatcher
    fun provideNetworkDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
