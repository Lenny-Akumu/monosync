package ua.monosync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationParserTest {

    @Test
    fun whiteCardTransferWithCreditLimit() {
        val p = NotificationParser.parse(
            "👉💳 10.00₴",
            "З Білої картки\nБаланс -1 004.46₴ 🥷\nКредитний ліміт зменшено на 10₴ до 5004.46₴",
        )
        assertNotNull(p)
        p!!
        assertEquals(1000L, p.amountMinor)
        assertEquals(Currency.UAH, p.currency)
        assertEquals(Direction.OUT, p.direction)
        assertEquals("Білої", p.cardPhrase)
        assertEquals("біл", p.cardKey)
        assertEquals(-100446L, p.balanceMinor)
        assertNull(p.description)
        assertEquals(1, p.notes.size)
    }

    @Test
    fun euroCardWithRate() {
        val p = NotificationParser.parse("👉💳 49.90₴  Курс 49.9₴", "З єврової картки\nБаланс 70.79₴ 🐇")!!
        assertEquals(4990L, p.amountMinor)
        assertEquals(49.9, p.rate!!, 0.0001)
        assertEquals("євров", p.cardKey)
        assertEquals(7079L, p.balanceMinor)
        assertEquals(100L, p.accountAmountMinor(Currency.EUR))
        assertEquals(Currency.EUR, CardKeys.guessCurrency(p.cardKey!!))
        assertNull(p.description)
    }

    @Test
    fun merchantPurchase() {
        val p = NotificationParser.parse("🛒 АТБ-Маркет", "−412.80₴\nЗ Чорної картки\nБаланс 24 318.40₴")!!
        assertEquals(41280L, p.amountMinor)
        assertEquals(Direction.OUT, p.direction)
        assertEquals("АТБ-Маркет", p.description)
        assertEquals("чорн", p.cardKey)
        assertEquals(2431840L, p.balanceMinor)
    }

    @Test
    fun incomingTransfer() {
        val p = NotificationParser.parse("💰 +1 500.00₴", "На Чорну картку\nВід: Олена Коваль\nБаланс 25 818.40₴")!!
        assertEquals(150000L, p.amountMinor)
        assertEquals(Direction.IN, p.direction)
        assertEquals("чорн", p.cardKey)
        assertEquals("Олена Коваль", p.description)
    }

    @Test
    fun jarTopUp() {
        val p = NotificationParser.parse("🫙 500.00₴", "На банку «Відпустка»")!!
        assertEquals(Direction.OUT, p.direction)
        assertEquals("Відпустка", p.jarName)
        assertEquals("Банка «Відпустка»", p.description)
    }

    @Test
    fun singleLineWithMerchantAfterAmount() {
        val p = NotificationParser.parse("monobank", "Uklon 186.00₴, Баланс 12 000.00₴")!!
        assertEquals(18600L, p.amountMinor)
        assertEquals(1200000L, p.balanceMinor)
        assertEquals("Uklon", p.description)
    }

    @Test
    fun promoIsIgnored() {
        assertNull(NotificationParser.parse("Новий кешбек", "Обирайте категорії на жовтень"))
    }

    @Test
    fun keysMatchApiTypes() {
        assertEquals(CardKeys.normalize("Білої"), CardKeys.keyForApi("white", Currency.UAH))
        assertEquals(CardKeys.normalize("Чорну"), CardKeys.keyForApi("black", Currency.UAH))
        assertEquals(CardKeys.normalize("єврової"), CardKeys.keyForApi("black", Currency.EUR))
        assertEquals("Біла картка", CardKeys.displayName("Білої"))
    }

    @Test
    fun money() {
        assertEquals(100446L, Money.parseMinor("1 004.46"))
        assertEquals(100446L, Money.parseMinor("1 004,46"))
        assertEquals("−1 004,46 ₴", Money.format(-100446, Currency.UAH))
        assertEquals("groceries", Mcc.group(5411)?.key)
    }
}
