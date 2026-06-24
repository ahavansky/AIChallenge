package com.akhavanskii.aichallenge.mcp.livebriefing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class LiveBriefingServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun toolsListExposesOnlyLiveBriefingTool() {
        val handler = McpJsonRpcHandler(LiveBriefingTool(service()))

        val response = handler.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")

        assertEquals(200, response.statusCode)
        val tools =
            json
                .parseToJsonElement(response.body)
                .jsonObject["result"]!!
                .jsonObject["tools"]!!
                .jsonArray
        assertEquals(1, tools.size)
        val tool = tools.single().jsonObject
        assertEquals(LIVE_BRIEFING_TOOL, tool["name"]!!.jsonPrimitive.content)
        val schema = tool["inputSchema"]!!.jsonObject
        assertTrue(schema["required"]!!.jsonArray.any { it.jsonPrimitive.content == "action" })
    }

    @Test
    fun refreshNowReturnsStructuredSummaryAndPersistsSnapshot() {
        val path = statePath()
        val service = service(path = path)

        val response = service.refreshNow()

        assertFalse(response.isError)
        assertEquals("fresh", response.structuredContent["status"]!!.jsonPrimitive.content)
        assertEquals(
            "Test City",
            response
                .structuredContent["weather"]!!
                .jsonObject["city"]!!
                .jsonPrimitive
                .content,
        )
        assertTrue(response.text.contains("Top headlines"))

        val restarted = service(path = path)
        val restartedSummary = restarted.summary()
        assertEquals(
            "Test City",
            restartedSummary
                .structuredContent["weather"]!!
                .jsonObject["city"]!!
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun addReminderBecomesDueWhenClockPassesDueTime() {
        val clock = MutableClock(Instant.parse("2026-06-24T10:00:00Z"))
        val service = service(clock = clock)

        service.addReminder(
            title = "Call back",
            body = null,
            dueAt = null,
            delaySeconds = 30,
            repeatEverySeconds = null,
        )
        clock.instant = Instant.parse("2026-06-24T10:00:31Z")
        val summary = service.summary()

        val due =
            summary
                .structuredContent["reminders"]!!
                .jsonObject["due"]!!
                .jsonArray
        assertEquals(1, due.size)
        assertEquals(
            "Call back",
            due
                .single()
                .jsonObject["title"]!!
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun completingRecurringReminderSchedulesNextOccurrence() {
        val clock = MutableClock(Instant.parse("2026-06-24T10:00:00Z"))
        val service = service(clock = clock)

        service.addReminder(
            title = "Drink water",
            body = null,
            dueAt = null,
            delaySeconds = 1,
            repeatEverySeconds = 30,
        )
        clock.instant = Instant.parse("2026-06-24T10:00:02Z")
        val dueSummary = service.summary()
        val dueReminder =
            dueSummary
                .structuredContent["reminders"]!!
                .jsonObject["due"]!!
                .jsonArray
                .single()
                .jsonObject
        val reminderId = dueReminder["id"]!!.jsonPrimitive.content

        service.completeReminder(reminderId)
        val summary = service.summary()

        val due =
            summary
                .structuredContent["reminders"]!!
                .jsonObject["due"]!!
                .jsonArray
        val recurring =
            summary
                .structuredContent["reminders"]!!
                .jsonObject["recurring"]!!
                .jsonArray
        assertTrue(due.isEmpty())
        assertEquals(1, recurring.size)
        assertEquals(
            "scheduled",
            recurring
                .single()
                .jsonObject["status"]!!
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun networkFailureKeepsCachedDataAndReturnsPartialStatus() {
        val weather = FakeWeatherClient()
        val rss = FakeRssClient()
        val service = service(weatherClient = weather, rssClient = rss)

        service.refreshNow()
        weather.result = FetchResult.Failure("weather down")
        rss.result = FetchResult.Failure("rss down")
        val response = service.refreshNow()

        assertEquals("partial", response.structuredContent["status"]!!.jsonPrimitive.content)
        assertEquals(
            "Test City",
            response
                .structuredContent["weather"]!!
                .jsonObject["city"]!!
                .jsonPrimitive
                .content,
        )
        assertTrue(response.structuredContent["errors"]!!.jsonArray.size >= 2)
    }

    @Test
    fun configureRejectsUnknownFeedId() {
        val service = service()

        val response = service.configure(city = "Berlin", feedIds = listOf("unknown"))

        assertTrue(response.isError)
        assertTrue(response.text.contains("feedIds"))
    }

    private fun service(
        path: Path = statePath(),
        clock: MutableClock = MutableClock(Instant.parse("2026-06-24T10:00:00Z")),
        weatherClient: FakeWeatherClient = FakeWeatherClient(),
        rssClient: FakeRssClient = FakeRssClient(),
    ): LiveBriefingService =
        LiveBriefingService(
            store = LiveBriefingStore(path),
            weatherClient = weatherClient,
            rssClient = rssClient,
            clock = clock,
            refreshInterval = java.time.Duration.ofMinutes(10),
        )

    private fun statePath(): Path = temporaryFolder.newFolder().toPath().resolve("state.json")

    private class MutableClock(
        var instant: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = instant
    }

    private class FakeWeatherClient : WeatherClient {
        var result: FetchResult<WeatherSnapshot> =
            FetchResult.Success(
                WeatherSnapshot(
                    city = "Test City",
                    country = "Testland",
                    latitude = 10.0,
                    longitude = 20.0,
                    temperatureCelsius = 21.0,
                    apparentTemperatureCelsius = 20.0,
                    precipitationProbabilityPercent = 15,
                    precipitationMm = 0.0,
                    windSpeedKmh = 8.0,
                    weatherCode = 1,
                ),
            )

        override fun fetchWeather(city: String): FetchResult<WeatherSnapshot> = result
    }

    private class FakeRssClient : RssClient {
        var result: FetchResult<List<NewsItem>> =
            FetchResult.Success(
                listOf(
                    NewsItem(
                        id = "news-1",
                        source = "bbc_world",
                        title = "Important headline",
                        link = "https://example.com/news",
                        publishedAt = "Wed, 24 Jun 2026 10:00:00 GMT",
                    ),
                ),
            )

        override fun fetch(feedId: String): FetchResult<List<NewsItem>> = result
    }
}
