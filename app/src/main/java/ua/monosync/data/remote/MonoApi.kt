package ua.monosync.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class MonoClientInfo(
    val clientId: String? = null,
    val name: String? = null,
    val accounts: List<MonoAccount> = emptyList(),
    val jars: List<MonoJar> = emptyList(),
)

@Serializable
data class MonoAccount(
    val id: String,
    val sendId: String? = null,
    val balance: Long = 0,
    val creditLimit: Long = 0,
    val type: String? = null,
    val currencyCode: Int = 980,
    val maskedPan: List<String> = emptyList(),
    val iban: String? = null,
)

@Serializable
data class MonoJar(
    val id: String,
    val title: String? = null,
    val currencyCode: Int = 980,
    val balance: Long = 0,
    val goal: Long? = null,
)

@Serializable
data class MonoStatementItem(
    val id: String,
    val time: Long,
    val description: String = "",
    val mcc: Int? = null,
    val originalMcc: Int? = null,
    val hold: Boolean = false,
    val amount: Long,
    val operationAmount: Long? = null,
    val currencyCode: Int? = null,
    val cashbackAmount: Long? = null,
    val balance: Long? = null,
    val comment: String? = null,
    val counterName: String? = null,
)

@Serializable
data class MonoRate(
    val currencyCodeA: Int,
    val currencyCodeB: Int,
    val date: Long = 0,
    val rateBuy: Double? = null,
    val rateSell: Double? = null,
    val rateCross: Double? = null,
)

class MonoApi(private val tokenProvider: () -> String?) {
    private val base = "https://api.monobank.ua"

    private fun headers(): Map<String, String> {
        val token = tokenProvider() ?: throw ApiException(401, "Токен Monobank не задано")
        return mapOf("X-Token" to token)
    }

    suspend fun clientInfo(): MonoClientInfo =
        Http.json.decodeFromString(MonoClientInfo.serializer(), Http.call("GET", "$base/personal/client-info", headers()))

    suspend fun statement(accountId: String, fromSec: Long, toSec: Long): List<MonoStatementItem> =
        Http.json.decodeFromString(
            ListSerializer(MonoStatementItem.serializer()),
            Http.call("GET", "$base/personal/statement/$accountId/$fromSec/$toSec", headers()),
        )

    suspend fun rates(): List<MonoRate> =
        Http.json.decodeFromString(ListSerializer(MonoRate.serializer()), Http.call("GET", "$base/bank/currency", emptyMap()))
}
