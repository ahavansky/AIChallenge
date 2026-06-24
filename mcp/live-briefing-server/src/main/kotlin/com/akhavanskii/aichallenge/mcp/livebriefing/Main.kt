package com.akhavanskii.aichallenge.mcp.livebriefing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

fun main() {
    val port = System.getenv("MCP_LIVE_BRIEFING_PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val statePath =
        System
            .getenv("MCP_LIVE_BRIEFING_STORE_PATH")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { Path.of(it) }
            ?: Path.of("build/live-briefing/state.json")
    val refreshSeconds =
        System
            .getenv("MCP_LIVE_BRIEFING_REFRESH_SECONDS")
            ?.toLongOrNull()
            ?.coerceAtLeast(MIN_REFRESH_SECONDS)
            ?: DEFAULT_REFRESH_SECONDS
    val reminderSeconds =
        System
            .getenv("MCP_LIVE_BRIEFING_REMINDER_SECONDS")
            ?.toLongOrNull()
            ?.coerceAtLeast(MIN_REMINDER_SECONDS)
            ?: DEFAULT_REMINDER_SECONDS
    val demoCity =
        System
            .getenv("MCP_LIVE_BRIEFING_DEMO_CITY")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_CITY
    val customFeeds = customFeedsFromEnvironment(System.getenv("MCP_LIVE_BRIEFING_RSS_FEEDS"))
    val service =
        LiveBriefingService(
            store = LiveBriefingStore(statePath),
            weatherClient = OpenMeteoWeatherClient(),
            rssClient = JvmRssClient(customFeeds = customFeeds),
            clock = Clock.systemUTC(),
            refreshInterval = Duration.ofSeconds(refreshSeconds),
            demoCity = demoCity,
            allowedFeedIds = ALLOWED_FEEDS.keys + customFeeds.keys,
        )
    service.startScheduler(
        refreshInterval = Duration.ofSeconds(refreshSeconds),
        reminderInterval = Duration.ofSeconds(reminderSeconds),
    )
    val server =
        LiveBriefingMcpHttpServer(
            port = port,
            handler = McpJsonRpcHandler(LiveBriefingTool(service)),
        )
    server.start()
    println("Live Briefing MCP server listening on http://localhost:$port/mcp")
}

class LiveBriefingMcpHttpServer(
    private val port: Int,
    private val handler: McpJsonRpcHandler,
) {
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("0.0.0.0", port), 0).apply {
            createContext("/mcp") { exchange -> handle(exchange) }
        }

    fun start() {
        server.start()
    }

    fun stop(delaySeconds: Int = 0) {
        server.stop(delaySeconds)
    }

    private fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            exchange.writeText(statusCode = 405, body = "Only POST is supported.")
            return
        }

        val requestBody = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val response = handler.handle(requestBody)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("Mcp-Session-Id", "live-briefing-session")
        exchange.writeText(statusCode = response.statusCode, body = response.body)
    }
}

class McpJsonRpcHandler(
    private val tool: LiveBriefingTool,
    private val json: Json = DEFAULT_JSON,
) {
    fun handle(requestBody: String): HttpResponseBody {
        val request =
            runCatching { json.parseToJsonElement(requestBody).jsonObject }
                .getOrElse {
                    return jsonRpcError(
                        id = JsonNull,
                        code = JSON_RPC_PARSE_ERROR,
                        message = "Invalid JSON-RPC payload.",
                    )
                }
        val id = request["id"] ?: JsonNull
        return when (request.stringOrNull("method")) {
            "initialize" -> initialize(id)
            "notifications/initialized" -> HttpResponseBody(statusCode = 202, body = "")
            "tools/list" -> toolsList(id)
            "tools/call" -> toolsCall(id, request["params"]?.jsonObjectOrNull())
            else ->
                jsonRpcError(
                    id = id,
                    code = JSON_RPC_METHOD_NOT_FOUND,
                    message = "Method not found.",
                )
        }
    }

    private fun initialize(id: JsonElement): HttpResponseBody =
        jsonRpcResult(
            id = id,
            result =
                buildJsonObject {
                    put("protocolVersion", MCP_PROTOCOL_VERSION)
                    putJsonObject("serverInfo") {
                        put("name", "live-briefing-mcp")
                        put("version", "1.0.0")
                    }
                    putJsonObject("capabilities") {
                        putJsonObject("tools") {
                            put("listChanged", false)
                        }
                    }
                },
        )

    private fun toolsList(id: JsonElement): HttpResponseBody =
        jsonRpcResult(
            id = id,
            result =
                buildJsonObject {
                    putJsonArray("tools") {
                        add(
                            buildJsonObject {
                                put("name", LIVE_BRIEFING_TOOL)
                                put(
                                    "description",
                                    "Update and summarize live briefing data: weather, RSS headlines, and reminders.",
                                )
                                put("inputSchema", liveBriefingSchema())
                            },
                        )
                    }
                },
        )

    private fun toolsCall(
        id: JsonElement,
        params: JsonObject?,
    ): HttpResponseBody {
        val toolName = params?.stringOrNull("name")
        val arguments = params?.get("arguments")?.jsonObjectOrNull() ?: JsonObject(emptyMap())
        if (toolName.isNullOrBlank()) {
            return jsonRpcError(id = id, code = JSON_RPC_INVALID_PARAMS, message = "Tool name is required.")
        }
        if (toolName != LIVE_BRIEFING_TOOL) {
            return jsonRpcError(id = id, code = JSON_RPC_INVALID_PARAMS, message = "Unknown tool: $toolName.")
        }

        return jsonRpcResult(id = id, result = tool.call(arguments).toJson())
    }

    private fun jsonRpcResult(
        id: JsonElement,
        result: JsonObject,
    ): HttpResponseBody =
        HttpResponseBody(
            statusCode = 200,
            body =
                buildJsonObject {
                    put("jsonrpc", JSON_RPC_VERSION)
                    put("id", id)
                    put("result", result)
                }.toString(),
        )

    private fun jsonRpcError(
        id: JsonElement,
        code: Int,
        message: String,
    ): HttpResponseBody =
        HttpResponseBody(
            statusCode = 200,
            body =
                buildJsonObject {
                    put("jsonrpc", JSON_RPC_VERSION)
                    put("id", id)
                    putJsonObject("error") {
                        put("code", code)
                        put("message", message)
                    }
                }.toString(),
        )
}

