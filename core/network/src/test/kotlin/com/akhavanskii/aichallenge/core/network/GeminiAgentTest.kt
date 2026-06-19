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
class GeminiAgentTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun sendMessageReturnsEmptyPromptWithoutNetworkCall() =
        runTest {
            val factory = FakeCallFactory { error("network should not be called") }
            val client = client(apiKey = "key", factory = factory)

            val result = client.sendMessage(" ", generationConfig = null)

            assertEquals(GeminiResult.Failure(GeminiNetworkError.EmptyPrompt), result)
            assertEquals(0, factory.callCount)
        }

    @Test
    fun sendMessageReturnsMissingApiKeyWithoutNetworkCall() =
        runTest {
            val factory = FakeCallFactory { error("network should not be called") }
            val client = client(apiKey = "", factory = factory)

            val result = client.sendMessage("Hello", generationConfig = null)

            assertEquals(GeminiResult.Failure(GeminiNetworkError.MissingApiKey), result)
            assertEquals(0, factory.callCount)
        }

    @Test
    fun sendMessageMapsSuccessfulTextResponse() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    jsonResponse(
                        request = request,
                        body = """{"candidates":[{"content":{"parts":[{"text":"Answer"}]}}]}""",
                    )
                }
            val client = client(factory = factory)

            val result = client.sendMessage("Hello", generationConfig = null)

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
    fun sendMessageSerializesSystemInstructionOutsideContents() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    jsonResponse(
                        request = request,
                        body = """{"candidates":[{"content":{"parts":[{"text":"Answer"}]}}]}""",
                    )
                }
            val client = client(factory = factory)

            val result =
                client.sendMessage(
                    prompt = "Hello",
                    systemInstruction = "Answer for a senior Kotlin developer.",
                    generationConfig = null,
                )

            assertEquals(GeminiResult.Success("Answer"), result)
            val body = factory.lastRequest?.bodyString().orEmpty()
            assertTrue(body.contains("\"systemInstruction\""))
            assertTrue(body.contains("\"text\":\"Answer for a senior Kotlin developer.\""))
            assertTrue(body.contains("\"contents\""))
            assertTrue(body.contains("\"text\":\"Hello\""))
        }

    @Test
    fun sendMessageMapsTokenUsageForCurrentRequestHistoryAndModelResponse() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    if (request.url.toString().endsWith(":countTokens")) {
                        jsonResponse(
                            request = request,
                            body = """{"totalTokens":4}""",
                        )
                    } else {
                        jsonResponse(
                            request = request,
                            body =
                                """
                                {
                                  "candidates": [
                                    {
                                      "content": {
                                        "parts": [
                                          {"text": "Follow-up answer"}
                                        ]
                                      }
                                    }
                                  ],
                                  "usageMetadata": {
                                    "promptTokenCount": 14,
                                    "candidatesTokenCount": 6,
                                    "totalTokenCount": 20
                                  }
                                }
                                """.trimIndent(),
                        )
                    }
                }
            val client = client(factory = factory, endpoint = GEMINI_GENERATE_CONTENT_ENDPOINT)

            val result =
                client.sendMessage(
                    messages =
                        listOf(
                            AgentMessage.User("First question"),
                            AgentMessage.Model("First answer"),
                            AgentMessage.User("Follow-up"),
                        ),
                    generationConfig = null,
                )

            assertEquals(
                GeminiResult.Success(
                    value = "Follow-up answer",
                    tokenUsage =
                        GeminiTokenUsage(
                            currentRequestTokens = 4,
                            conversationHistoryTokens = 14,
                            modelResponseTokens = 6,
                            totalTokens = 20,
                        ),
                ),
                result,
            )
            assertEquals(2, factory.callCount)
            assertTrue(
                factory.requests[0]
                    .url
                    .toString()
                    .endsWith(":countTokens"),
            )
            assertTrue(factory.requests[0].bodyString().contains("\"text\":\"Follow-up\""))
            assertFalse(factory.requests[0].bodyString().contains("First answer"))
            assertTrue(
                factory.requests[1]
                    .url
                    .toString()
                    .endsWith(":generateContent"),
            )
            assertTrue(factory.requests[1].bodyString().contains("\"text\":\"First answer\""))
        }

    @Test
    fun countTokensUsesGeminiCountTokensEndpoint() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    jsonResponse(
                        request = request,
                        body = """{"totalTokens":42}""",
                    )
                }
            val client = client(factory = factory, endpoint = GEMINI_GENERATE_CONTENT_ENDPOINT)

            val result =
                client.countTokens(
                    messages =
                        listOf(
                            AgentMessage.User("First question"),
                            AgentMessage.Model("First answer"),
                            AgentMessage.User("Follow-up"),
                        ),
                    modelName = "gemini-2.5-flash-lite",
                )

            assertEquals(GeminiResult.Success(42), result)
            assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:countTokens",
                factory.lastRequest?.url.toString(),
            )
            val body = factory.lastRequest?.bodyString().orEmpty()
            assertTrue(body.contains("\"text\":\"First question\""))
            assertTrue(body.contains("\"text\":\"First answer\""))
            assertTrue(body.contains("\"text\":\"Follow-up\""))
        }

    @Test
    fun countTokensIncludesSystemInstructionInGenerateContentRequest() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    jsonResponse(
                        request = request,
                        body = """{"totalTokens":52}""",
                    )
                }
            val client = client(factory = factory, endpoint = GEMINI_GENERATE_CONTENT_ENDPOINT)

            val result =
                client.countTokens(
                    messages = listOf(AgentMessage.User("Follow-up")),
                    systemInstruction = "Use profile preferences.",
                    modelName = "gemini-2.5-flash-lite",
                )

            assertEquals(GeminiResult.Success(52), result)
            val body = factory.lastRequest?.bodyString().orEmpty()
            assertTrue(body.contains("\"generateContentRequest\""))
            assertTrue(body.contains("\"systemInstruction\""))
            assertTrue(body.contains("\"text\":\"Use profile preferences.\""))
            assertTrue(body.contains("\"text\":\"Follow-up\""))
        }

    @Test
    fun countTokensReturnsEmptyPromptWithoutNetworkCall() =
        runTest {
            val factory = FakeCallFactory { error("network should not be called") }
            val client = client(apiKey = "key", factory = factory)

            val result = client.countTokens(listOf(AgentMessage.User(" ")))

            assertEquals(GeminiResult.Failure(GeminiNetworkError.EmptyPrompt), result)
            assertEquals(0, factory.callCount)
        }

    @Test
    fun countTokensReturnsMissingApiKeyWithoutNetworkCall() =
        runTest {
            val factory = FakeCallFactory { error("network should not be called") }
            val client = client(apiKey = "", factory = factory)

            val result = client.countTokens(listOf(AgentMessage.User("Hello")))

            assertEquals(GeminiResult.Failure(GeminiNetworkError.MissingApiKey), result)
            assertEquals(0, factory.callCount)
        }

    @Test
    fun sendMessageSerializesAccumulatedChatHistory() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    jsonResponse(
                        request = request,
                        body = """{"candidates":[{"content":{"parts":[{"text":"Follow-up answer"}]}}]}""",
                    )
                }
            val client = client(factory = factory)

            val result =
                client.sendMessage(
                    messages =
                        listOf(
                            AgentMessage.User("First question"),
                            AgentMessage.Model("First answer"),
                            AgentMessage.User("Follow-up"),
                        ),
                    generationConfig = null,
                )

            assertEquals(GeminiResult.Success("Follow-up answer"), result)
            val body = factory.lastRequest?.bodyString().orEmpty()
            assertTrue(body.contains("\"role\":\"user\""))
            assertTrue(body.contains("\"role\":\"model\""))
            assertTrue(body.contains("\"text\":\"First question\""))
            assertTrue(body.contains("\"text\":\"First answer\""))
            assertTrue(body.contains("\"text\":\"Follow-up\""))
        }

    @Test
    fun sendMessageAppliesSlidingWindowWhenTokenLimitIsExceeded() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    if (request.url.toString().endsWith(":countTokens")) {
                        val body = request.bodyString()
                        val totalTokens =
                            when {
                                body.contains("First question") -> 30
                                body.contains("Second question") -> 18
                                body.contains("Follow-up") -> 4
                                else -> 1
                            }
                        jsonResponse(
                            request = request,
                            body = """{"totalTokens":$totalTokens}""",
                        )
                    } else {
                        jsonResponse(
                            request = request,
                            body =
                                """
                                {
                                  "candidates": [
                                    {
                                      "content": {
                                        "parts": [
                                          {"text": "Windowed answer"}
                                        ]
                                      }
                                    }
                                  ],
                                  "usageMetadata": {
                                    "promptTokenCount": 18,
                                    "candidatesTokenCount": 2,
                                    "totalTokenCount": 20
                                  }
                                }
                                """.trimIndent(),
                        )
                    }
                }
            val client = client(factory = factory, endpoint = GEMINI_GENERATE_CONTENT_ENDPOINT)

            val result =
                client.sendMessage(
                    messages =
                        listOf(
                            AgentMessage.User("First question"),
                            AgentMessage.Model("First answer"),
                            AgentMessage.User("Second question"),
                            AgentMessage.Model("Second answer"),
                            AgentMessage.User("Follow-up"),
                        ),
                    systemInstruction = "Keep this profile visible.",
                    generationConfig = null,
                    totalTokenLimit = 20,
                )

            assertEquals(
                GeminiResult.Success(
                    value = "Windowed answer",
                    tokenUsage =
                        GeminiTokenUsage(
                            currentRequestTokens = 4,
                            conversationHistoryTokens = 18,
                            modelResponseTokens = 2,
                            totalTokens = 20,
                            slidingWindowApplied = true,
                        ),
                ),
                result,
            )
            assertEquals(4, factory.callCount)
            val generateBody = factory.lastRequest?.bodyString().orEmpty()
            assertFalse(generateBody.contains("First question"))
            assertFalse(generateBody.contains("First answer"))
            assertTrue(generateBody.contains("Keep this profile visible."))
            assertTrue(generateBody.contains("Second question"))
            assertTrue(generateBody.contains("Second answer"))
            assertTrue(generateBody.contains("Follow-up"))
            assertTrue(generateBody.contains("\"maxOutputTokens\":2"))
        }

    @Test
    fun sendMessageUsesProvidedModelNameInGeminiEndpoint() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    jsonResponse(
                        request = request,
                        body = """{"candidates":[{"content":{"parts":[{"text":"Answer"}]}}]}""",
                    )
                }
            val client = client(factory = factory, endpoint = GEMINI_GENERATE_CONTENT_ENDPOINT)

            val result =
                client.sendMessage(
                    prompt = "Hello",
                    generationConfig = null,
                    modelName = "gemini-2.5-flash-lite",
                )

            assertEquals(GeminiResult.Success("Answer"), result)
            assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent",
                factory.lastRequest?.url.toString(),
            )
        }

    @Test
    fun sendMessageSerializesGenerationConfig() =
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
                client.sendMessage(
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

            assertTrue(result is GeminiResult.Success)
            assertEquals("Configured", (result as GeminiResult.Success).value)
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
    fun sendMessageKeepsProvidedMaxOutputTokensWhenTokenWindowAllowsMore() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    if (request.url.toString().endsWith(":countTokens")) {
                        jsonResponse(
                            request = request,
                            body = """{"totalTokens":27}""",
                        )
                    } else {
                        jsonResponse(
                            request = request,
                            body = """{"candidates":[{"content":{"parts":[{"text":"Configured"}]}}]}""",
                        )
                    }
                }
            val client = client(factory = factory, endpoint = GEMINI_GENERATE_CONTENT_ENDPOINT)

            val result =
                client.sendMessage(
                    prompt = "Hello",
                    generationConfig = GeminiGenerationConfig(maxOutputTokens = 1_024),
                    modelName = "gemma-4-31b-it",
                    totalTokenLimit = 262_144,
                )

            assertTrue(result is GeminiResult.Success)
            assertEquals("Configured", (result as GeminiResult.Success).value)
            val body = factory.lastRequest?.bodyString().orEmpty()
            assertTrue(body.contains("\"maxOutputTokens\":1024"))
            assertFalse(body.contains("\"maxOutputTokens\":262117"))
        }

    @Test
    fun sendMessageOmitsPenaltiesForGemini35Flash() =
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
                client.sendMessage(
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
    fun sendMessageMapsHttpErrors() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    jsonResponse(request = request, code = 429, body = """{"error":"quota"}""")
                }
            val client = client(factory = factory)

            val result = client.sendMessage("Hello", generationConfig = null)

            assertTrue(result is GeminiResult.Failure)
            assertEquals(429, ((result as GeminiResult.Failure).error as GeminiNetworkError.Http).statusCode)
        }

    @Test
    fun sendMessageRetriesUnavailableResponses() =
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

            val result = client.sendMessage("Hello", generationConfig = null)

            assertEquals(GeminiResult.Success("Recovered"), result)
            assertEquals(2, factory.callCount)
        }

    @Test
    fun sendMessageRetriesRateLimitResponses() =
        runTest {
            var attempts = 0
            val factory =
                FakeCallFactory { request ->
                    attempts += 1
                    if (attempts == 1) {
                        jsonResponse(request = request, code = 429, body = """{"error":"rate limit"}""")
                    } else {
                        jsonResponse(
                            request = request,
                            body = """{"candidates":[{"content":{"parts":[{"text":"Recovered"}]}}]}""",
                        )
                    }
                }
            val client = client(factory = factory)

            val result = client.sendMessage("Hello", generationConfig = null)

            assertEquals(GeminiResult.Success("Recovered"), result)
            assertEquals(2, factory.callCount)
        }

    @Test
    fun sendMessageDoesNotRetryInvalidArgumentResponses() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    jsonResponse(
                        request = request,
                        code = 400,
                        body = """{"error":{"message":"Penalty is not enabled for this model"}}""",
                    )
                }
            val client = client(factory = factory)

            val result = client.sendMessage("Hello", generationConfig = null)

            assertTrue(result is GeminiResult.Failure)
            assertEquals(400, ((result as GeminiResult.Failure).error as GeminiNetworkError.Http).statusCode)
            assertEquals(1, factory.callCount)
        }

    @Test
    fun sendMessageMapsInvalidJson() =
        runTest {
            val factory = FakeCallFactory { request -> jsonResponse(request = request, body = "{") }
            val client = client(factory = factory)

            val result = client.sendMessage("Hello", generationConfig = null)

            assertTrue((result as GeminiResult.Failure).error is GeminiNetworkError.Serialization)
        }

    @Test
    fun sendMessageMapsEmptyModelResponse() =
        runTest {
            val factory = FakeCallFactory { request -> jsonResponse(request = request, body = """{"candidates":[]}""") }
            val client = client(factory = factory)

            val result = client.sendMessage("Hello", generationConfig = null)

            assertEquals(GeminiResult.Failure(GeminiNetworkError.EmptyResponse), result)
        }

    @Test
    fun sendMessageMapsIoExceptions() =
        runTest {
            val factory = FakeCallFactory { throw IOException("offline") }
            val client = client(factory = factory)

            val result = client.sendMessage("Hello", generationConfig = null)

            assertTrue((result as GeminiResult.Failure).error is GeminiNetworkError.Network)
        }

    private fun client(
        apiKey: String = "key",
        factory: FakeCallFactory,
        endpoint: String = "https://example.test/generate",
    ): LlmAgent =
        GeminiAgent(
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
        val requests = mutableListOf<Request>()

        override fun newCall(request: Request): Call {
            callCount += 1
            lastRequest = request
            requests += request
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
