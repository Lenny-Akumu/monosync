package ua.monosync.core

data class MccGroup(val key: String, val title: String, val keywords: List<String>, val codes: Set<Int>)

object Mcc {
    private fun r(a: Int, b: Int) = (a..b).toSet()

    val groups = listOf(
        MccGroup("groceries", "Продукти", listOf("продукт", "їжа", "супермаркет", "grocer", "food"),
            setOf(5411, 5412, 5422, 5441, 5451, 5462, 5499, 5300, 5311, 5331)),
        MccGroup("cafe", "Кафе та ресторани", listOf("кафе", "ресторан", "кава", "фастфуд", "доставка їжі", "cafe", "restaurant"),
            setOf(5812, 5813, 5814)),
        MccGroup("taxi", "Таксі", listOf("таксі", "taxi", "uber", "bolt", "uklon"), setOf(4121)),
        MccGroup("transport", "Транспорт", listOf("транспорт", "проїзд", "метро", "transport"),
            setOf(4111, 4112, 4131, 4784, 4789, 7523, 7512)),
        MccGroup("fuel", "Пальне", listOf("пальне", "бензин", "азс", "авто", "fuel", "car"), setOf(5541, 5542, 5983, 5532, 5533, 7538, 7542)),
        MccGroup("health", "Здоров'я", listOf("здоров", "аптек", "лік", "медиц", "health", "pharm"),
            setOf(5122, 5912, 8011, 8021, 8031, 8041, 8042, 8043, 8049, 8050, 8062, 8071, 8099)),
        MccGroup("clothes", "Одяг", listOf("одяг", "взуття", "шопінг", "clothes", "shopping"),
            setOf(5611, 5621, 5631, 5641, 5651, 5655, 5661, 5681, 5691, 5697, 5698, 5699, 5948)),
        MccGroup("fun", "Розваги", listOf("розваг", "кіно", "ігри", "відпочин", "fun", "entertain"),
            setOf(7832, 7841, 7911, 7922, 7929, 7932, 7933, 7941, 7991, 7992, 7993, 7994, 7996, 7997, 7998, 7999)),
        MccGroup("digital", "Підписки", listOf("підпис", "сервіс", "цифров", "subscr", "digital"),
            setOf(5815, 5816, 5817, 5818, 4899, 5734, 7372)),
        MccGroup("mobile", "Зв'язок", listOf("зв'язок", "мобіль", "інтернет", "телефон", "mobile"), setOf(4812, 4814, 4816)),
        MccGroup("utilities", "Комунальні", listOf("комунал", "оренд", "житло", "квартир", "utilit", "rent"), setOf(4900, 6513)),
        MccGroup("travel", "Подорожі", listOf("подорож", "туризм", "готел", "авіа", "travel"),
            r(3000, 3350) + r(3351, 3441) + r(3501, 3999) + setOf(4411, 4511, 4722, 7011, 7012)),
        MccGroup("home", "Дім", listOf("дім", "дом", "ремонт", "господар", "home"),
            setOf(5200, 5211, 5231, 5251, 5261, 5712, 5713, 5714, 5718, 5719, 5722, 5950)),
        MccGroup("electronics", "Техніка", listOf("технік", "електрон", "гаджет", "electron"), setOf(5045, 5732, 5946)),
        MccGroup("beauty", "Краса", listOf("крас", "косметик", "догляд", "beauty"), setOf(5977, 7230, 7297, 7298)),
        MccGroup("education", "Освіта", listOf("освіт", "навчан", "книг", "курс", "educat"), setOf(5942, 5943, 8211, 8220, 8241, 8244, 8249, 8299)),
        MccGroup("pets", "Тварини", listOf("тварин", "зоо", "pet"), setOf(742, 5995)),
        MccGroup("gifts", "Подарунки", listOf("подарун", "квіт", "gift"), setOf(5992, 5947)),
        MccGroup("transfers", "Перекази", listOf("переказ", "transfer"), setOf(4829, 6012, 6536, 6537, 6538, 6540)),
        MccGroup("cash", "Готівка", listOf("готівк", "cash", "банкомат"), setOf(6010, 6011)),
    )

    fun group(mcc: Int?): MccGroup? = mcc?.let { c -> groups.firstOrNull { c in it.codes } }

    private val merchantHints = mapOf(
        "atb" to "groceries", "атб" to "groceries", "сільпо" to "groceries", "silpo" to "groceries",
        "новус" to "groceries", "novus" to "groceries", "фора" to "groceries", "ашан" to "groceries",
        "varus" to "groceries", "metro" to "groceries", "lidl" to "groceries", "biedronka" to "groceries",
        "uklon" to "taxi", "uber" to "taxi", "bolt" to "taxi",
        "netflix" to "digital", "spotify" to "digital", "youtube" to "digital", "apple.com" to "digital",
        "google" to "digital", "claude" to "digital", "anthropic" to "digital", "openai" to "digital",
        "discord" to "digital", "steam" to "fun", "render" to "digital",
        "glovo" to "cafe", "mcdonald" to "cafe", "kfc" to "cafe", "aroma" to "cafe", "starbucks" to "cafe",
        "okko" to "fuel", "wog" to "fuel", "socar" to "fuel", "upg" to "fuel",
        "rozetka" to "electronics", "comfy" to "electronics", "фокстрот" to "electronics",
        "аптека" to "health", "apteka" to "health",
        "kyivstar" to "mobile", "київстар" to "mobile", "vodafone" to "mobile", "lifecell" to "mobile",
    )

    fun groupForMerchant(name: String?): MccGroup? {
        val n = name?.lowercase() ?: return null
        val key = merchantHints.entries.firstOrNull { n.contains(it.key) }?.value ?: return null
        return groups.firstOrNull { it.key == key }
    }

    /** Picks the user's category whose name best fits the MCC group. */
    fun <T> matchCategory(group: MccGroup?, categories: List<T>, name: (T) -> String): T? {
        if (group == null) return null
        return categories.firstOrNull { c ->
            val n = name(c).lowercase()
            n == group.title.lowercase() || group.keywords.any { n.contains(it) }
        }
    }
}
