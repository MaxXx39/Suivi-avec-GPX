package app.capmystere.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Voyage de l'hôte (document JSON complet + colonnes de tri). */
@Entity(tableName = "trips")
data class TripEntity(@PrimaryKey val id: String, val code: String, val title: String, val updatedAt: Long, val json: String)

/** Cache clé/valeur : dernier instantané voyageur, tronçons calculés, état des sessions locales. */
@Entity(tableName = "cache")
data class CacheEntity(@PrimaryKey val key: String, val json: String, val updatedAt: Long)

/** Actions du voyageur en attente de synchronisation (validation d'étape, réponse, positions). */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val kind: String,
    val payload: String,
    val createdAt: Long,
    val attempts: Int = 0,
)

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY updatedAt DESC") fun observeAll(): Flow<List<TripEntity>>
    @Query("SELECT * FROM trips WHERE id = :id") suspend fun get(id: String): TripEntity?
    @Query("SELECT * FROM trips WHERE code = :code LIMIT 1") suspend fun byCode(code: String): TripEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: TripEntity)
    @Query("DELETE FROM trips WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache WHERE `key` = :key") suspend fun get(key: String): CacheEntity?
    @Query("SELECT * FROM cache WHERE `key` = :key") fun observe(key: String): Flow<CacheEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(e: CacheEntity)
    @Query("DELETE FROM cache WHERE `key` = :key") suspend fun delete(key: String)
    @Query("DELETE FROM cache WHERE `key` LIKE :prefix || '%'") suspend fun deletePrefix(prefix: String)
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY id ASC") suspend fun all(): List<OutboxEntity>
    @Query("SELECT COUNT(*) FROM outbox WHERE kind != 'positions'") fun observePendingActions(): Flow<Int>
    @Insert suspend fun add(e: OutboxEntity): Long
    @Query("DELETE FROM outbox WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE id = :id") suspend fun bump(id: Long)
    @Query("DELETE FROM outbox WHERE sessionId = :sessionId") suspend fun clear(sessionId: String)
}

@Database(entities = [TripEntity::class, CacheEntity::class, OutboxEntity::class], version = 1, exportSchema = false)
abstract class CapDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun cache(): CacheDao
    abstract fun outbox(): OutboxDao
}