class LiveBriefingTool(
    private val service: LiveBriefingService,
) {
    fun call(arguments: JsonObject): McpToolCallResult {
        val action = arguments.stringOrNull("action") ?: return McpToolCallResult.error("`action` is required.")
        return when (action) {
            "configure" -> configure(arguments)
            "refresh_now" -> service.refreshNow().toToolResult()
            "summary" -> service.summary().toToolResult()
            "add_reminder" -> addReminder(arguments)
            "complete_reminder" -> completeReminder(arguments)
            "timeline" -> service.timeline().toToolResult()
            "demo_setup" -> service.demoSetup().toToolResult()
            "reset" -> service.reset().toToolResult()
            else -> McpToolCallResult.error("Unsupported action: $action.")
        }
    }

    private fun configure(arguments: JsonObject): McpToolCallResult {
        val city = arguments.stringOrNull("city")
        val feedIds = arguments.stringArrayOrNull("feedIds")
        return service.configure(city = city, feedIds = feedIds).toToolResult()
    }

    private fun addReminder(arguments: JsonObject): McpToolCallResult {
        val title = arguments.stringOrNull("title")
        val body = arguments.stringOrNull("body")
        val dueAt = arguments.stringOrNull("dueAt")
        val delaySeconds = arguments.longOrNull("delaySeconds")
        val repeatEverySeconds = arguments.longOrNull("repeatEverySeconds")
        return service
            .addReminder(
                title = title,
                body = body,
                dueAt = dueAt,
                delaySeconds = delaySeconds,
                repeatEverySeconds = repeatEverySeconds,
            ).toToolResult()
    }

    private fun completeReminder(arguments: JsonObject): McpToolCallResult {
        val id = arguments.stringOrNull("id")
        return service.completeReminder(id = id).toToolResult()
    }
}

