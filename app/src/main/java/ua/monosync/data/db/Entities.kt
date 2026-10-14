package ua.monosync.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ua.monosync.core.Currency

object Kind {
    const val EXPENSE = "EXPENSE"
    const val INCOME = "INCOME"
    const val TRANSFER = "TRANSFER"
}

object Source {
    const val NOTIFICATION = "NOTIFICATION"
    const val API = "API"
    const val MANUAL = "MANUAL"
}

object SyncState {
    const val PENDING = "PENDING"
    const val SYNCED = "SYNCED"
    const val ERROR = "ERROR"
    const val SKIPPED = "SKIPPED"
}

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val cardKey: String,
    val type: String? = null,
    val currency: Int = Currency.UAH.iso,
    val maskedPan: String? = null,
    val iban: String? = null,
    val balance: Long? = null,
    val creditLimit: Long = 0,
    val confirmed: Boolean = false,
    val hidden: Boolean = false,
    val fromApi: Boolean = false,
    val notionAccountId: String? = null,
    val lastStatementAt: Long = 0,
    val sortOrder: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "transactions",
    indices = [Index(value = ["monoId"], unique = true), Index("accountId"), Index("time")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val monoId: String? = null,
    val accountId: String,
    val time: Long,
    val amount: Long,
    val currency: Int,
    val operationAmount: Long? = null,
    val operationCurrency: Int? = null,
    val description: String,
    val genericDescription: Boolean = false,
    val mcc: Int? = null,
    val categoryId: String? = null,
    val kind: String,
    val transferAccountId: String? = null,
    val balanceAfter: Long? = null,
    val comment: String? = null,
    val source: String,
    val rawText: String? = null,
    val notionPageId: String? = null,
    val notionDb: String? = null,
    val syncState: String = SyncState.PENDING,
    val syncError: String? = null,
    val userEdited: Boolean = false,
    val deleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val budget: Long? = null,
    val groupKey: String? = null,
    val fromNotion: Boolean = false,
    val sortOrder: Int = 0,
)

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey val pattern: String,
    val categoryId: String,
)

@Entity(tableName = "raw_notifications", indices = [Index(value = ["hash"], unique = true)])
data class RawNotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hash: String,
    val postedAt: Long,
    val title: String?,
    val text: String?,
    val parsed: Boolean,
    val transactionId: Long? = null,
)

val AccountEntity.cur: Currency get() = Currency.byIso(currency) ?: Currency.UAH
val TransactionEntity.cur: Currency get() = Currency.byIso(currency) ?: Currency.UAH
val AccountEntity.available: Long? get() = balance?.let { it + creditLimit }
