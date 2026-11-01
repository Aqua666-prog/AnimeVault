package com.sergey.animevault.data.community

import android.content.Context
import androidx.room.*
import com.google.gson.Gson

@Entity(tableName = "community_cache")
data class CommunityCacheEntity(@PrimaryKey val cacheKey: String, val payload: String, val saved: Long)
@Entity(tableName = "community_drafts", primaryKeys = ["owner", "threadKey"])
data class CommunityDraftEntity(val owner: String, val threadKey: String, val payload: String, val saved: Long)
@Dao
interface CommunityCacheDao {
    @Query("SELECT * FROM community_cache WHERE cacheKey = :key") suspend fun page(key: String): CommunityCacheEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(page: CommunityCacheEntity)
    @Query("DELETE FROM community_cache WHERE saved < :cutoff OR cacheKey NOT IN (SELECT cacheKey FROM community_cache ORDER BY saved DESC LIMIT 120)") suspend fun prune(cutoff: Long)
    @Query("SELECT * FROM community_drafts WHERE owner = :owner AND threadKey = :key") suspend fun draft(owner: String, key: String): CommunityDraftEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDraft(draft: CommunityDraftEntity)
    @Query("DELETE FROM community_drafts WHERE owner = :owner") suspend fun clearDrafts(owner: String)
    @Query("DELETE FROM community_drafts WHERE saved < :cutoff OR rowid NOT IN (SELECT rowid FROM community_drafts ORDER BY saved DESC LIMIT 100)") suspend fun pruneDrafts(cutoff: Long)
    @Query("DELETE FROM community_cache") suspend fun clearPages()
}
@Database(entities = [CommunityCacheEntity::class, CommunityDraftEntity::class], version = 1, exportSchema = false)
abstract class CommunityCacheDatabase : RoomDatabase() {
    abstract fun cacheDao(): CommunityCacheDao
}

/** Disposable isolated Room cache; existing library DB schema and backup format stay intact. */
class CommunityCache(context: Context) {
    private val dao = Room.databaseBuilder(context, CommunityCacheDatabase::class.java,
        java.io.File(context.noBackupFilesDir, "community-cache.db").absolutePath).build().cacheDao()
    private val gson = Gson()
    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    suspend fun <T> get(key: String, type: Class<T>): T? = optional {
        val item = dao.page(key)
        if (item == null || item.saved < System.currentTimeMillis()-7*86_400_000L) null
        else gson.fromJson(item.payload, type)
    }
    suspend fun put(key: String, value: Any) {
        optional {
            dao.put(CommunityCacheEntity(key, gson.toJson(value), System.currentTimeMillis()))
            dao.prune(System.currentTimeMillis()-7*86_400_000L)
        }
    }
    suspend fun draft(owner: String, key: String): CommunityDraftDto = optional {
        dao.draft(owner,key)?.takeIf { it.saved >= System.currentTimeMillis()-30*86_400_000L }?.let {
            gson.fromJson(it.payload, CommunityDraftDto::class.java)
        }
    } ?: CommunityDraftDto()
    suspend fun saveDraft(owner: String, key: String, draft: CommunityDraftDto) {
        optional {
            dao.putDraft(CommunityDraftEntity(owner,key,gson.toJson(draft),System.currentTimeMillis()))
            dao.pruneDrafts(System.currentTimeMillis()-30*86_400_000L)
        }
    }
    suspend fun clearDrafts(owner: String) { optional { dao.clearDrafts(owner) } }
    suspend fun clearPages() { optional { dao.clearPages() } }
}
