package com.tc.gymtrainer.authbot.platform

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.future.await
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

class ServiceException(message: String) : RuntimeException(message)

class PlatformSettings(
    val allowInsecure: Boolean = false,
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val requestTimeout: Duration = Duration.ofSeconds(20),
    val retryDelay: Duration = Duration.ofSeconds(5),
)

internal class PlatformHttp(url: String, private val key: String, private val settings: PlatformSettings) {
    private val base = url.trimEnd('/') + "/internal/v1/auth"
    private val http = HttpClient.newBuilder().connectTimeout(settings.connectTimeout).build()
    private val json = jacksonObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    init {
        require(key.isNotBlank()) { "Missing platform service key" }
        // The service key and login codes travel on this connection.
        val uri = URI.create(url)
        fun local() = uri.host != null && runCatching { InetAddress.getByName(uri.host).isLoopbackAddress }.getOrDefault(false)
        require(uri.scheme == "https" || (uri.scheme == "http" && (settings.allowInsecure || local()))) {
            "PLATFORM_URL must use https. For a private network set PLATFORM_ALLOW_INSECURE=true"
        }
    }

    suspend inline fun <reified T> post(path: String, body: Any? = null): T {
        val data = body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) }
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(settings.requestTimeout).header("Authorization", "Bearer $key")
            .header("X-Request-Id", UUID.randomUUID().toString())
            .POST(data ?: HttpRequest.BodyPublishers.noBody())
        if (data != null) request.header("Content-Type", "application/json")
        val response = http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()).await()
        val status = response.statusCode()
        if (status !in 200..299) {
            val error = runCatching { json.readTree(response.body()).path("error").textValue() }.getOrNull()
            if (status == 400) throw ServiceException(error ?: "Некорректный запрос.")
            if (status >= 500) throw IOException("Platform HTTP $status")
            throw ServiceException(error ?: "Доступ к платформе отклонён (HTTP $status).")
        }
        @Suppress("UNCHECKED_CAST")
        if (T::class == Unit::class) return Unit as T
        return json.readValue<T>(response.body().ifBlank { "null" })
    }
}
