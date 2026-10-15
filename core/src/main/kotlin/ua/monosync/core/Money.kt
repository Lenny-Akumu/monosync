package ua.monosync.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

enum class Currency(val iso: Int, val code: String, val symbol: String) {
    UAH(980, "UAH", "₴"),
    EUR(978, "EUR", "€"),
    USD(840, "USD", "$"),
    GBP(826, "GBP", "£"),
    PLN(985, "PLN", "zł"),
    NOK(578, "NOK", "kr"),
    SEK(752, "SEK", "kr"),
    DKK(208, "DKK", "kr"),
    CHF(756, "CHF", "CHF");

    companion object {
        fun byIso(iso: Int?): Currency? = entries.firstOrNull { it.iso == iso }
        fun byCode(code: String?): Currency? = entries.firstOrNull { it.code.equals(code, true) }

        fun bySymbol(raw: String?): Currency? {
            val s = raw?.trim()?.lowercase()?.trimEnd('.') ?: return null
            return when (s) {
                "₴", "грн", "uah" -> UAH
                "€", "eur" -> EUR
                "$", "usd" -> USD
                "£", "gbp" -> GBP
                "zł", "pln" -> PLN
                "nok" -> NOK
                "sek" -> SEK
                "dkk" -> DKK
                "chf" -> CHF
                else -> null
            }
        }
    }
}

object Money {
    private val spaces = Regex("[\\s\\u00A0\\u202F\\u2009\\u2007]")

    fun parseMinor(raw: String): Long? {
        var s = raw.replace(spaces, "").replace('−', '-').replace('–', '-')
        if (s.isEmpty()) return null
        s = when {
            s.contains(',') && s.contains('.') -> s.replace(",", "")
            s.contains(',') -> s.replace(',', '.')
            else -> s
        }
        return runCatching {
            BigDecimal(s).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        }.getOrNull()
    }

    fun toDecimal(minor: Long): BigDecimal = BigDecimal.valueOf(minor, 2)

    private val formatter = ThreadLocal.withInitial {
        DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US).apply {
            groupingSeparator = ' '
            decimalSeparator = ','
        })
    }

    fun format(minor: Long, currency: Currency? = Currency.UAH, signed: Boolean = false): String {
        val sign = when {
            minor < 0 -> "−"
            signed && minor > 0 -> "+"
            else -> ""
        }
        val body = formatter.get().format(toDecimal(abs(minor)))
        return if (currency == null) "$sign$body" else "$sign$body ${currency.symbol}"
    }

    fun formatShort(minor: Long, currency: Currency? = Currency.UAH): String {
        val whole = abs(minor) / 100
        val sign = if (minor < 0) "−" else ""
        val body = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US).apply { groupingSeparator = ' ' })
            .format(whole)
        return if (currency == null) "$sign$body" else "$sign$body ${currency.symbol}"
    }

    fun convert(minor: Long, rate: Double): Long =
        BigDecimal.valueOf(minor).divide(BigDecimal.valueOf(rate), 0, RoundingMode.HALF_UP).toLong()

    fun multiply(minor: Long, rate: Double): Long =
        BigDecimal.valueOf(minor).multiply(BigDecimal.valueOf(rate)).setScale(0, RoundingMode.HALF_UP).toLong()
}