class LiveBriefingService(
    private val store: LiveBriefingStore,
    private val weatherClient: WeatherClient,
    private val rssClient: RssClient,
    private val clock: Clock,
    private val refreshInterval: Duration,
    private val demoCity: String = DEFAULT_CITY,
    private val allowedFeedIds: Set<String> = ALLOWED_FEEDS.keys,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
) {
    fun startScheduler(
        refreshInterval: Duration,
        reminderInterval: Duration,
    ) {
        executor.scheduleAtFixedRate(
            { runCatching { refreshNow() } },
            refreshInterval.seconds,
            refreshInterval.seconds,
            TimeUnit.SECONDS,
        )
        executor.scheduleAtFixedRate(
            { runCatching { checkDueReminders() } },
            reminderInterval.seconds,
            reminderInterval.seconds,
            TimeUnit.SECONDS,
        )
    }

    fun stopScheduler() {
        executor.shutdownNow()
    }

    @Synchronized
    fun configure(
        city: String?,
        feedIds: List<String>?,
    ): LiveBriefingResponse {
        val state = store.load()
        val normalizedCity = city?.trim()?.takeIf { it.isNotEmpty() } ?: state.config.city
        val cityError = validateCity(normalizedCity)
        if (cityError != null) return LiveBriefingResponse.error(cityError, summaryJson(state))
        val normalizedFeeds = feedIds?.takeIf { it.isNotEmpty() } ?: state.config.feedIds
        val feedError = validateFeedIds(normalizedFeeds)
        if (feedError != null) return LiveBriefingResponse.error(feedError, summaryJson(state))

        val updated =
            state.copy(
                config = state.config.copy(city = normalizedCity, feedIds = normalizedFeeds.distinct()),
                events =
                    (
                        state.events +
                            BriefingEvent(
                                id = nextId(),
                                createdAt = nowString(),
                                type = "configured",
                                title = "Configured briefing for $normalizedCity",
                            )
                    ).takeLast(MAX_EVENTS),
            )
        store.save(updated)
        return LiveBriefingResponse.success(
            text = "Live briefing configured for $normalizedCity.",
            structuredContent = summaryJson(updated),
        )
    }

    @Synchronized
    fun refreshNow(): LiveBriefingResponse {
        var state = store.load()
        val now = nowString()
        val errors = mutableListOf<BriefingError>()
        val weather =
            when (val result = weatherClient.fetchWeather(state.config.city)) {
                is FetchResult.Success -> result.value
                is FetchResult.Failure -> {
                    errors += BriefingError(source = "weather", message = result.message, lastFailureAt = now)
                    state.snapshots.lastOrNull()?.weather
                }
            }
        val newsItems = mutableListOf<NewsItem>()
        state.config.feedIds.forEach { feedId ->
            when (val result = rssClient.fetch(feedId)) {
                is FetchResult.Success -> newsItems += result.value
                is FetchResult.Failure -> errors += BriefingError(source = feedId, message = result.message, lastFailureAt = now)
            }
        }
        val previousNews =
            state.snapshots
                .lastOrNull()
                ?.newsItems
                .orEmpty()
        val snapshot =
            BriefingSnapshot(
                createdAt = now,
                weather = weather,
                newsItems = newsItems.ifEmpty { previousNews }.take(MAX_NEWS_ITEMS),
                errors = errors.take(MAX_ERRORS),
            )
        val dueChecked = checkDueReminders(state)
        state =
            dueChecked.copy(
                snapshots = (dueChecked.snapshots + snapshot).takeLast(MAX_SNAPSHOTS),
                events =
                    (
                        dueChecked.events +
                            BriefingEvent(
                                id = nextId(),
                                createdAt = now,
                                type = "refresh",
                                title = if (errors.isEmpty()) "Briefing refreshed" else "Briefing refreshed with warnings",
                            )
                    ).takeLast(MAX_EVENTS),
            )
        store.save(state)
        return summary("Briefing refreshed.", state)
    }

    @Synchronized
    fun summary(): LiveBriefingResponse {
        val state = checkDueReminders(store.load())
        store.save(state)
        return summary("Live briefing summary.", state)
    }

    @Synchronized
    fun timeline(): LiveBriefingResponse {
        val state = checkDueReminders(store.load())
        store.save(state)
        val text =
            buildString {
                appendLine("Live briefing timeline")
                state.events.takeLast(8).forEach { event ->
                    appendLine("- ${event.createdAt}: ${event.title}")
                }
                state.reminders.filter { it.status == ReminderStatus.DUE }.forEach { reminder ->
                    appendLine("- ${reminder.nextDueAt}: reminder due: ${reminder.title}")
                }
            }.trim()
        return LiveBriefingResponse.success(text = text, structuredContent = summaryJson(state))
    }

    @Synchronized
    fun demoSetup(): LiveBriefingResponse {
        val state =
            LiveBriefingState(
                config = BriefingConfig(city = demoCity, feedIds = DEFAULT_FEED_IDS),
            )
        store.save(state)
        addReminder(
            title = "Record Live Briefing demo result",
            body = "This reminder is due in 30 seconds so the demo can show scheduled work.",
            dueAt = null,
            delaySeconds = 30,
            repeatEverySeconds = null,
        )
        return refreshNow()
    }

    @Synchronized
    fun reset(): LiveBriefingResponse {
        val state = LiveBriefingState()
        store.save(state)
        return LiveBriefingResponse.success(text = "Live briefing state reset.", structuredContent = summaryJson(state))
    }

    @Synchronized
    fun addReminder(
        title: String?,
        body: String?,
        dueAt: String?,
        delaySeconds: Long?,
        repeatEverySeconds: Long?,
    ): LiveBriefingResponse {
        val normalizedTitle = title?.trim().orEmpty()
        if (normalizedTitle.isBlank()) return LiveBriefingResponse.error("`title` is required.", summaryJson(store.load()))
        if (normalizedTitle.length > MAX_TITLE_LENGTH) {
            return LiveBriefingResponse.error("`title` must be $MAX_TITLE_LENGTH characters or fewer.", summaryJson(store.load()))
        }
        val dueInstant =
            dueAt
                ?.let {
                    try {
                        Instant.parse(it)
                    } catch (_: DateTimeParseException) {
                        return LiveBriefingResponse.error("`dueAt` must be an ISO-8601 UTC timestamp.", summaryJson(store.load()))
                    }
                }
                ?: delaySeconds?.let { seconds ->
                    if (seconds !in 1..MAX_DELAY_SECONDS) {
                        return LiveBriefingResponse.error(
                            "`delaySeconds` must be between 1 and $MAX_DELAY_SECONDS.",
                            summaryJson(store.load()),
                        )
                    }
                    clock.instant().plusSeconds(seconds)
                }
                ?: return LiveBriefingResponse.error("Provide `dueAt` or `delaySeconds`.", summaryJson(store.load()))
        val repeatSeconds = repeatEverySeconds?.takeIf { it > 0 }
        if (repeatSeconds != null && repeatSeconds < MIN_REPEAT_SECONDS) {
            return LiveBriefingResponse.error("`repeatEverySeconds` must be at least $MIN_REPEAT_SECONDS.", summaryJson(store.load()))
        }

        var state = store.load()
        val reminder =
            Reminder(
                id = nextId(),
                title = normalizedTitle,
                body = body?.trim().orEmpty(),
                createdAt = nowString(),
                nextDueAt = dueInstant.toString(),
                repeatEverySeconds = repeatSeconds,
                status = ReminderStatus.SCHEDULED,
            )
        state =
            state.copy(
                reminders = (state.reminders + reminder).takeLast(MAX_REMINDERS),
                events =
                    (
                        state.events +
                            BriefingEvent(
                                id = nextId(),
                                createdAt = nowString(),
                                type = "reminder_added",
                                title = "Reminder added: ${reminder.title}",
                            )
                    ).takeLast(MAX_EVENTS),
            )
        store.save(state)
        return summary("Reminder added: ${reminder.title}.", state)
    }

    @Synchronized
    fun completeReminder(id: String?): LiveBriefingResponse {
        val reminderId = id?.trim().orEmpty()
        if (reminderId.isBlank()) return LiveBriefingResponse.error("`id` is required.", summaryJson(store.load()))
        var state = store.load()
        val reminder =
            state.reminders.firstOrNull { it.id == reminderId }
                ?: return LiveBriefingResponse.error("Reminder not found: $reminderId.", summaryJson(state))
        val completedAt = clock.instant()
        val updatedReminder =
            if (reminder.repeatEverySeconds != null) {
                reminder.copy(
                    status = ReminderStatus.SCHEDULED,
                    nextDueAt = completedAt.plusSeconds(reminder.repeatEverySeconds).toString(),
                    lastCompletedAt = completedAt.toString(),
                )
            } else {
                reminder.copy(status = ReminderStatus.COMPLETED, lastCompletedAt = completedAt.toString())
            }
        state =
            state.copy(
                reminders = state.reminders.map { if (it.id == reminderId) updatedReminder else it },
                events =
                    (
                        state.events +
                            BriefingEvent(
                                id = nextId(),
                                createdAt = nowString(),
                                type = "reminder_completed",
                                title = "Reminder completed: ${reminder.title}",
                            )
                    ).takeLast(MAX_EVENTS),
            )
        store.save(state)
        return summary("Reminder completed: ${reminder.title}.", state)
    }

    private fun checkDueReminders(): LiveBriefingState {
        val updated = checkDueReminders(store.load())
        store.save(updated)
        return updated
    }

    private fun checkDueReminders(state: LiveBriefingState): LiveBriefingState {
        val now = clock.instant()
        val dueIds =
            state.reminders
                .filter { reminder ->
                    reminder.status == ReminderStatus.SCHEDULED &&
                        runCatching { Instant.parse(reminder.nextDueAt) <= now }.getOrDefault(false)
                }.map { it.id }
                .toSet()
        if (dueIds.isEmpty()) return state
        val dueEvents =
            state.reminders
                .filter { it.id in dueIds }
                .map { reminder ->
                    BriefingEvent(
                        id = nextId(),
                        createdAt = now.toString(),
                        type = "reminder_due",
                        title = "Reminder due: ${reminder.title}",
                    )
                }
        return state.copy(
            reminders = state.reminders.map { if (it.id in dueIds) it.copy(status = ReminderStatus.DUE) else it },
            events = (state.events + dueEvents).takeLast(MAX_EVENTS),
        )
    }

    private fun summary(
        title: String,
        state: LiveBriefingState,
    ): LiveBriefingResponse {
        val structured = summaryJson(state)
        val latest = state.snapshots.lastOrNull()
        val dueReminders = state.reminders.filter { it.status == ReminderStatus.DUE }
        val upcomingReminders = state.reminders.filter { it.status == ReminderStatus.SCHEDULED }
        val text =
            buildString {
                appendLine(title)
                appendLine("Status: ${structured.stringOrNull("status") ?: "stale"}")
                latest?.weather?.let { weather ->
                    appendLine(
                        "Weather: ${weather.city}, ${weather.temperatureCelsius} C, feels like ${weather.apparentTemperatureCelsius} C.",
                    )
                } ?: appendLine("Weather: no successful weather snapshot yet.")
                if (latest?.newsItems?.isNotEmpty() == true) {
                    appendLine("Top headlines:")
                    latest.newsItems.take(3).forEach { item -> appendLine("- ${item.title} (${item.source})") }
                } else {
                    appendLine("Top headlines: no successful RSS snapshot yet.")
                }
                if (dueReminders.isNotEmpty()) {
                    appendLine("Due reminders:")
                    dueReminders.take(3).forEach { reminder -> appendLine("- ${reminder.title}") }
                }
                if (upcomingReminders.isNotEmpty()) {
                    appendLine("Upcoming reminders: ${upcomingReminders.size}")
                }
                append("Next action: ${briefNextAction(state)}")
            }
        return LiveBriefingResponse.success(text = text, structuredContent = structured)
    }

    private fun summaryJson(state: LiveBriefingState): JsonObject {
        val latest = state.snapshots.lastOrNull()
        val status = statusFor(state)
        val due = state.reminders.filter { it.status == ReminderStatus.DUE }
        val upcoming = state.reminders.filter { it.status == ReminderStatus.SCHEDULED }
        val recurring = state.reminders.filter { it.repeatEverySeconds != null && it.status != ReminderStatus.COMPLETED }
        return buildJsonObject {
            put("status", status)
            put("generatedAt", nowString())
            put("lastRefreshAt", latest?.createdAt ?: "")
            put("city", state.config.city)
            put("weather", latest?.weather?.toJson() ?: JsonObject(emptyMap()))
            putJsonArray("newsItems") {
                latest
                    ?.newsItems
                    .orEmpty()
                    .take(5)
                    .forEach { add(it.toJson()) }
            }
            putJsonObject("reminders") {
                putJsonArray("due") { due.forEach { add(it.toJson()) } }
                putJsonArray("upcoming") { upcoming.take(5).forEach { add(it.toJson()) } }
                putJsonArray("recurring") { recurring.take(5).forEach { add(it.toJson()) } }
            }
            putJsonObject("brief") {
                put("headline", briefHeadline(state))
                putJsonArray("bullets") {
                    briefBullets(state).forEach { add(JsonPrimitive(it)) }
                }
                put("nextAction", briefNextAction(state))
            }
            putJsonArray("errors") {
                latest?.errors.orEmpty().forEach { add(it.toJson()) }
            }
            putJsonArray("events") {
                state.events.takeLast(8).forEach { add(it.toJson()) }
            }
        }
    }

    private fun statusFor(state: LiveBriefingState): String {
        val latest = state.snapshots.lastOrNull() ?: return "stale"
        val createdAt = runCatching { Instant.parse(latest.createdAt) }.getOrNull() ?: return "stale"
        val isStale = Duration.between(createdAt, clock.instant()) > refreshInterval.multipliedBy(2)
        if (isStale) return "stale"
        return if (latest.errors.isNotEmpty()) "partial" else "fresh"
    }

    private fun briefHeadline(state: LiveBriefingState): String {
        val latest = state.snapshots.lastOrNull() ?: return "Briefing has no network snapshot yet"
        val city = latest.weather?.city ?: state.config.city
        return when {
            state.reminders.any { it.status == ReminderStatus.DUE } -> "Reminder due now"
            latest.errors.isNotEmpty() -> "Briefing partially updated"
            else -> "Live briefing ready for $city"
        }
    }

    private fun briefBullets(state: LiveBriefingState): List<String> {
        val latest = state.snapshots.lastOrNull()
        val bullets = mutableListOf<String>()
        latest?.weather?.let { weather ->
            bullets += "${weather.city}: ${weather.temperatureCelsius} C, wind ${weather.windSpeedKmh} km/h"
        }
        latest?.newsItems?.take(2)?.forEach { item -> bullets += item.title }
        val dueCount = state.reminders.count { it.status == ReminderStatus.DUE }
        if (dueCount > 0) bullets += "$dueCount reminder(s) due"
        if (latest?.errors?.isNotEmpty() == true) bullets += "Some sources failed; cached data is still shown"
        return bullets.ifEmpty { listOf("No briefing data yet. Run refresh_now.") }
    }

    private fun briefNextAction(state: LiveBriefingState): String =
        when {
            state.reminders.any { it.status == ReminderStatus.DUE } -> "Open due reminders."
            state.snapshots.isEmpty() -> "Run refresh_now."
            state.snapshots
                .last()
                .errors
                .isNotEmpty() -> "Retry refresh later; keep cached data visible."
            else -> "Review top headlines and weather before planning the next step."
        }

    private fun validateCity(city: String): String? =
        when {
            city.length !in 2..80 -> "`city` must be between 2 and 80 characters."
            !CITY_PATTERN.matches(city) -> "`city` contains unsupported characters."
            else -> null
        }

    private fun validateFeedIds(feedIds: List<String>): String? =
        when {
            feedIds.isEmpty() -> "`feedIds` must not be empty."
            feedIds.size > MAX_FEEDS -> "`feedIds` supports at most $MAX_FEEDS feeds."
            feedIds.any { it !in allowedFeedIds } -> "`feedIds` must use built-in feed ids: ${allowedFeedIds.sorted()}."
            else -> null
        }

    private fun nowString(): String = clock.instant().toString()
}

