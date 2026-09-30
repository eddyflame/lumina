package org.lumina.reader.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 极简、零依赖、高性能本地阅读历史持久化数据库
 *
 * 采用原生 SQLiteOpenHelper，避免 KSP 编译插件版本兼容冲突，配合 StateFlow 实现响应式书架
 */
class HistoryDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val _recentDocsFlow = MutableStateFlow<List<RecentDocument>>(emptyList())
    val recentDocsFlow: StateFlow<List<RecentDocument>> = _recentDocsFlow.asStateFlow()

    init {
        refreshFlow()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_HISTORY (
                $COL_URI TEXT PRIMARY KEY,
                $COL_TITLE TEXT NOT NULL,
                $COL_PAGE_COUNT INTEGER NOT NULL,
                $COL_LAST_PAGE INTEGER NOT NULL,
                $COL_PROGRESS REAL NOT NULL,
                $COL_TIME INTEGER NOT NULL,
                $COL_PINNED INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 简单平滑升级策略
    }

    /**
     * 更新或新增阅读历史
     */
    fun recordProgress(uri: String, title: String, pageCount: Int, pageIndex: Int) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val db = writableDatabase
                val progress = if (pageCount > 0) (pageIndex + 1).toFloat() / pageCount.toFloat() else 0f
                // 查询现有记录以保留置顶标记 (is_pinned)，避免 CONFLICT_REPLACE 导致置顶状态被重置为 0
                val existingPinned = getDocument(uri)?.isPinned ?: false
                val values = ContentValues().apply {
                    put(COL_URI, uri)
                    put(COL_TITLE, title)
                    put(COL_PAGE_COUNT, pageCount)
                    put(COL_LAST_PAGE, pageIndex)
                    put(COL_PROGRESS, progress.coerceIn(0f, 1f))
                    put(COL_TIME, System.currentTimeMillis())
                    put(COL_PINNED, if (existingPinned) 1 else 0)
                }
                db.insertWithOnConflict(TABLE_HISTORY, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            refreshFlow()
        }
    }

    /**
     * 切换置顶状态
     */
    fun togglePin(uri: String, isPinned: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val db = writableDatabase
                val values = ContentValues().apply {
                    put(COL_PINNED, if (isPinned) 1 else 0)
                }
                db.update(TABLE_HISTORY, values, "$COL_URI = ?", arrayOf(uri))
            }
            refreshFlow()
        }
    }

    /**
     * 从历史中移除
     */
    fun deleteDocument(uri: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val db = writableDatabase
                db.delete(TABLE_HISTORY, "$COL_URI = ?", arrayOf(uri))
            }
            refreshFlow()
        }
    }

    /**
     * 清空历史
     */
    fun clearAll() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val db = writableDatabase
                db.delete(TABLE_HISTORY, null, null)
            }
            refreshFlow()
        }
    }

    /**
     * 查询单个文档阅读记录
     */
    suspend fun getDocument(uri: String): RecentDocument? = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_HISTORY,
            null,
            "$COL_URI = ?",
            arrayOf(uri),
            null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) {
                parseRow(it)
            } else {
                null
            }
        }
    }

    private fun refreshFlow() {
        scope.launch {
            val list = queryAll()
            _recentDocsFlow.value = list
        }
    }

    private suspend fun queryAll(): List<RecentDocument> = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val list = mutableListOf<RecentDocument>()
        val cursor = db.query(
            TABLE_HISTORY,
            null,
            null,
            null,
            null,
            null,
            "$COL_PINNED DESC, $COL_TIME DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(parseRow(it))
            }
        }
        list
    }

    private fun parseRow(cursor: android.database.Cursor): RecentDocument {
        val uri = cursor.getString(cursor.getColumnIndexOrThrow(COL_URI))
        val title = cursor.getString(cursor.getColumnIndexOrThrow(COL_TITLE))
        val pageCount = cursor.getInt(cursor.getColumnIndexOrThrow(COL_PAGE_COUNT))
        val lastPage = cursor.getInt(cursor.getColumnIndexOrThrow(COL_LAST_PAGE))
        val progress = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_PROGRESS))
        val time = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIME))
        val pinned = cursor.getInt(cursor.getColumnIndexOrThrow(COL_PINNED)) == 1

        return RecentDocument(
            uriString = uri,
            title = title,
            pageCount = pageCount,
            lastReadPage = lastPage,
            progressPercent = progress,
            lastReadTime = time,
            isPinned = pinned
        )
    }

    companion object {
        private const val DB_NAME = "lumina_reader.db"
        private const val DB_VERSION = 1

        private const val TABLE_HISTORY = "reading_history"
        private const val COL_URI = "uri"
        private const val COL_TITLE = "title"
        private const val COL_PAGE_COUNT = "page_count"
        private const val COL_LAST_PAGE = "last_page"
        private const val COL_PROGRESS = "progress"
        private const val COL_TIME = "last_read_time"
        private const val COL_PINNED = "is_pinned"
    }
}
