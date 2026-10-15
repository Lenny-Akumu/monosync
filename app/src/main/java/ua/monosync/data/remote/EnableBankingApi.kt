package ua.monosync.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

data class EbAspsp(val name: String, val country: String, val maxConsentSeconds: Long?)

data class EbAccount(val uid: String, val name: String?, val iban: String?, val currency: String?, val product: String?)

data class EbSession(val id: String, val validUntil: Long, val accounts: List<EbAccount>)

data class EbBalance(val amountMinor: Long, val currency: String?)

data class EbTransaction(
    val id: String?,
    val amountMinor: Long,
    val currency: String?,
    val credit: Boolean,
    val booked: Boolean,
    val date: String?,
    val counterparty: String?,
    val remittance: String?,
    val mcc: Int?,
    val balanceAfterMinor: Long?,
)

/** Enable Banking (PSD2 account information) client; every call is signed with the app's own RSA key. */
class EnableBankingApi(private val appId: () -> String?, private val pem: () -> String?) {
    private val base = "https://api.enablebanking.com"
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private fun jwt(): String {
        val id = appId()?.takeIf { it.isNotBlank() } ?: throw ApiException(401, "Не задано Application ID")
        val key = privateKey(pem() ?: throw ApiException(401, "Не завантажено ключ .pem"))
        val now = System.currentTimeMillis() / 1000
        val header = buildJsonObject { put("typ", "JWT"); put("alg", "RS256"); put("kid", id.trim()) }
        val payload = buildJsonObject {
            put("iss", "enablebanking.com")
            put("aud", "api.enablebanking.com")
            put("iat", now)
            put("exp", now + 3600)
        }
        val unsigned = b64.encodeToString(header.toString().toByteArray()) + "." + b64.encodeToString(payload.toString().toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(key)
            update(unsigned.toByteArray())
        }.sign()
        return unsigned + "." + b64.encodeToString(sig)
    }

    private fun headers() = mapOf("Authorization" to "Bearer ${jwt()}")

    private suspend fun get(path: String): JsonObject =
        Http.json.parseToJsonElement(Http.call("GET", base + path, headers())).jsonObject

    private suspend fun post(path: String, body: JsonObject): JsonObject =
        Http.json.parseToJsonElement(Http.call("POST", base + path, headers(), body.toString())).jsonObject

    suspend fun aspsps(country: String): List<EbAspsp> {
        val o = get("/aspsps?country=${enc(country)}&psu_type=personal&service=AIS")
        return (o["aspsps"] as? JsonArray).orEmpty().mapNotNull { el ->
            val a = el as? JsonObject ?: return@mapNotNull null
            EbAspsp(a.str("name") ?: return@mapNotNull null, a.str("country") ?: country, a.num("maximum_consent_validity"))
        }.sortedBy { it.name.lowercase() }
    }

    suspend fun startAuth(aspsp: String, country: String, redirectUrl: String, state: String, maxConsentSeconds: Long?): String {
        val seconds = minOf(maxConsentSeconds ?: 90L * 24 * 3600, 180L * 24 * 3600) - 3600
        val body = buildJsonObject {
            putJsonObject("access") { put("valid_until", Instant.now().plusSeconds(seconds).toString()) }
            putJsonObject("aspsp") { put("name", aspsp); put("country", country) }
            put("state", state)
            put("redirect_url", redirectUrl)
            put("psu_type", "personal")
        }
        return post("/auth", body).str("url") ?: throw ApiException(500, "Банк не повернув посилання для входу")
    }

    suspend fun createSession(code: String): EbSession {
        val o = post("/sessions", buildJsonObject { put("code", code) })
        val id = o.str("session_id") ?: throw ApiException(500, "Не вдалося створити сесію")
        val until = (o["access"] as? JsonObject)?.str("valid_until")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
        val accounts = (o["accounts"] as? JsonArray).orEmpty().mapNotNull { el ->
            val a = el as? JsonObject ?: return@mapNotNull null
            EbAccount(
                uid = a.str("uid") ?: return@mapNotNull null,
                name = a.str("name") ?: a.str("details"),
                iban = (a["account_id"] as? JsonObject)?.str("iban") ?: (a["account_id"] as? JsonObject)?.let { acc -> (acc["other"] as? JsonObject)?.str("identification") },
                currency = a.str("currency"),
                product = a.str("product"),
            )
        }
        return EbSession(id, until, accounts)
    }

