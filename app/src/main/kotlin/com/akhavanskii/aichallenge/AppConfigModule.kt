package com.akhavanskii.aichallenge

import com.akhavanskii.aichallenge.core.network.DEEPSEEK_API_KEY_NAME
import com.akhavanskii.aichallenge.core.network.GEMINI_API_KEY_NAME
import com.akhavanskii.aichallenge.core.network.HUGGINGFACE_API_KEY_NAME
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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
}
