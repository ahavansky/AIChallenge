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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.reflect.KClass

@OptIn(ExperimentalSerializationApi::class)
class RestHuggingFaceTextClientTest {
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

            val result = client.generate(prompt = " ", modelName = "openai/gpt-oss-20b:groq")

            assertEquals(HuggingFaceResult.Failure(HuggingFaceNetworkError.EmptyPrompt), result)
            assertEquals(0, factory.callCount)
        }

    @Test
    fun generateReturnsMissingApiKeyWithoutNetworkCall() =
        runTest {
            val factory = FakeCallFactory { error("network should not be called") }
            val client = client(apiKey = "", factory = factory)

            val result = client.generate(prompt = "Hello", modelName = "openai/gpt-oss-20b:groq")

            assertEquals(HuggingFaceResult.Failure(HuggingFaceNetworkError.MissingApiKey), result)
            assertEquals(0, factory.callCount)
        }

    @Test
    fun generateMapsSuccessfulTextAndUsageResponse() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    jsonResponse(
                        request = request,
                        body =
                            """
                            {
                              "choices": [
                                {
                                  "finish_reason": "stop",
                                  "message": {"role": "assistant", "content": "Answer"}
                                }
                              ],
                              "usage": {
                                "prompt_tokens": 7,
                                "completion_tokens": 11,
                                "total_tokens": 18,
                                "completion_tokens_details": {
                                  "reasoning_tokens": 3
                                }
                              }
                            }
                            """.trimIndent(),
                    )
                }
            val client = client(factory = factory)

            val result = client.generate(prompt = "Hello", modelName = "openai/gpt-oss-20b:groq")

            assertEquals(
                HuggingFaceResult.Success(
                    HuggingFaceTextResponse(
                        text = "Answer",
                        tokenUsage =
                            HuggingFaceTokenUsage(
                                promptTokens = 7,
                                completionTokens = 11,
                                totalTokens = 18,
                                reasoningTokens = 3,
                            ),
                        metadata =
                            HuggingFaceResponseMetadata(
                                attemptCount = 1,
                                finishReasons = listOf("stop"),
                            ),
                    ),
                ),
                result,
            )
            assertEquals("https://example.test/chat/completions", factory.lastRequest?.url.toString())
            assertEquals("Bearer key", factory.lastRequest?.header("Authorization"))
            val requestBody = factory.lastRequest?.bodyString().orEmpty()
            assertTrue(requestBody.contains("\"model\":\"openai/gpt-oss-20b:groq\""))
            assertTrue(requestBody.contains("\"role\":\"user\""))
            assertTrue(requestBody.contains("\"content\":\"Hello\""))
            assertTrue(requestBody.contains("\"role\":\"system\""))
            assertTrue(requestBody.contains("\"max_tokens\":1024"))
            assertTrue(requestBody.contains("\"reasoning_effort\":\"low\""))
        }

    @Test
    fun generateMapsHttpErrors() =
        runTest {
            val factory = FakeCallFactory { request -> jsonResponse(request = request, code = 401, body = """{"error":"bad token"}""") }
            val client = client(factory = factory)

            val result = client.generate(prompt = "Hello", modelName = "openai/gpt-oss-20b:groq")

            assertTrue(result is HuggingFaceResult.Failure)
            assertEquals(401, ((result as HuggingFaceResult.Failure).error as HuggingFaceNetworkError.Http).statusCode)
            assertEquals(1, factory.callCount)
        }

    @Test
    fun generateExplainsUnsupportedModelErrors() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    jsonResponse(
                        request = request,
                        code = 400,
                        body = """{"error":{"code":"model_not_supported","message":"unsupported"}}""",
                    )
                }
            val client = client(factory = factory)

            val result = client.generate(prompt = "Hello", modelName = "google/gemma-2-2b-it")

            assertTrue(result is HuggingFaceResult.Failure)
            assertTrue((result as HuggingFaceResult.Failure).error.userMessage.contains("provider-qualified model id"))
            assertEquals(1, factory.callCount)
        }

    @Test
    fun generateRetriesTemporaryHttpErrors() =
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
                            body = """{"choices":[{"message":{"role":"assistant","content":"Recovered"}}]}""",
                        )
                    }
                }
            val client = client(factory = factory)

            val result = client.generate(prompt = "Hello", modelName = "openai/gpt-oss-20b:groq")

            assertEquals(
                HuggingFaceResult.Success(
                    HuggingFaceTextResponse(
                        text = "Recovered",
                        tokenUsage = null,
                        metadata = HuggingFaceResponseMetadata(attemptCount = 2),
                    ),
                ),
                result,
            )
            assertEquals(2, factory.callCount)
        }

    @Test
    fun generateMapsInvalidJson() =
        runTest {
            val factory = FakeCallFactory { request -> jsonResponse(request = request, body = "{") }
            val client = client(factory = factory)

            val result = client.generate(prompt = "Hello", modelName = "openai/gpt-oss-20b:groq")

            assertTrue((result as HuggingFaceResult.Failure).error is HuggingFaceNetworkError.Serialization)
        }

    @Test
    fun generateMapsEmptyModelResponse() =
        runTest {
            val factory = FakeCallFactory { request -> jsonResponse(request = request, body = """{"choices":[]}""") }
            val client = client(factory = factory)

            val result = client.generate(prompt = "Hello", modelName = "openai/gpt-oss-20b:groq")

            assertEquals(HuggingFaceResult.Failure(HuggingFaceNetworkError.EmptyResponse), result)
        }

    @Test
    fun generateExplainsReasoningOnlyLengthResponses() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    jsonResponse(
                        request = request,
                        body =
                            """
                            {
                              "choices": [
                                {
                                  "finish_reason": "length",
                                  "message": {
                                    "role": "assistant",
                                    "content": "",
                                    "reasoning": "The model kept thinking until the token limit."
                                  }
                                }
                              ],
                              "usage": {
                                "prompt_tokens": 171,
                                "completion_tokens": 1024,
                                "total_tokens": 1195
                              }
                            }
                            """.trimIndent(),
                    )
                }
            val client = client(factory = factory)

            val result = client.generate(prompt = "Hello", modelName = "openai/gpt-oss-20b:groq")

            assertEquals(HuggingFaceResult.Failure(HuggingFaceNetworkError.ReasoningOnlyResponse), result)
        }

    @Test
    fun generateMapsIoExceptions() =
        runTest {
            val factory = FakeCallFactory { throw IOException("offline") }
            val client = client(factory = factory)

            val result = client.generate(prompt = "Hello", modelName = "openai/gpt-oss-20b:groq")

            assertTrue((result as HuggingFaceResult.Failure).error is HuggingFaceNetworkError.Network)
        }

    private fun client(
        apiKey: String = "key",
        factory: FakeCallFactory,
        endpoint: String = "https://example.test/chat/completions",
    ): HuggingFaceTextClient =
        RestHuggingFaceTextClient(
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