    suspend fun deleteSession(id: String) {
        runCatching { Http.call("DELETE", "$base/sessions/$id", headers()) }
    }

    suspend fun balance(uid: String): EbBalance? {
        val list = (get("/accounts/$uid/balances")["balances"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val order = listOf("ITAV", "CLAV", "XPCD", "ITBD", "CLBD", "OPAV", "OPBD")
        val best = list.minByOrNull { b -> order.indexOf(b.str("balance_type")).let { if (it < 0) 99 else it } } ?: return null
        val amount = best["balance_amount"] as? JsonObject ?: return null
        return EbBalance(ua.monosync.core.Money.parseMinor(amount.str("amount") ?: return null) ?: return null, amount.str("currency"))
    }

    suspend fun transactions(uid: String, dateFrom: String): List<EbTransaction> {
        val out = mutableListOf<EbTransaction>()
        var continuation: String? = null
        var pages = 0
        do {
            val q = "date_from=${enc(dateFrom)}" + (continuation?.let { "&continuation_key=${enc(it)}" } ?: "")
            val o = get("/accounts/$uid/transactions?$q")
            (o["transactions"] as? JsonArray).orEmpty().forEach { el -> (el as? JsonObject)?.let { parseTx(it) }?.let(out::add) }
            continuation = o.str("continuation_key")
            pages++
        } while (continuation != null && pages < 20)
        return out
    }

    private fun parseTx(t: JsonObject): EbTransaction? {
        val amount = t["transaction_amount"] as? JsonObject ?: return null
        val minor = ua.monosync.core.Money.parseMinor(amount.str("amount") ?: return null) ?: return null
        val credit = t.str("credit_debit_indicator") == "CRDT"
        val party = if (credit) (t["debtor"] as? JsonObject)?.str("name") else (t["creditor"] as? JsonObject)?.str("name")
        val remittance = (t["remittance_information"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.joinToString(" ")?.trim()?.ifBlank { null }
        val balanceAfter = ((t["balance_after_transaction"] as? JsonObject)?.get("balance_amount") as? JsonObject)?.str("amount")
            ?.let { ua.monosync.core.Money.parseMinor(it) }
        return EbTransaction(
            id = t.str("entry_reference") ?: t.str("transaction_id"),
            amountMinor = kotlin.math.abs(minor),
            currency = amount.str("currency"),
            credit = credit,
            booked = (t.str("status") ?: "BOOK") == "BOOK",
            date = t.str("transaction_date") ?: t.str("booking_date") ?: t.str("value_date"),
            counterparty = party,
            remittance = remittance,
            mcc = t.str("merchant_category_code")?.toIntOrNull(),
            balanceAfterMinor = balanceAfter,
        )
    }

    companion object {
        fun privateKey(pem: String): PrivateKey {
            if (pem.contains("BEGIN RSA PRIVATE KEY")) {
                throw ApiException(400, "Ключ у форматі PKCS#1. Потрібен файл з рядком BEGIN PRIVATE KEY")
            }
            val body = pem.lines().filter { !it.startsWith("-----") }.joinToString("").replace(Regex("\\s"), "")
            val bytes = runCatching { Base64.getDecoder().decode(body) }.getOrElse { throw ApiException(400, "Файл не схожий на ключ .pem") }
            return runCatching { KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes)) }
                .getOrElse { throw ApiException(400, "Не вдалося прочитати ключ .pem") }
        }

        fun codeFrom(url: String): String? =
            Regex("[?&]code=([^&#]+)").find(url)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }

        fun errorFrom(url: String): String? =
            Regex("[?&]error_description=([^&#]+)").find(url)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?: Regex("[?&]error=([^&#]+)").find(url)?.groupValues?.get(1)
    }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.num(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

@Suppress("unused")
private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
