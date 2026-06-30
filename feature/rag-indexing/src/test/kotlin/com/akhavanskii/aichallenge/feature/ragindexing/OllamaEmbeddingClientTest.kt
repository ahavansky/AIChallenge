package com.akhavanskii.aichallenge.feature.ragindexing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

class OllamaEmbeddingClientTest {
    @Test
    fun embedPostsRequestAndParsesSuccessfulEmbeddings() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    jsonResponse(
                        request = request,
                        body = """{"embeddings":[[1.0,2.0],[3.0,4.0]]}""",
                    )
                }
            val client = client(factory)

            val result =
                client.embed(
                    endpoint = "https://example.test/api/embed",
                    model = "nomic-embed-text",
                    inputs = listOf("one", "two"),
                )

            assertEquals(EmbeddingResult.Success(listOf(listOf(1.0, 2.0), listOf(3.0, 4.0))), result)
            assertEquals("https://example.test/api/embed", factory.lastRequest?.url.toString())
            val requestBody = factory.lastRequest?.bodyString().orEmpty()
            assertTrue(requestBody.contains("\"model\":\"nomic-embed-text\""))
            assertTrue(requestBody.contains("\"input\":[\"one\",\"two\"]"))
        }

    @Test
    fun embedMapsEmptyEmbeddings() =
        runTest {
            val client = client(FakeCallFactory { request -> jsonResponse(request = request, body = """{"embeddings":[]}""") })

            val result = client.embed("https://example.test/api/embed", "model", listOf("one"))

            assertEquals(EmbeddingResult.Failure(EmbeddingError.EmptyEmbeddings), result)
        }

    @Test
    fun embedMapsEmbeddingCountMismatch() =
        runTest {
            val client = client(FakeCallFactory { request -> jsonResponse(request = request, body = """{"embeddings":[[1.0]]}""") })

            val result = client.embed("https://example.test/api/embed", "model", listOf("one", "two"))

            assertEquals(
                EmbeddingResult.Failure(
                    EmbeddingError.DimensionMismatch(
                        expectedCount = 2,
                        actualCount = 1,
                    ),
                ),
                result,
            )
        }

    @Test
    fun embedMapsEmbeddingDimensionMismatch() =
        runTest {
            val client =
                client(
                    FakeCallFactory { request ->
                        jsonResponse(request = request, body = """{"embeddings":[[1.0],[2.0,3.0]]}""")
                    },
                )

            val result = client.embed("https://example.test/api/embed", "model", listOf("one", "two"))

            assertEquals(
                EmbeddingResult.Failure(
                    EmbeddingError.DimensionMismatch(
                        expectedDimensions = 1,
                        actualDimensions = 2,
                    ),
                ),
                result,
            )
        }

    @Test
    fun embedMapsMalformedJson() =
        runTest {
            val client = client(FakeCallFactory { request -> jsonResponse(request = request, body = "{") })

            val result = client.embed("https://example.test/api/embed", "model", listOf("one"))

            assertEquals(EmbeddingResult.Failure(EmbeddingError.BadResponse), result)
        }

    @Test
    fun embedMapsHttpErrorWithSnippet() =
        runTest {
            val client = client(FakeCallFactory { request -> jsonResponse(request = request, code = 404, body = "missing model") })

            val result = client.embed("https://example.test/api/embed", "model", listOf("one"))

            assertEquals(EmbeddingResult.Failure(EmbeddingError.Http(statusCode = 404, bodySnippet = "missing model")), result)
        }

    private fun client(factory: FakeCallFactory): EmbeddingClient =
        OllamaEmbeddingClient(
            callFactory = factory,
            dispatcher = Dispatchers.Unconfined,
        )

    private fun jsonResponse(
        request: Request,
        code: Int = 200,
        body: String,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun Request.bodyString(): String {
        val buffer = Buffer()
        body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private class FakeCallFactory(
        private val responseFactory: (Request) -> Response,
    ) : Call.Factory {
        var callCount: Int = 0
            private set
        var lastRequest: Request? = null
            private set

        override fun newCall(request: Request): Call {
            callCount += 1
            lastRequest = request
            return FakeCall(request) { responseFactory(request) }
        }
    }

    private class FakeCall(
        private val request: Request,
        private val responseFactory: () -> Response,
    ) : Call {
        private var executed = false
        private var canceled = false

        override fun execute(): Response {
            executed = true
            return responseFactory()
        }

        override fun enqueue(responseCallback: Callback) = error("enqueue is not used")

        override fun cancel() {
            canceled = true
        }

        override fun isExecuted(): Boolean = executed

        override fun isCanceled(): Boolean = canceled

        override fun clone(): Call = FakeCall(request, responseFactory)

        override fun request(): Request = request

        override fun timeout(): Timeout = Timeout.NONE

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(
            type: KClass<T>,
            computeIfAbsent: () -> T,
        ): T = computeIfAbsent()

        override fun <T : Any> tag(
            type: Class<T>,
            computeIfAbsent: () -> T,
        ): T = computeIfAbsent()
    }
}
