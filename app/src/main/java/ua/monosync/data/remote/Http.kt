package ua.monosync.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : Exception(message)

object Http {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; isLenient = true }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun call(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.method(method, body?.toRequestBody(jsonType))
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, errorMessage(resp.code, text))
            text
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        val msg = runCatching {
            val obj = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
            (obj?.get("errorDescription") ?: obj?.get("message"))?.toString()?.trim('"')
        }.getOrNull()
        return when (code) {
            401, 403 -> "Невірний або відкликаний токен" + (msg?.let { ": $it" } ?: "")
            404 -> "Не знайдено" + (msg?.let { ": $it" } ?: "")
            429 -> "Забагато запитів, спробую пізніше"
            else -> "HTTP $code" + (msg?.let { ": $it" } ?: "")
        }
    }
}
