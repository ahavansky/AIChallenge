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
class DeepSeekAgentTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun sendMessageReturnsMissingApiKeyWithoutNetworkCall() =
        runTest {
            val factory = FakeCallFactory { error("network should not be called") }
            val client = client(apiKey = "", factory = factory)

            val result =
                client.sendMessage(
                    messages = listOf(AgentMessage.User("Hello")),
                    modelName = "deepseek-v4-flash",
                )

            assertEquals(
                GeminiResult.Failure(
                    GeminiNetworkError.MissingProviderApiKey(
                        providerName = "DeepSeek",
                        keyName = "DEEPSEEK_API_KEY",
                    ),
                ),
                result,
            )
            assertEquals(0, factory.callCount)
        }

    @Test
    fun sendMessageSerializesOpenAiCompatibleChatRequestAndMapsUsage() =
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
                                  "message": {"role": "assistant", "content": "DeepSeek answer"}
                                }
                              ],
                              "usage": {
                                "prompt_tokens": 13,
                                "completion_tokens": 8,
                                "total_tokens": 21
                              }
                            }
                            """.trimIndent(),
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
                    systemInstruction = "Use profile preferences.",
                    generationConfig =
                        GeminiGenerationConfig(
                            maxOutputTokens = 512,
                            temperature = 0.2,
                            topP = 0.9,
                            stopSequences = listOf("END"),
                        ),
                    modelName = "deepseek-v4-pro",
                )

            assertEquals(
                GeminiResult.Success(
                    value = "DeepSeek answer",
                    tokenUsage =
                        GeminiTokenUsage(
                            conversationHistoryTokens = 13,
                            modelResponseTokens = 8,
                            totalTokens = 21,
                        ),
                ),
                result,
            )
            assertEquals("https://example.test/chat/completions", factory.lastRequest?.url.toString())
            assertEquals("Bearer key", factory.lastRequest?.header("Authorization"))
            val body = factory.lastRequest?.bodyString().orEmpty()
            assertTrue(body.contains("\"model\":\"deepseek-v4-pro\""))
            assertTrue(body.contains("\"role\":\"system\""))
            assertTrue(body.contains("\"content\":\"Use profile preferences.\""))
            assertTrue(body.contains("\"role\":\"user\""))
            assertTrue(body.contains("\"content\":\"First question\""))
            assertTrue(body.contains("\"role\":\"assistant\""))
            assertTrue(body.contains("\"content\":\"First answer\""))
            assertTrue(body.contains("\"content\":\"Follow-up\""))
            assertTrue(body.contains("\"max_tokens\":512"))
            assertTrue(body.contains("\"temperature\":0.2"))
            assertTrue(body.contains("\"top_p\":0.9"))
            assertTrue(body.contains("\"stop\":[\"END\"]"))
        }

    @Test
    fun sendMessageMapsHttpErrors() =
        runTest {
            val factory = FakeCallFactory { request -> jsonResponse(request = request, code = 401, body = """{"error":"bad key"}""") }
            val client = client(factory = factory)

            val result = client.sendMessage("Hello", modelName = "deepseek-v4-flash")

            assertTrue(result is GeminiResult.Failure)
            assertEquals(401, ((result as GeminiResult.Failure).error as GeminiNetworkError.ProviderHttp).statusCode)
            assertEquals(1, factory.callCount)
        }

    @Test
    fun sendMessageRetriesTemporaryHttpErrors() =
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

            val result = client.sendMessage("Hello", modelName = "deepseek-v4-flash")

            assertEquals(GeminiResult.Success("Recovered"), result)
            assertEquals(2, factory.callCount)
        }

    @Test
    fun sendMessageMapsInvalidJson() =
        runTest {
            val factory = FakeCallFactory { request -> jsonResponse(request = request, body = "{") }
            val client = client(factory = factory)

            val result = client.sendMessage("Hello", modelName = "deepseek-v4-flash")

            assertTrue((result as GeminiResult.Failure).error is GeminiNetworkError.ProviderSerialization)
        }

    @Test
    fun sendMessageMapsIoExceptions() =
        runTest {
            val factory = FakeCallFactory { throw IOException("offline") }
            val client = client(factory = factory)

            val result = client.sendMessage("Hello", modelName = "deepseek-v4-flash")

            assertTrue((result as GeminiResult.Failure).error is GeminiNetworkError.ProviderNetwork)
        }

    private fun client(
        apiKey: String = "key",
        factory: FakeCallFactory,
        endpoint: String = "https://example.test/chat/completions",
    ): LlmAgent =
        DeepSeekAgent(
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
