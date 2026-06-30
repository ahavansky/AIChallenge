package com.akhavanskii.aichallenge.feature.ragindexing

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RagIndexingModule {
    @Provides
    @Singleton
    fun provideEmbeddingClient(): EmbeddingClient = OllamaEmbeddingClient()

    @Provides
    @Singleton
    fun provideRagIndexingStorage(
        @ApplicationContext context: Context,
    ): RagIndexingStorage = AndroidRagIndexingStorage(context)
}
