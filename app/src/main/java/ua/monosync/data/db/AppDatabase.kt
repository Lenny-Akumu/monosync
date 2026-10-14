package ua.monosync.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY confirmed DESC, sortOrder, name")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts")
    suspend fun all(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE cardKey = :key")
    suspend fun byKey(key: String): List<AccountEntity>

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE deleted = 0 ORDER BY time DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE deleted = 0 AND time >= :from AND time < :to ORDER BY time DESC")
    fun observeRange(from: Long, to: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun byId(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observe(id: Long): Flow<TransactionEntity?>

    @Query("SELECT * FROM transactions WHERE monoId = :monoId")
    suspend fun byMonoId(monoId: String): TransactionEntity?

    @Query(
        "SELECT * FROM transactions WHERE monoId IS NULL AND accountId = :accountId " +
            "AND time BETWEEN :from AND :to"
    )
    suspend fun unmatched(accountId: String, from: Long, to: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE deleted = 0 AND kind = 'EXPENSE' AND time >= :from")
    suspend fun expensesSince(from: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE deleted = 0 AND time BETWEEN :from AND :to AND kind != 'TRANSFER'")
    suspend fun around(from: Long, to: Long): List<TransactionEntity>

    @Query(
        "SELECT * FROM transactions WHERE deleted = 0 AND kind = 'TRANSFER' AND transferAccountId = :accountId " +
            "AND time BETWEEN :from AND :to"
    )
    suspend fun transfersTouching(accountId: String, from: Long, to: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE syncState IN ('PENDING','ERROR') ORDER BY time")
    suspend fun pendingSync(): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE syncState IN ('PENDING','ERROR') AND deleted = 0")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM transactions WHERE deleted = 0 AND categoryId IS NULL AND kind = 'EXPENSE' AND lower(description) = :desc")
    suspend fun uncategorizedByDescription(desc: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE categoryId LIKE 'local:%'")
    suspend fun withLocalCategory(): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tx: TransactionEntity): Long

    @Update
    suspend fun update(tx: TransactionEntity)

    @Query("UPDATE transactions SET accountId = :to WHERE accountId = :from")
    suspend fun moveAccount(from: String, to: String)

    @Query("UPDATE transactions SET transferAccountId = :to WHERE transferAccountId = :from")
    suspend fun moveTransferAccount(from: String, to: String)

    @Query("UPDATE transactions SET syncState = 'PENDING' WHERE accountId = :accountId AND syncState = 'SKIPPED' AND deleted = 0 AND time >= :since")
    suspend fun releaseForAccount(accountId: String, since: Long)

    @Query(
        "UPDATE transactions SET categoryId = :to, " +
            "syncState = CASE WHEN notionPageId IS NOT NULL THEN 'PENDING' ELSE syncState END WHERE categoryId = :from"
    )
    suspend fun remapCategory(from: String, to: String?)

    @Query("UPDATE transactions SET syncState = 'PENDING', syncError = NULL WHERE syncState = 'ERROR'")
    suspend fun retryErrors()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY fromNotion DESC, sortOrder, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY fromNotion DESC, sortOrder, name")
    suspend fun all(): List<CategoryEntity>

    @Upsert
    suspend fun upsert(items: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE id LIKE 'local:%'")
    suspend fun deleteBuiltIn()

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules")
    suspend fun all(): List<RuleEntity>

    @Query("SELECT * FROM rules")
    fun observeAll(): Flow<List<RuleEntity>>

    @Upsert
    suspend fun upsert(rule: RuleEntity)

    @Query("DELETE FROM rules WHERE pattern = :pattern")
    suspend fun delete(pattern: String)

    @Query("UPDATE rules SET categoryId = :to WHERE categoryId = :from")
    suspend fun remap(from: String, to: String)

    @Query("DELETE FROM rules WHERE categoryId = :id")
    suspend fun deleteByCategory(id: String)
}

@Dao
interface RawDao {
    @Query("SELECT * FROM raw_notifications ORDER BY postedAt DESC LIMIT 300")
    fun observe(): Flow<List<RawNotificationEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(raw: RawNotificationEntity): Long

    @Query("UPDATE raw_notifications SET parsed = :parsed, transactionId = :txId WHERE id = :id")
    suspend fun mark(id: Long, parsed: Boolean, txId: Long?)

    @Query("DELETE FROM raw_notifications")
    suspend fun clear()
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions WHERE dismissed = 0 ORDER BY nextCharge")
    fun observeActive(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions")
    suspend fun all(): List<SubscriptionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(s: SubscriptionEntity): Long

    @Update
    suspend fun update(s: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [
        AccountEntity::class, TransactionEntity::class, CategoryEntity::class, RuleEntity::class,
        RawNotificationEntity::class, SubscriptionEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun transactions(): TransactionDao
    abstract fun categories(): CategoryDao
    abstract fun rules(): RuleDao
    abstract fun raw(): RawDao
    abstract fun subscriptions(): SubscriptionDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "monosync.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE accounts ADD COLUMN goal INTEGER")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `subscriptions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`key` TEXT NOT NULL, `name` TEXT NOT NULL, `amount` INTEGER NOT NULL, `currency` INTEGER NOT NULL, " +
                "`accountId` TEXT, `period` TEXT NOT NULL, `lastCharge` INTEGER NOT NULL, `nextCharge` INTEGER NOT NULL, " +
                "`remind` INTEGER NOT NULL, `dismissed` INTEGER NOT NULL, `manual` INTEGER NOT NULL, " +
                "`userEdited` INTEGER NOT NULL, `notifiedFor` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_subscriptions_key` ON `subscriptions` (`key`)")
    }
}
