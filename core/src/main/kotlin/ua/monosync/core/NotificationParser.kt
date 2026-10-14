package ua.monosync.core

enum class Direction { OUT, IN }

data class ParsedNotification(
    val amountMinor: Long,
    val currency: Currency?,
    val direction: Direction,
    val description: String?,
    val cardPhrase: String?,
    val cardKey: String?,
    val jarName: String?,
    val counterpartPhrase: String?,
    val balanceMinor: Long?,
    val rate: Double?,
    val notes: List<String>,
) {
    /** Amount in the card's own currency, when the operation went through a conversion. */
    fun accountAmountMinor(accountCurrency: Currency?): Long {
        if (rate == null || rate <= 0.0 || accountCurrency == null || currency == null) return amountMinor
        if (currency == accountCurrency) return amountMinor
        return if (accountCurrency == Currency.UAH) Money.multiply(amountMinor, rate) else Money.convert(amountMinor, rate)
    }
}

object NotificationParser {
    private const val NUM = "(\\d{1,3}(?:[ \\u00A0\\u202F\\u2009]\\d{3})+(?:[.,]\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?)"
    private const val CUR = "(₴|грн\\.?|UAH|€|EUR|\\$|USD|£|GBP|zł|PLN)"

    private val amountSuffix = Regex("([+\\-−–]?)\\s?$NUM\\s?$CUR", RegexOption.IGNORE_CASE)
    private val amountPrefix = Regex("([+\\-−–]?)\\s?$CUR\\s?$NUM", RegexOption.IGNORE_CASE)
    private val balance = Regex("(?:Баланс|Залишок|Доступно)\\s*:?\\s*([+\\-−–]?)\\s?$NUM\\s?$CUR?", RegexOption.IGNORE_CASE)
    private val rateRx = Regex("Курс\\s*:?\\s*(\\d+(?:[.,]\\d+)?)\\s*$CUR?", RegexOption.IGNORE_CASE)
    private val cardRx = Regex(
        "^(з|із|зі|на|до)\\s+(.+?)\\s+(картк[аиуі]|рахунк[уа]|рахунок)\\b.*$",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val jarRx = Regex("^(з|із|зі|на|до|у|в)\\s+банк[иуа]\\s*[«\"“]?(.+?)[»\"”]?\\s*$", RegexOption.IGNORE_CASE)
    private val fromRx = Regex("^(від|from)\\s*:?\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val toRx = Regex("^(кому|отримувач|to)\\s*:?\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val noteRx = Regex("^(кредитний ліміт|кешбек|cashback|комісія|ліміт|бонус)", RegexOption.IGNORE_CASE)
    private val incomeWords = Regex(
        "(поповнення|надходження|зарахування|повернення|відшкодування|зараховано|отримано|переказ від|від:|заробітна|зарплата)",
        RegexOption.IGNORE_CASE,
    )
    private val junk = Regex("[\\p{So}\\p{Sk}\\p{Cs}\\p{Co}\\uFE0F\\u200D\\u20E3]")

    fun parse(title: String?, text: String?): ParsedNotification? {
        val lines = listOfNotNull(title, text)
            .flatMap { it.split('\n') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (lines.isEmpty()) return null

        var amount: Long? = null
        var currency: Currency? = null
        var sign: String? = null
        var rate: Double? = null
        var balanceMinor: Long? = null
        var cardPhrase: String? = null
        var cardPreposition: String? = null
        var jar: String? = null
        var jarPreposition: String? = null
        var counterpart: String? = null
        var amountLineRest: String? = null
        var incomeHint = false
        val descCandidates = mutableListOf<String>()
        val notes = mutableListOf<String>()

        for (raw in lines) {
            if (incomeWords.containsMatchIn(raw)) incomeHint = true
            var line = raw

            balance.find(line)?.let { b ->
                balanceMinor = Money.parseMinor(b.groupValues[1] + b.groupValues[2])
                line = clean(line.replace(b.value, " "))
            }
            if (line.isBlank()) continue

            if (noteRx.containsMatchIn(line)) {
                notes += clean(line)
                continue
            }

            val jm = jarRx.find(line)
            if (jm != null) {
                jar = clean(jm.groupValues[2])
                jarPreposition = jm.groupValues[1].lowercase()
                continue
            }

            val cm = cardRx.find(line)
            if (cm != null) {
                cardPhrase = clean(cm.groupValues[2])
                cardPreposition = cm.groupValues[1].lowercase()
                continue
            }

            val fm = fromRx.find(line)
            if (fm != null) {
                counterpart = clean(fm.groupValues[2])
                incomeHint = true
                continue
            }
            val tm = toRx.find(line)
            if (tm != null) {
                counterpart = clean(tm.groupValues[2])
                continue
            }

            val rm = rateRx.find(line)
            if (rm != null) {
                rate = rm.groupValues[1].replace(',', '.').toDoubleOrNull()
                line = line.replace(rm.value, " ")
            }

            if (amount == null) {
                val m = amountSuffix.find(line)
                val p = if (m == null) amountPrefix.find(line) else null
                if (m != null) {
                    sign = m.groupValues[1]
                    amount = Money.parseMinor(m.groupValues[2])
                    currency = Currency.bySymbol(m.groupValues[3])
                    amountLineRest = clean(line.replaceFirst(m.value, " "))
                    continue
                }
                if (p != null) {
                    sign = p.groupValues[1]
                    currency = Currency.bySymbol(p.groupValues[2])
                    amount = Money.parseMinor(p.groupValues[3])
                    amountLineRest = clean(line.replaceFirst(p.value, " "))
                    continue
                }
            }

            val c = clean(line)
            if (c.isNotEmpty()) descCandidates += c
        }

        val amt = amount ?: return null
        if (amt == 0L) return null

        val direction = when {
            sign == "+" -> Direction.IN
            sign == "-" || sign == "−" || sign == "–" -> Direction.OUT
            cardPreposition == "на" || cardPreposition == "до" -> Direction.IN
            cardPreposition != null -> Direction.OUT
            jarPreposition == "з" || jarPreposition == "із" || jarPreposition == "зі" -> Direction.IN
            jarPreposition != null -> Direction.OUT
            incomeHint -> Direction.IN
            else -> Direction.OUT
        }

        val description = amountLineRest?.takeIf { it.length > 1 }
            ?: descCandidates.firstOrNull { it.length > 1 }
            ?: counterpart
            ?: jar?.let { if (direction == Direction.OUT) "Банка «$it»" else "З банки «$it»" }

        return ParsedNotification(
            amountMinor = amt,
            currency = currency,
            direction = direction,
            description = description,
            cardPhrase = cardPhrase,
            cardKey = cardPhrase?.let { CardKeys.normalize(it) },
            jarName = jar,
            counterpartPhrase = counterpart,
            balanceMinor = balanceMinor,
            rate = rate,
            notes = notes,
        )
    }

    fun clean(s: String): String =
        s.replace(junk, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim(':', '-', '–', '—', '·', ',', '.', '|', '•')
            .trim()
}
