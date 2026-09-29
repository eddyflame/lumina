package org.lumina.reader.data.repository

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.StateFlow
import org.lumina.reader.core.engine.AndroidPdfRendererEngine
import org.lumina.reader.core.engine.PdfEngine
import org.lumina.reader.core.model.PdfDocumentInfo
import org.lumina.reader.data.db.HistoryDatabase
import org.lumina.reader.data.db.RecentDocument
import java.io.FileNotFoundException

/**
 * 现代 SAF (Storage Access Framework) 与本地持久化书架仓库
 */
class DocumentRepository(
    private val context: Context,
    val pdfEngine: PdfEngine = AndroidPdfRendererEngine()
) {

    private val historyDb = HistoryDatabase(context)

    /** 响应式书架流 */
    val recentDocuments: StateFlow<List<RecentDocument>> = historyDb.recentDocsFlow

    suspend fun openDocument(uri: Uri): PdfDocumentInfo {
        val resolver = context.contentResolver
        val title = queryDocumentTitle(uri) ?: "Document.pdf"

        val pfd: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw FileNotFoundException("无法通过 ContentResolver 获取文件描述符: $uri")

        // 检查该文件是否有上次阅读进度记忆
        val history = historyDb.getDocument(uri.toString())
        val initialPage = history?.lastReadPage ?: 0

        val info = pdfEngine.open(uri, title, pfd, initialPage)

        // 记录并刷新历史
        historyDb.recordProgress(uri.toString(), title, info.pageCount, initialPage)

        return info
    }

    fun saveProgress(uri: Uri, title: String, pageCount: Int, pageIndex: Int) {
        historyDb.recordProgress(uri.toString(), title, pageCount, pageIndex)
    }

    fun deleteRecentDocument(uri: String) {
        historyDb.deleteDocument(uri)
    }

    fun togglePinRecentDocument(uri: String, isPinned: Boolean) {
        historyDb.togglePin(uri, isPinned)
    }

    fun clearAllHistory() {
        historyDb.clearAll()
    }

    private fun queryDocumentTitle(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            val name = cursor.getString(nameIndex)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return uri.lastPathSegment
    }
}
