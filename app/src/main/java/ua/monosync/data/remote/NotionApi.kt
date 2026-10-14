package ua.monosync.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class NotionProperty(
    val name: String,
    val type: String,
    val numberFormat: String? = null,
    val relationDataSourceId: String? = null,
    val relationDatabaseId: String? = null,
)

data class NotionDataSource(
    val id: String,
    val databaseId: String?,
    val title: String,
    val properties: List<NotionProperty>,
)

data class NotionRow(val id: String, val title: String, val numbers: Map<String, Double?>)

class NotionApi(private val tokenProvider: () -> String?) {
    private val base = "https://api.notion.com/v1"

    private fun headers(): Map<String, String> {
        val token = tokenProvider() ?: throw ApiException(401, "Токен Notion не задано")
        return mapOf(
            "Authorization" to "Bearer $token",
            "Notion-Version" to "2025-09-03",
        )
    }

    private suspend fun request(method: String, path: String, body: JsonElement? = null): JsonObject {
        val text = Http.call(method, base + path, headers(), body?.toString())
        return Http.json.parseToJsonElement(text).jsonObject
    }

    suspend fun me(): String {
        val o = request("GET", "/users/me")
        return o.str("name") ?: o["bot"]?.jsonObjectOrNull()?.get("workspace_name")?.jsonPrimitive?.contentOrNull ?: "Notion"
    }

    suspend fun searchDataSources(): List<NotionDataSource> {
        val result = mutableListOf<NotionDataSource>()
        var cursor: String? = null
        do {
            val body = buildJsonObject {
                putJsonObject("filter") { put("property", "object"); put("value", "data_source") }
                put("page_size", 100)
                cursor?.let { put("start_cursor", it) }
            }
            val o = request("POST", "/search", body)
            o["results"]?.jsonArray?.forEach { el -> parseDataSource(el.jsonObject)?.let(result::add) }
            cursor = if (o["has_more"]?.jsonPrimitive?.contentOrNull == "true") o.str("next_cursor") else null
        } while (cursor != null)
        return result
    }

    suspend fun dataSource(id: String): NotionDataSource? = parseDataSource(request("GET", "/data_sources/$id"))

    private fun parseDataSource(o: JsonObject): NotionDataSource? {
        if (o.str("object") != "data_source") return null
        val id = o.str("id") ?: return null
        val title = o["title"].plainText().ifBlank { o["name"]?.jsonPrimitive?.contentOrNull ?: "Без назви" }
        val dbId = o["parent"]?.jsonObjectOrNull()?.str("database_id")
            ?: o["database_parent"]?.jsonObjectOrNull()?.str("database_id")
        val props = o["properties"]?.jsonObjectOrNull()?.map { (key, v) ->
            val p = v.jsonObject
            val type = p.str("type") ?: ""
            NotionProperty(
                name = p.str("name") ?: key,
                type = type,
                numberFormat = p["number"]?.jsonObjectOrNull()?.str("format"),
                relationDataSourceId = p["relation"]?.jsonObjectOrNull()?.str("data_source_id"),
                relationDatabaseId = p["relation"]?.jsonObjectOrNull()?.str("database_id"),
            )
        } ?: emptyList()
        return NotionDataSource(id, dbId, title, props)
    }

    suspend fun queryAll(dataSourceId: String, numberProps: List<String> = emptyList()): List<NotionRow> {
        val rows = mutableListOf<NotionRow>()
        var cursor: String? = null
        do {
            val body = buildJsonObject {
                put("page_size", 100)
                cursor?.let { put("start_cursor", it) }
            }
            val o = request("POST", "/data_sources/$dataSourceId/query", body)
            o["results"]?.jsonArray?.forEach { el ->
                val page = el.jsonObject
                if (page["in_trash"]?.jsonPrimitive?.contentOrNull == "true" ||
                    page["archived"]?.jsonPrimitive?.contentOrNull == "true"
                ) return@forEach
                val props = page["properties"]?.jsonObjectOrNull() ?: return@forEach
                val title = props.values.map { it.jsonObject }.firstOrNull { it.str("type") == "title" }
                    ?.get("title").plainText()
                val numbers = numberProps.associateWith { n ->
                    props[n]?.jsonObjectOrNull()?.get("number")?.let { (it as? JsonPrimitive)?.doubleOrNull }
                }
                rows += NotionRow(page.str("id") ?: return@forEach, title.ifBlank { "Без назви" }, numbers)
            }
            cursor = if (o["has_more"]?.jsonPrimitive?.contentOrNull == "true") o.str("next_cursor") else null
        } while (cursor != null)
        return rows
    }

    suspend fun createPage(dataSourceId: String, properties: JsonObject, children: JsonArray? = null): String {
        val body = buildJsonObject {
            putJsonObject("parent") { put("type", "data_source_id"); put("data_source_id", dataSourceId) }
            put("properties", properties)
            if (children != null) put("children", children)
        }
        return request("POST", "/pages", body).str("id") ?: throw ApiException(500, "Notion не повернув id сторінки")
    }

    suspend fun updatePage(pageId: String, properties: JsonObject) {
        request("PATCH", "/pages/$pageId", buildJsonObject { put("properties", properties) })
    }

    suspend fun trashPage(pageId: String) {
        try {
            request("PATCH", "/pages/$pageId", buildJsonObject { put("in_trash", true) })
        } catch (e: ApiException) {
            if (e.code == 404) return
            request("PATCH", "/pages/$pageId", buildJsonObject { put("archived", true) })
        }
    }

    companion object {
        fun title(text: String) = buildJsonObject {
            putJsonArray("title") { add(buildJsonObject { putJsonObject("text") { put("content", text.take(1900)) } }) }
        }

        fun number(v: Double) = buildJsonObject { put("number", v) }

        fun emptyNumber() = buildJsonObject { put("number", JsonNull) }

        fun date(start: String) = buildJsonObject { putJsonObject("date") { put("start", start) } }

        fun relation(id: String?) = buildJsonObject {
            putJsonArray("relation") { if (id != null) add(buildJsonObject { put("id", id) }) }
        }

        fun paragraph(text: String) = buildJsonObject {
            put("object", "block")
            put("type", "paragraph")
            putJsonObject("paragraph") {
                putJsonArray("rich_text") {
                    add(buildJsonObject {
                        put("type", "text")
                        putJsonObject("text") { put("content", text) }
                        putJsonObject("annotations") { put("color", "gray") }
                    })
                }
            }
        }

        fun blocks(vararg items: JsonObject) = buildJsonArray { items.forEach { add(it) } }
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.plainText(): String {
    val arr = this as? JsonArray ?: return ""
    return arr.joinToString("") { el ->
        val o = el as? JsonObject ?: return@joinToString ""
        o.str("plain_text") ?: o["text"]?.jsonObjectOrNull()?.str("content") ?: ""
    }.trim()
}