class LiveBriefingStore(
    private val path: Path,
    private val json: Json = DEFAULT_JSON,
) {
    fun load(): LiveBriefingState {
        if (!Files.exists(path)) return LiveBriefingState()
        return runCatching {
            json.decodeFromString<LiveBriefingState>(Files.readString(path))
        }.getOrDefault(LiveBriefingState())
    }

    fun save(state: LiveBriefingState) {
        path.parent?.let { Files.createDirectories(it) }
        val tempPath = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tempPath, json.encodeToString(state))
        Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

interface WeatherClient {
    fun fetchWeather(city: String): FetchResult<WeatherSnapshot>
}

interface RssClient {
    fun fetch(feedId: String): FetchResult<List<NewsItem>>
}

sealed interface FetchResult<out T> {
    data class Success<T>(
        val value: T,
    ) : FetchResult<T>

    data class Failure(
        val message: String,
    ) : FetchResult<Nothing>
}

class OpenMeteoWeatherClient(
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build(),
    private val json: Json = DEFAULT_JSON,
) : WeatherClient {
    override fun fetchWeather(city: String): FetchResult<WeatherSnapshot> {
        val location = geocode(city) ?: return FetchResult.Failure("City not found: $city.")
        val endpoint =
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${location.latitude}" +
                "&longitude=${location.longitude}" +
                "&current=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m" +
                "&hourly=precipitation_probability" +
                "&forecast_days=1" +
                "&timezone=auto"
        val root = getJson(endpoint) ?: return FetchResult.Failure("Open-Meteo forecast request failed.")
        val current = root["current"]?.jsonObjectOrNull() ?: return FetchResult.Failure("Open-Meteo forecast missing current data.")
        val probability =
            root["hourly"]
                ?.jsonObjectOrNull()
                ?.get("precipitation_probability")
                ?.jsonArrayOrNull()
                ?.firstOrNull()
                ?.jsonPrimitiveOrNull()
                ?.intOrNull
        return FetchResult.Success(
            WeatherSnapshot(
                city = location.name,
                country = location.country,
                latitude = location.latitude,
                longitude = location.longitude,
                temperatureCelsius = current.doubleOrZero("temperature_2m"),
                apparentTemperatureCelsius = current.doubleOrZero("apparent_temperature"),
                precipitationProbabilityPercent = probability ?: 0,
                precipitationMm = current.doubleOrZero("precipitation"),
                windSpeedKmh = current.doubleOrZero("wind_speed_10m"),
                weatherCode = current.intOrZero("weather_code"),
            ),
        )
    }

    private fun geocode(city: String): Location? {
        val endpoint =
            "https://geocoding-api.open-meteo.com/v1/search" +
                "?name=${city.urlEncode()}" +
                "&count=1&language=en&format=json"
        val root = getJson(endpoint) ?: return null
        val item =
            root["results"]
                ?.jsonArrayOrNull()
                ?.firstOrNull()
                ?.jsonObjectOrNull()
                ?: return null
        return Location(
            name = item.stringOrNull("name") ?: city,
            country = item.stringOrNull("country").orEmpty(),
            latitude = item.doubleOrZero("latitude"),
            longitude = item.doubleOrZero("longitude"),
        )
    }

    private fun getJson(endpoint: String): JsonObject? {
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("User-Agent", "aichallenge-live-briefing-mcp")
                .GET()
                .build()
        val response =
            runCatching {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            }.getOrNull() ?: return null
        if (response.statusCode() !in 200..299) return null
        return runCatching { json.parseToJsonElement(response.body().take(MAX_RESPONSE_CHARS)).jsonObject }.getOrNull()
    }

    private data class Location(
        val name: String,
        val country: String,
        val latitude: Double,
        val longitude: Double,
    )
}

class JvmRssClient(
    customFeeds: Map<String, URI> = emptyMap(),
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build(),
) : RssClient {
    private val feeds = ALLOWED_FEEDS + customFeeds

    override fun fetch(feedId: String): FetchResult<List<NewsItem>> {
        val uri = feeds[feedId] ?: return FetchResult.Failure("Feed is not allowed: $feedId.")
        if (uri.scheme != "https") return FetchResult.Failure("Feed must use HTTPS: $feedId.")
        val request =
            HttpRequest
                .newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/rss+xml, application/xml, text/xml")
                .header("User-Agent", "aichallenge-live-briefing-mcp")
                .GET()
                .build()
        val response =
            runCatching {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            }.getOrElse { throwable ->
                return FetchResult.Failure("RSS request failed for $feedId: ${throwable.message.orEmpty()}")
            }
        if (response.statusCode() !in 200..299) {
            return FetchResult.Failure("RSS request failed for $feedId with HTTP ${response.statusCode()}.")
        }
        val body = response.body().take(MAX_RESPONSE_CHARS)
        return parseRss(feedId, body)
    }

    private fun parseRss(
        feedId: String,
        body: String,
    ): FetchResult<List<NewsItem>> =
        runCatching {
            val factory =
                DocumentBuilderFactory.newInstance().apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    isExpandEntityReferences = false
                }
            val document = factory.newDocumentBuilder().parse(InputSource(StringReader(body)))
            val items = document.getElementsByTagName("item")
            if (items.length > 0) {
                (0 until items.length).mapNotNull { index ->
                    val element = items.item(index) as? Element ?: return@mapNotNull null
                    element.toNewsItem(feedId)
                }
            } else {
                val entries = document.getElementsByTagName("entry")
                (0 until entries.length).mapNotNull { index ->
                    val element = entries.item(index) as? Element ?: return@mapNotNull null
                    element.toNewsItem(feedId)
                }
            }.take(MAX_FEED_ITEMS)
        }.fold(
            onSuccess = { FetchResult.Success(it) },
            onFailure = { FetchResult.Failure("RSS response could not be parsed for $feedId: ${it.message.orEmpty()}") },
        )

    private fun Element.toNewsItem(feedId: String): NewsItem? {
        val title = childText("title").take(MAX_TITLE_LENGTH).takeIf { it.isNotBlank() } ?: return null
        val link = childText("link").ifBlank { childAttribute("link", "href") }
        return NewsItem(
            id = "$feedId:${title.hashCode()}",
            source = feedId,
            title = title,
            link = link.takeIf { it.startsWith("https://") }.orEmpty(),
            publishedAt = childText("pubDate").ifBlank { childText("updated") }.ifBlank { childText("published") },
        )
    }
}

