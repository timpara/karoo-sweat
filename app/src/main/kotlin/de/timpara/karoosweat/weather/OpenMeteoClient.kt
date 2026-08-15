package de.timpara.karoosweat.weather

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.timeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/** Cached ambient conditions, with enough metadata to judge staleness. */
@Serializable
data class WeatherSnapshot(
    val temperatureC: Double,
    val relativeHumidityPct: Double,
    val latitude: Double,
    val longitude: Double,
    val fetchedAtEpochMs: Long,
) {
    fun ageMs(nowMs: Long): Long = nowMs - fetchedAtEpochMs

    /**
     * Weather this old is still far better than a guess, but a rider who has
     * descended 1500 m or ridden into the evening should not be trusting it.
     */
    fun isStale(nowMs: Long): Boolean = ageMs(nowMs) > STALE_AFTER_MS

    companion object {
        const val STALE_AFTER_MS = 3 * 60 * 60 * 1000L
    }
}

@Serializable
private data class OpenMeteoResponse(
    val current: Current,
) {
    @Serializable
    data class Current(
        @SerialName("temperature_2m") val temperature: Double? = null,
        @SerialName("relative_humidity_2m") val relativeHumidity: Double? = null,
    )
}

/**
 * Minimal Open-Meteo client.
 *
 * Requests go through [OnHttpResponse.MakeHttpRequest] rather than a normal HTTP
 * stack. That is not an arbitrary choice: the Karoo routes these through the
 * companion phone when the head unit itself has no connectivity, which is the usual
 * situation mid-ride. A plain OkHttp or Ktor call would simply fail.
 *
 * Open-Meteo needs no API key and permits non-commercial use, which is why it is
 * preferred over OpenWeatherMap here.
 */
class OpenMeteoClient(private val karooSystem: KarooSystemService) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(latitude: Double, longitude: Double): WeatherSnapshot? {
        val url = buildString {
            append("https://api.open-meteo.com/v1/forecast")
            append("?latitude=").append("%.4f".format(latitude))
            append("&longitude=").append("%.4f".format(longitude))
            append("&current=temperature_2m,relative_humidity_2m")
            append("&timeformat=unixtime&forecast_days=1")
        }

        val response = request(url) ?: return null
        if (response.statusCode !in 200..299) return null
        val body = response.body ?: return null

        return try {
            val parsed = json.decodeFromString<OpenMeteoResponse>(String(body))
            val temp = parsed.current.temperature ?: return null
            val humidity = parsed.current.relativeHumidity ?: return null
            WeatherSnapshot(
                temperatureC = temp,
                relativeHumidityPct = humidity,
                latitude = latitude,
                longitude = longitude,
                fetchedAtEpochMs = System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            null
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private suspend fun request(url: String): HttpResponseState.Complete? =
        callbackFlow<HttpResponseState.Complete?> {
            val consumerId = karooSystem.addConsumer(
                OnHttpResponse.MakeHttpRequest(
                    method = "GET",
                    url = url,
                    headers = mapOf("User-Agent" to USER_AGENT),
                    // Do not block indefinitely waiting for the phone to reappear; a
                    // stale cached reading is more useful than a hung coroutine.
                    waitForConnection = false,
                ),
                onEvent = { event: OnHttpResponse ->
                    (event.state as? HttpResponseState.Complete)?.let {
                        trySend(it)
                        close()
                    }
                },
                onError = { trySend(null); close() },
            )
            awaitClose { karooSystem.removeConsumer(consumerId) }
        }
            .timeout(REQUEST_TIMEOUT)
            .catch { if (it is TimeoutCancellationException) emit(null) else throw it }
            .firstOrNull()

    private companion object {
        const val USER_AGENT = "karoo-sweat"
        val REQUEST_TIMEOUT = 30.seconds
    }
}
