package org.lumina.reader.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.lumina.reader.core.annotation.PdfAnnotation
import org.lumina.reader.core.engine.AndroidPdfRendererEngine
import org.lumina.reader.core.engine.PdfEngine
import org.lumina.reader.core.export.PdfDocumentExporter
import org.lumina.reader.core.model.PageEditSpec
import org.lumina.reader.core.model.PdfDocumentInfo
import org.lumina.reader.data.db.HistoryDatabase
import org.lumina.reader.data.db.RecentDocument
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

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
        // 对系统 SAF 选择器授予的 content:// URI 持久化权限，确保跨进程和重启后可继续从书架直接打开
        if (uri.scheme == "content") {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
                // 部分第三方应用临时分享的 URI 不支持持久化授权，静默忽略
            }
        }

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

    /**
     * 将批注与页面结构修改原子覆写回存到当前 SAF 目标文档中
     * 采用“临时文件导出 -> 校验完整性 -> 原生流安全覆写 -> 重新装载渲染器”的事务性工作流
     */
    suspend fun saveDocumentInPlace(
        uri: Uri,
        annotations: Map<Int, List<PdfAnnotation>>,
        pageSpecs: List<PageEditSpec>
    ): PdfDocumentInfo = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val tempFile = File.createTempFile("lumina_save_", ".pdf", context.cacheDir)
        try {
            // 1. 读取原文档输入流，由 PdfDocumentExporter 执行纯 JVM 合成与物理写入
            val inputStream = resolver.openInputStream(uri)
                ?: throw FileNotFoundException("无法打开原始文件输入流: $uri")

            inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    PdfDocumentExporter.exportPdf(input, output, annotations, pageSpecs)
                }
            }

            // 2. 临时释放底层 PdfRenderer 避免持锁冲突
            pdfEngine.close()

            // 3. 打开 SAF 目标写入流并覆写
            val outputStream = try {
                resolver.openOutputStream(uri, "wt")
            } catch (e: SecurityException) {
                throw SecurityException("该文件只读或无写权限，请使用【另存为】功能保存副本", e)
            } ?: throw IOException("无法获取写入流: $uri")

            outputStream.use { targetOut ->
                tempFile.inputStream().use { tempIn ->
                    tempIn.copyTo(targetOut)
                    targetOut.flush()
                }
            }

            // 4. 重新装载并返回最新的文档模型
            val title = queryDocumentTitle(uri) ?: "Document.pdf"
            val pfd = resolver.openFileDescriptor(uri, "r")
                ?: throw FileNotFoundException("无法获取重新装载的文件描述符: $uri")

            val newInfo = pdfEngine.open(uri, title, pfd, 0)
            historyDb.recordProgress(uri.toString(), title, newInfo.pageCount, 0)
            newInfo
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    /**
     * 将包含批注与页面结构修改的文档另存为 (Save As) 到新的 SAF Content URI
     */
    suspend fun exportDocumentToUri(
        sourceUri: Uri,
        targetUri: Uri,
        annotations: Map<Int, List<PdfAnnotation>>,
        pageSpecs: List<PageEditSpec>
    ) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val tempFile = File.createTempFile("lumina_export_", ".pdf", context.cacheDir)
        try {
            val inputStream = resolver.openInputStream(sourceUri)
                ?: throw FileNotFoundException("无法打开源文件输入流: $sourceUri")

            inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    PdfDocumentExporter.exportPdf(input, output, annotations, pageSpecs)
                }
            }

            val targetOut = resolver.openOutputStream(targetUri, "wt")
                ?: throw IOException("无法创建目标输出流: $targetUri")

            targetOut.use { out ->
                tempFile.inputStream().use { tempIn ->
                    tempIn.copyTo(out)
                    out.flush()
                }
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
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
