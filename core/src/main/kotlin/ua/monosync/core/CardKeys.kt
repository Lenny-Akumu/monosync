package ua.monosync.core

object CardKeys {
    private val endings = listOf("ої", "ою", "ій", "ий", "ую", "ої", "ого", "ому", "а", "у", "і", "ї", "е", "я", "ю")

    fun normalize(phrase: String): String =
        phrase.lowercase()
            .replace(Regex("[«»\"'’`]"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                val ending = endings.sortedByDescending { it.length }
                    .firstOrNull { word.length > it.length + 2 && word.endsWith(it) }
                if (ending != null) word.dropLast(ending.length) else word
            }

    /** Nominative display name: "Білої" -> "Біла картка". */
    fun displayName(phrase: String): String {
        val key = normalize(phrase)
        val known = mapOf(
            "чорн" to "Чорна", "біл" to "Біла", "залізн" to "Залізна", "платинов" to "Платинова",
            "жовт" to "Жовта", "євров" to "Єврова", "доларов" to "Доларова", "фоп" to "ФОП",
            "дитяч" to "Дитяча", "кредитн" to "Кредитна", "зарплатн" to "Зарплатна",
        )
        val name = known.entries.firstOrNull { key.contains(it.key) }?.value
            ?: phrase.trim().replaceFirstChar { it.uppercase() }
        return "$name картка"
    }

    fun guessType(key: String): String? = when {
        key.contains("чорн") -> "black"
        key.contains("біл") -> "white"
        key.contains("залізн") -> "iron"
        key.contains("платин") -> "platinum"
        key.contains("жовт") -> "yellow"
        key.contains("фоп") -> "fop"
        key.contains("підтримк") || key.contains("eaid") -> "eAid"
        else -> null
    }

    fun guessCurrency(key: String): Currency? = when {
        key.contains("євро") || key.contains("eur") -> Currency.EUR
        key.contains("долар") || key.contains("usd") -> Currency.USD
        key.contains("злот") || key.contains("pln") -> Currency.PLN
        key.contains("фунт") || key.contains("gbp") -> Currency.GBP
        else -> null
    }

    fun typeTitle(type: String?): String? = when (type) {
        "black" -> "Чорна"
        "white" -> "Біла"
        "iron" -> "Залізна"
        "platinum" -> "Платинова"
        "yellow" -> "Жовта"
        "fop" -> "ФОП"
        "eAid" -> "єПідтримка"
        "madeInUkraine" -> "Made in Ukraine"
        "rebuilding" -> "Відновлення"
        else -> null
    }

    /** Key for an API account so it lines up with what notifications say ("з білої картки"). */
    fun keyForApi(type: String?, currency: Currency?): String = when {
        currency == Currency.EUR -> "євров"
        currency == Currency.USD -> "доларов"
        currency == Currency.PLN -> "злотов"
        currency == Currency.GBP -> "фунтов"
        type != null -> typeTitle(type)?.let { normalize(it) } ?: type.lowercase()
        else -> "картк"
    }
}