@Serializable
data class LiveBriefingState(
    val config: BriefingConfig = BriefingConfig(),
    val snapshots: List<BriefingSnapshot> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val events: List<BriefingEvent> = emptyList(),
)

@Serializable
data class BriefingConfig(
    val city: String = DEFAULT_CITY,
    val feedIds: List<String> = DEFAULT_FEED_IDS,
)

@Serializable
data class BriefingSnapshot(
    val createdAt: String,
    val weather: WeatherSnapshot? = null,
    val newsItems: List<NewsItem> = emptyList(),
    val errors: List<BriefingError> = emptyList(),
)

@Serializable
data class WeatherSnapshot(
    val city: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val temperatureCelsius: Double,
    val apparentTemperatureCelsius: Double,
    val precipitationProbabilityPercent: Int,
    val precipitationMm: Double,
    val windSpeedKmh: Double,
    val weatherCode: Int,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("city", city)
            put("country", country)
            put("latitude", latitude)
            put("longitude", longitude)
            put("temperatureCelsius", temperatureCelsius)
            put("apparentTemperatureCelsius", apparentTemperatureCelsius)
            put("precipitationProbabilityPercent", precipitationProbabilityPercent)
            put("precipitationMm", precipitationMm)
            put("windSpeedKmh", windSpeedKmh)
            put("weatherCode", weatherCode)
        }
}

