package com.akhavanskii.aichallenge.core.network

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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

@Module
@InstallIn(SingletonComponent::class)
interface NetworkBindings {
    @Binds
    @Singleton
    fun bindGeminiTextClient(client: RestGeminiTextClient): GeminiTextClient
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideJson(): Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
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
    @Named(GEMINI_ENDPOINT_NAME)
    fun provideGeminiEndpoint(): String = GEMINI_GENERATE_CONTENT_ENDPOINT

    @Provides
    @NetworkDispatcher
    fun provideNetworkDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
