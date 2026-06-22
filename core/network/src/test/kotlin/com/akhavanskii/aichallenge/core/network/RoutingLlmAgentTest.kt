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
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.reflect.KClass

@OptIn(ExperimentalSerializationApi::class)
class RoutingLlmAgentTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun sendMessageRoutesDeepSeekModelsToDeepSeekEndpoint() =
        runTest {
            val geminiFactory = FakeCallFactory { error("Gemini should not be called") }
            val deepSeekFactory =
                FakeCallFactory { request ->
                    jsonResponse(
                        request = request,
                        body = """{"choices":[{"message":{"role":"assistant","content":"DeepSeek answer"}}]}""",
                    )
                }
            val agent =
                RoutingLlmAgent(
                    geminiAgent =
                        GeminiAgent(
                            apiKey = "gemini-key",
                            endpoint = GEMINI_GENERATE_CONTENT_ENDPOINT,
                            callFactory = geminiFactory,
                            json = json,
                            dispatcher = Dispatchers.Unconfined,
                        ),
                    deepSeekAgent =
                        DeepSeekAgent(
                            apiKey = "deepseek-key",
                            endpoint = "https://example.test/deepseek/chat/completions",
                            callFactory = deepSeekFactory,
                            json = json,
                            dispatcher = Dispatchers.Unconfined,
                        ),
                )

            val result = agent.sendMessage("Hello", modelName = "deepseek-v4-flash")

            assertEquals(GeminiResult.Success("DeepSeek answer"), result)
            assertEquals(0, geminiFactory.callCount)
            assertEquals(1, deepSeekFactory.callCount)
            assertEquals("https://example.test/deepseek/chat/completions", deepSeekFactory.lastRequest?.url.toString())
        }

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