@Serializable
data class NewsItem(
    val id: String,
    val source: String,
    val title: String,
    val link: String,
    val publishedAt: String,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            put("source", source)
            put("title", title)
            put("link", link)
            put("publishedAt", publishedAt)
        }
}

@Serializable
data class Reminder(
    val id: String,
    val title: String,
    val body: String = "",
    val createdAt: String,
    val nextDueAt: String,
    val repeatEverySeconds: Long? = null,
    val status: ReminderStatus,
    val lastCompletedAt: String = "",
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            put("title", title)
            put("body", body)
            put("createdAt", createdAt)
            put("nextDueAt", nextDueAt)
            repeatEverySeconds?.let { put("repeatEverySeconds", it) }
            put("status", status.serialName)
            put("lastCompletedAt", lastCompletedAt)
        }
}

@Serializable
enum class ReminderStatus(
    val serialName: String,
) {
    @SerialName("scheduled")
    SCHEDULED("scheduled"),

    @SerialName("due")
    DUE("due"),

    @SerialName("completed")
    COMPLETED("completed"),
}

@Serializable
data class BriefingError(
    val source: String,
    val message: String,
    val lastFailureAt: String,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("source", source)
            put("message", message)
            put("lastFailureAt", lastFailureAt)
        }
}

@Serializable
data class BriefingEvent(
    val id: String,
    val createdAt: String,
    val type: String,
    val title: String,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            put("createdAt", createdAt)
            put("type", type)
            put("title", title)
        }
}

