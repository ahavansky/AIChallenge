package com.akhavanskii.aichallenge.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.reflect.KClass

@OptIn(ExperimentalSerializationApi::class)
class RestGeminiTextClientTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun generateReturnsEmptyPromptWithoutNetworkCall() =
        runTest {
            val factory = FakeCallFactory { error("network should not be called") }
            val client = client(apiKey = "key", factory = factory)

            val result = client.generate(" ", generationConfig = null)

            assertEquals(GeminiResult.Failure(GeminiNetworkError.EmptyPrompt), result)
            assertEquals(0, factory.callCount)
        }

    @Test
    fun generateReturnsMissingApiKeyWithoutNetworkCall() =
        runTest {
            val factory = FakeCallFactory { error("network should not be called") }
            val client = client(apiKey = "", factory = factory)

            val result = client.generate("Hello", generationConfig = null)

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

            val result = client.generate("Hello", generationConfig = null)

            assertEquals(GeminiResult.Success("Answer"), result)
            assertEquals("https://example.test/generate", factory.lastRequest?.url.toString())
            assertEquals("key", factory.lastRequest?.header("x-goog-api-key"))
            assertFalse(
                factory.lastRequest
                    ?.bodyString()
                    .orEmpty()
                    .contains("generationConfig"),
            )
        }

    @Test
    fun generateSerializesGenerationConfig() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    jsonResponse(
                        request = request,
                        body = """{"candidates":[{"content":{"parts":[{"text":"Configured"}]}}]}""",
                    )
                }
            val client = client(factory = factory)

            val result =
                client.generate(
                    prompt = "Hello",
                    generationConfig =
                        GeminiGenerationConfig(
                            responseMimeType = "application/json",
                            responseSchemaJson = """{"type":"object","properties":{"answer":{"type":"string"}}}""",
                            maxOutputTokens = 64,
                            stopSequences = listOf("END"),
                            temperature = 0.2,
                            topP = 0.9,
                            topK = 20,
                            candidateCount = 2,
                            presencePenalty = 0.3,
                            frequencyPenalty = 0.4,
                        ),
                )

            assertEquals(GeminiResult.Success("Configured"), result)
            val body = factory.lastRequest?.bodyString().orEmpty()
            assertTrue(body.contains("\"generationConfig\""))
            assertTrue(body.contains("\"responseMimeType\":\"application/json\""))
            assertTrue(body.contains("\"responseSchema\":{\"type\":\"object\""))
            assertTrue(body.contains("\"maxOutputTokens\":64"))
            assertTrue(body.contains("\"stopSequences\":[\"END\"]"))
            assertTrue(body.contains("\"temperature\":0.2"))
            assertTrue(body.contains("\"topP\":0.9"))
            assertTrue(body.contains("\"topK\":20"))
            assertTrue(body.contains("\"candidateCount\":2"))
            assertTrue(body.contains("\"presencePenalty\":0.3"))
            assertTrue(body.contains("\"frequencyPenalty\":0.4"))
        }

    @Test
    fun generateOmitsPenaltiesForGemini35Flash() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    jsonResponse(
                        request = request,
                        body = """{"candidates":[{"content":{"parts":[{"text":"Configured"}]}}]}""",
                    )
                }
            val client = client(factory = factory, endpoint = GEMINI_GENERATE_CONTENT_ENDPOINT)

            val result =
                client.generate(
                    prompt = "Hello",
                    generationConfig =
                        GeminiGenerationConfig(
                            temperature = 0.2,
                            presencePenalty = 0.3,
                            frequencyPenalty = 0.4,
                        ),
                )

            assertEquals(GeminiResult.Success("Configured"), result)
            val body = factory.lastRequest?.bodyString().orEmpty()
            assertTrue(body.contains("\"generationConfig\""))
            assertTrue(body.contains("\"temperature\":0.2"))
            assertFalse(body.contains("presencePenalty"))
            assertFalse(body.contains("frequencyPenalty"))
        }

    @Test
    fun generateMapsHttpErrors() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    jsonResponse(request = request, code = 429, body = """{"error":"quota"}""")
                }
            val client = client(factory = factory)

            val result = client.generate("Hello", generationConfig = null)

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

            val result = client.generate("Hello", generationConfig = null)

            assertEquals(GeminiResult.Success("Recovered"), result)
            assertEquals(2, factory.callCount)
        }

    @Test
    fun generateMapsInvalidJson() =
        runTest {
            val factory = FakeCallFactory { request -> jsonResponse(request = request, body = "{") }
            val client = client(factory = factory)

            val result = client.generate("Hello", generationConfig = null)

            assertTrue((result as GeminiResult.Failure).error is GeminiNetworkError.Serialization)
        }

    @Test
    fun generateMapsEmptyModelResponse() =
        runTest {
            val factory = FakeCallFactory { request -> jsonResponse(request = request, body = """{"candidates":[]}""") }
            val client = client(factory = factory)

            val result = client.generate("Hello", generationConfig = null)

            assertEquals(GeminiResult.Failure(GeminiNetworkError.EmptyResponse), result)
        }

    @Test
    fun generateMapsIoExceptions() =
        runTest {
            val factory = FakeCallFactory { throw IOException("offline") }
            val client = client(factory = factory)

            val result = client.generate("Hello", generationConfig = null)

            assertTrue((result as GeminiResult.Failure).error is GeminiNetworkError.Network)
        }

    private fun client(
        apiKey: String = "key",
        factory: FakeCallFactory,
        endpoint: String = "https://example.test/generate",
    ): RestGeminiTextClient =
        RestGeminiTextClient(
            apiKey = apiKey,
            endpoint = endpoint,
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
