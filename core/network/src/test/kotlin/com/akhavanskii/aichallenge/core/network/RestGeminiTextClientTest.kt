package com.akhavanskii.aichallenge.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.reflect.KClass

class RestGeminiTextClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun generateReturnsEmptyPromptWithoutNetworkCall() =
        runTest {
            val factory = FakeCallFactory { error("network should not be called") }
            val client = client(apiKey = "key", factory = factory)

            val result = client.generate(" ")

            assertEquals(GeminiResult.Failure(GeminiNetworkError.EmptyPrompt), result)
            assertEquals(0, factory.callCount)
        }

    @Test
    fun generateReturnsMissingApiKeyWithoutNetworkCall() =
        runTest {
            val factory = FakeCallFactory { error("network should not be called") }
            val client = client(apiKey = "", factory = factory)

            val result = client.generate("Hello")

            assertEquals(GeminiResult.Failure(GeminiNetworkError.MissingApiKey), result)
            assertEquals(0, factory.callCount)
        }

    @Test
    fun generateMapsSuccessfulTextResponse() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    jsonResponse(
                        request = request,
                        body = """{"candidates":[{"content":{"parts":[{"text":"Answer"}]}}]}""",
                    )
                }
            val client = client(factory = factory)

            val result = client.generate("Hello")

            assertEquals(GeminiResult.Success("Answer"), result)
            assertEquals("https://example.test/generate", factory.lastRequest?.url.toString())
            assertEquals("key", factory.lastRequest?.header("x-goog-api-key"))
        }

    @Test
    fun generateMapsHttpErrors() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    jsonResponse(request = request, code = 429, body = """{"error":"quota"}""")
                }
            val client = client(factory = factory)

            val result = client.generate("Hello")

            assertTrue(result is GeminiResult.Failure)
            assertEquals(429, ((result as GeminiResult.Failure).error as GeminiNetworkError.Http).statusCode)
        }

    @Test
    fun generateRetriesUnavailableResponses() =
        runTest {
            var attempts = 0
            val factory =
                FakeCallFactory { request ->
                    attempts += 1
                    if (attempts == 1) {
                        jsonResponse(request = request, code = 503, body = """{"error":"unavailable"}""")
                    } else {
                        jsonResponse(
                            request = request,
                            body = """{"candidates":[{"content":{"parts":[{"text":"Recovered"}]}}]}""",
                        )
                    }
                }
            val client = client(factory = factory)

            val result = client.generate("Hello")

            assertEquals(GeminiResult.Success("Recovered"), result)
            assertEquals(2, factory.callCount)
        }

    @Test
    fun generateMapsInvalidJson() =
        runTest {
            val factory = FakeCallFactory { request -> jsonResponse(request = request, body = "{") }
            val client = client(factory = factory)

            val result = client.generate("Hello")

            assertTrue((result as GeminiResult.Failure).error is GeminiNetworkError.Serialization)
        }

    @Test
    fun generateMapsEmptyModelResponse() =
        runTest {
            val factory = FakeCallFactory { request -> jsonResponse(request = request, body = """{"candidates":[]}""") }
            val client = client(factory = factory)

            val result = client.generate("Hello")

            assertEquals(GeminiResult.Failure(GeminiNetworkError.EmptyResponse), result)
        }

    @Test
    fun generateMapsIoExceptions() =
        runTest {
            val factory = FakeCallFactory { throw IOException("offline") }
            val client = client(factory = factory)

            val result = client.generate("Hello")

            assertTrue((result as GeminiResult.Failure).error is GeminiNetworkError.Network)
        }

    private fun client(
        apiKey: String = "key",
        factory: FakeCallFactory,
    ): RestGeminiTextClient =
        RestGeminiTextClient(
            apiKey = apiKey,
            endpoint = "https://example.test/generate",
            callFactory = factory,
            json = json,
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
