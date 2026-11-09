package com.glassbox.app.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.glassbox.app.data.MediaItem
import com.glassbox.app.data.WatchRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 历史 / 收藏 —— 对齐 TVBox VodRecord / VodCollect，使用 SQLite 持久化。
 * 对外暴露与 AppStore 兼容的数据模型，便于 LibraryScreen 无感切换。
 */
interface LibraryRepository {
    suspend fun listHistory(limit: Int = 100): List<WatchRecord>
    suspend fun upsertHistory(item: WatchRecord)
    suspend fun removeHistory(key: String)
    suspend fun clearHistory()

    suspend fun listFavorites(): List<MediaItem>
    suspend fun upsertFavorite(item: MediaItem)
    suspend fun removeFavorite(globalKey: String)
    suspend fun isFavorite(globalKey: String): Boolean
    suspend fun toggleFavorite(item: MediaItem): Boolean
}

class SqliteLibraryRepository(context: Context) : LibraryRepository {

    private val dbHelper = LibraryDbHelper(context.applicationContext)

    override suspend fun listHistory(limit: Int): List<WatchRecord> = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.rawQuery(
            "SELECT * FROM vod_history ORDER BY updated_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            val list = mutableListOf<WatchRecord>()
            while (c.moveToNext()) {
                list += WatchRecord(
                    key = c.getString(c.getColumnIndexOrThrow("key")),
                    title = c.getString(c.getColumnIndexOrThrow("title")),
                    poster = c.getString(c.getColumnIndexOrThrow("poster")) ?: "",
                    siteKey = c.getString(c.getColumnIndexOrThrow("site_key")) ?: "",
                    siteName = c.getString(c.getColumnIndexOrThrow("site_name")) ?: "",
                    episodeIndex = c.getInt(c.getColumnIndexOrThrow("episode_index")),
                    episodeName = c.getString(c.getColumnIndexOrThrow("episode_name")) ?: "",
                    positionMs = c.getLong(c.getColumnIndexOrThrow("position_ms")),
                    durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms")),
                    updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
                )
            }
            list
        }
    }

    override suspend fun upsertHistory(item: WatchRecord) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("key", item.key)
            put("title", item.title)
            put("poster", item.poster)
            put("site_key", item.siteKey)
            put("site_name", item.siteName)
            put("episode_index", item.episodeIndex)
            put("episode_name", item.episodeName)
            put("position_ms", item.positionMs)
            put("duration_ms", item.durationMs)
            put("updated_at", if (item.updatedAt > 0) item.updatedAt else System.currentTimeMillis())
        }
        dbHelper.writableDatabase.insertWithOnConflict(
            "vod_history", null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
        // 保留最近 200 条
        dbHelper.writableDatabase.execSQL(
            "DELETE FROM vod_history WHERE key NOT IN (SELECT key FROM vod_history ORDER BY updated_at DESC LIMIT 200)"
        )
    }

    override suspend fun removeHistory(key: String) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete("vod_history", "key=?", arrayOf(key))
        Unit
    }

    override suspend fun clearHistory() = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete("vod_history", null, null)
        Unit
    }

    override suspend fun listFavorites(): List<MediaItem> = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.rawQuery(
            "SELECT * FROM vod_favorite ORDER BY updated_at DESC",
            null
        ).use { c ->
            val list = mutableListOf<MediaItem>()
            while (c.moveToNext()) {
                list += MediaItem(
                    id = c.getString(c.getColumnIndexOrThrow("id")),
                    title = c.getString(c.getColumnIndexOrThrow("title")),
                    poster = c.getString(c.getColumnIndexOrThrow("poster")) ?: "",
                    remarks = c.getString(c.getColumnIndexOrThrow("remarks")) ?: "",
                    siteKey = c.getString(c.getColumnIndexOrThrow("site_key")) ?: "",
                    siteName = c.getString(c.getColumnIndexOrThrow("site_name")) ?: "",
                    sourceId = c.getString(c.getColumnIndexOrThrow("source_id")) ?: ""
                )
            }
            list
        }
    }

    override suspend fun upsertFavorite(item: MediaItem) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("global_key", item.globalKey)
            put("id", item.id)
            put("title", item.title)
            put("poster", item.poster)
            put("remarks", item.remarks)
            put("site_key", item.siteKey)
            put("site_name", item.siteName)
            put("source_id", item.sourceId)
            put("updated_at", System.currentTimeMillis())
        }
        dbHelper.writableDatabase.insertWithOnConflict(
            "vod_favorite", null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
        Unit
    }

    override suspend fun removeFavorite(globalKey: String) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete("vod_favorite", "global_key=?", arrayOf(globalKey))
        Unit
    }

    override suspend fun isFavorite(globalKey: String): Boolean = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.rawQuery(
            "SELECT 1 FROM vod_favorite WHERE global_key=? LIMIT 1",
            arrayOf(globalKey)
        ).use { it.moveToFirst() }
    }

    override suspend fun toggleFavorite(item: MediaItem): Boolean {
        return if (isFavorite(item.globalKey)) {
            removeFavorite(item.globalKey)
            false
        } else {
            upsertFavorite(item)
            true
        }
    }

    private class LibraryDbHelper(context: Context) :
        SQLiteOpenHelper(context, "glass_library.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS vod_history (
                    key TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    poster TEXT,
                    site_key TEXT,
                    site_name TEXT,
                    episode_index INTEGER NOT NULL DEFAULT 0,
                    episode_name TEXT,
                    position_ms INTEGER NOT NULL DEFAULT 0,
                    duration_ms INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS vod_favorite (
                    global_key TEXT PRIMARY KEY NOT NULL,
                    id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    poster TEXT,
                    remarks TEXT,
                    site_key TEXT,
                    site_name TEXT,
                    source_id TEXT,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }
}

/** 进程内兜底（测试 / 无 Context 时） */
class InMemoryLibraryRepository : LibraryRepository {
    private val history = LinkedHashMap<String, WatchRecord>()
    private val favorites = LinkedHashMap<String, MediaItem>()

    override suspend fun listHistory(limit: Int) =
        history.values.sortedByDescending { it.updatedAt }.take(limit)

    override suspend fun upsertHistory(item: WatchRecord) {
        history[item.key] = item
    }

    override suspend fun removeHistory(key: String) {
        history.remove(key)
    }

    override suspend fun clearHistory() = history.clear()

    override suspend fun listFavorites() =
        favorites.values.toList()

    override suspend fun upsertFavorite(item: MediaItem) {
        favorites[item.globalKey] = item
    }

    override suspend fun removeFavorite(globalKey: String) {
        favorites.remove(globalKey)
    }

    override suspend fun isFavorite(globalKey: String) = favorites.containsKey(globalKey)

    override suspend fun toggleFavorite(item: MediaItem): Boolean {
        return if (favorites.containsKey(item.globalKey)) {
            favorites.remove(item.globalKey)
            false
        } else {
            favorites[item.globalKey] = item
            true
        }
    }
}