data class LiveBriefingResponse(
    val text: String,
    val structuredContent: JsonObject,
    val isError: Boolean = false,
) {
    companion object {
        fun success(
            text: String,
            structuredContent: JsonObject,
        ): LiveBriefingResponse = LiveBriefingResponse(text = text, structuredContent = structuredContent)

        fun error(
            text: String,
            structuredContent: JsonObject,
        ): LiveBriefingResponse = LiveBriefingResponse(text = text, structuredContent = structuredContent, isError = true)
    }
}

data class McpToolCallResult(
    val text: String,
    val structuredContent: JsonObject? = null,
    val isError: Boolean = false,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                )
            }
            structuredContent?.let { put("structuredContent", it) }
            if (isError) {
                put("isError", true)
            }
        }

    companion object {
        fun error(message: String): McpToolCallResult = McpToolCallResult(text = message, isError = true)
    }
}

private fun LiveBriefingResponse.toToolResult(): McpToolCallResult =
    McpToolCallResult(
        text = text,
        structuredContent = structuredContent,
        isError = isError,
    )

private fun liveBriefingSchema(): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put(
                    "description",
                    "One of configure, refresh_now, summary, add_reminder, complete_reminder, timeline, demo_setup, reset.",
                )
                putJsonArray("enum") {
                    listOf(
                        "configure",
                        "refresh_now",
                        "summary",
                        "add_reminder",
                        "complete_reminder",
                        "timeline",
                        "demo_setup",
                        "reset",
                    ).forEach { add(JsonPrimitive(it)) }
                }
            }
            putJsonObject("city") {
                put("type", "string")
                put("description", "City used by configure.")
            }
            putJsonObject("feedIds") {
                put("type", "array")
                put("description", "Built-in RSS feed ids used by configure.")
                putJsonObject("items") {
                    put("type", "string")
                    putJsonArray("enum") {
                        ALLOWED_FEEDS.keys.sorted().forEach { add(JsonPrimitive(it)) }
                    }
                }
                put("maxItems", MAX_FEEDS)
            }
            putJsonObject("title") {
                put("type", "string")
                put("description", "Reminder title used by add_reminder.")
                put("maxLength", MAX_TITLE_LENGTH)
            }
            putJsonObject("body") {
                put("type", "string")
                put("description", "Optional reminder body.")
            }
            putJsonObject("dueAt") {
                put("type", "string")
                put("description", "ISO-8601 UTC timestamp used by add_reminder.")
            }
            putJsonObject("delaySeconds") {
                put("type", "integer")
                put("description", "Delay from now used by add_reminder.")
                put("minimum", 1)
            }
            putJsonObject("repeatEverySeconds") {
                put("type", "integer")
                put("description", "Optional recurring reminder interval.")
                put("minimum", MIN_REPEAT_SECONDS)
            }
            putJsonObject("id") {
                put("type", "string")
                put("description", "Reminder id used by complete_reminder.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("action"))))
        put("additionalProperties", false)
    }

private fun customFeedsFromEnvironment(raw: String?): Map<String, URI> =
    raw
        ?.split(",")
        ?.mapIndexedNotNull { index, value ->
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return@mapIndexedNotNull null
            val uri = runCatching { URI.create(trimmed) }.getOrNull() ?: return@mapIndexedNotNull null
            if (uri.scheme != "https") return@mapIndexedNotNull null
            "custom_${index + 1}" to uri
        }?.toMap()
        .orEmpty()

private fun HttpExchange.writeText(
    statusCode: Int,
    body: String,
) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    sendResponseHeaders(statusCode, bytes.size.toLong())
    responseBody.use { output -> output.write(bytes) }
}

