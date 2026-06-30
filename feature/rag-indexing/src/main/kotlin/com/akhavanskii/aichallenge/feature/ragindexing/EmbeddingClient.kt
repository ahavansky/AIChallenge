package com.akhavanskii.aichallenge.feature.ragindexing

interface EmbeddingClient {
    suspend fun embed(
        endpoint: String,
        model: String,
        inputs: List<String>,
    ): EmbeddingResult
}

sealed interface EmbeddingResult {
    data class Success(
        val embeddings: List<List<Double>>,
    ) : EmbeddingResult

    data class Failure(
        val error: EmbeddingError,
    ) : EmbeddingResult
}

sealed interface EmbeddingError {
    data object Unreachable : EmbeddingError

    data object Timeout : EmbeddingError

    data class Http(
        val statusCode: Int,
        val bodySnippet: String?,
    ) : EmbeddingError

    data object BadResponse : EmbeddingError

    data object EmptyEmbeddings : EmbeddingError

    data class DimensionMismatch(
        val expectedCount: Int? = null,
        val actualCount: Int? = null,
        val expectedDimensions: Int? = null,
        val actualDimensions: Int? = null,
    ) : EmbeddingError
}

fun EmbeddingError.userMessage(): String =
    when (this) {
        EmbeddingError.Unreachable -> "Ollama endpoint is unreachable. Check that Ollama is running and endpoint is correct."
        EmbeddingError.Timeout -> "Ollama request timed out. Check model availability or try again."
        is EmbeddingError.Http -> "Ollama returned HTTP $statusCode${bodySnippet?.let { ": $it" }.orEmpty()}"
        EmbeddingError.BadResponse -> "Ollama returned malformed response."
        EmbeddingError.EmptyEmbeddings -> "Ollama returned empty embeddings."
        is EmbeddingError.DimensionMismatch -> {
            val count =
                if (expectedCount != null && actualCount != null) {
                    " count expected=$expectedCount actual=$actualCount"
                } else {
                    ""
                }
            val dimensions =
                if (expectedDimensions != null && actualDimensions != null) {
                    " dimensions expected=$expectedDimensions actual=$actualDimensions"
                } else {
                    ""
                }
            "Ollama returned inconsistent embedding dimensions.$count$dimensions"
        }
    }

internal fun validateEmbeddingVectors(
    embeddings: List<List<Double>>,
    expectedInputCount: Int,
): EmbeddingError? {
    if (embeddings.isEmpty()) {
        return EmbeddingError.EmptyEmbeddings
    }
    if (embeddings.size != expectedInputCount) {
        return EmbeddingError.DimensionMismatch(
            expectedCount = expectedInputCount,
            actualCount = embeddings.size,
        )
    }

    val expectedDimensions = embeddings.first().size
    if (expectedDimensions == 0) {
        return EmbeddingError.EmptyEmbeddings
    }

    embeddings.forEach { embedding ->
        if (embedding.isEmpty()) {
            return EmbeddingError.EmptyEmbeddings
        }
        if (embedding.size != expectedDimensions) {
            return EmbeddingError.DimensionMismatch(
                expectedDimensions = expectedDimensions,
                actualDimensions = embedding.size,
            )
        }
    }

    return null
}