private fun JsonObject.stringOrNull(key: String): String? =
    this[key]
        ?.jsonPrimitiveOrNull()
        ?.contentOrNull

private fun JsonObject.longOrNull(key: String): Long? =
    this[key]
        ?.jsonPrimitiveOrNull()
        ?.contentOrNull
        ?.toLongOrNull()

private fun JsonObject.stringArrayOrNull(key: String): List<String>? =
    this[key]
        ?.jsonArrayOrNull()
        ?.mapNotNull { element -> element.jsonPrimitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() } }

private fun JsonObject.doubleOrZero(key: String): Double =
    this[key]
        ?.jsonPrimitiveOrNull()
        ?.doubleOrNull
        ?: 0.0

private fun JsonObject.intOrZero(key: String): Int =
    this[key]
        ?.jsonPrimitiveOrNull()
        ?.intOrNull
        ?: 0

private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

private fun JsonElement.jsonArrayOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()

private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = runCatching { jsonPrimitive }.getOrNull()

private fun Element.childText(name: String): String {
    val nodes = getElementsByTagName(name)
    if (nodes.length == 0) return ""
    return nodes
        .item(0)
        ?.textContent
        ?.trim()
        .orEmpty()
}

private fun Element.childAttribute(
    name: String,
    attribute: String,
): String {
    val nodes = getElementsByTagName(name)
    if (nodes.length == 0) return ""
    val element = nodes.item(0) as? Element ?: return ""
    return element.getAttribute(attribute).trim()
}

private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")

private fun nextId(): String = UUID.randomUUID().toString()

data class HttpResponseBody(
    val statusCode: Int,
    val body: String,
)

private const val DEFAULT_PORT = 8765
private const val DEFAULT_CITY = "San Francisco"
private const val DEFAULT_REFRESH_SECONDS = 600L
private const val DEFAULT_REMINDER_SECONDS = 30L
private const val MIN_REFRESH_SECONDS = 30L
private const val MIN_REMINDER_SECONDS = 5L
private const val MIN_REPEAT_SECONDS = 30L
private const val MAX_DELAY_SECONDS = 2_592_000L
private const val MAX_RESPONSE_CHARS = 500_000
private const val MAX_TITLE_LENGTH = 180
private const val MAX_FEEDS = 5
private const val MAX_FEED_ITEMS = 5
private const val MAX_NEWS_ITEMS = 20
private const val MAX_SNAPSHOTS = 48
private const val MAX_REMINDERS = 100
private const val MAX_EVENTS = 100
private const val MAX_ERRORS = 10
const val LIVE_BRIEFING_TOOL = "live_briefing"
private const val JSON_RPC_VERSION = "2.0"
private const val MCP_PROTOCOL_VERSION = "2025-06-18"
private const val JSON_RPC_PARSE_ERROR = -32700
private const val JSON_RPC_METHOD_NOT_FOUND = -32601
private const val JSON_RPC_INVALID_PARAMS = -32602
private val DEFAULT_FEED_IDS = listOf("bbc_world", "hacker_news")
private val CITY_PATTERN = Regex("[\\p{L}0-9 .,'-]+")
private val ALLOWED_FEEDS =
    mapOf(
        "bbc_world" to URI.create("https://feeds.bbci.co.uk/news/world/rss.xml"),
        "hacker_news" to URI.create("https://hnrss.org/frontpage"),
        "nasa_breaking" to URI.create("https://www.nasa.gov/news-release/feed/"),
    )
private val DEFAULT_JSON =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
    }
