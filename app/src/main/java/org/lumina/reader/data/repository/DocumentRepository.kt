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
import java.io.FileOutputStream
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
            // 导出前先主动释放位图内存缓存，为 PDFBox 腾出足够的堆内存
            pdfEngine.clearMemoryCache()

            // 1. 读取原文档输入流，由 PdfDocumentExporter 执行纯 JVM 合成与物理写入
            val inputStream = resolver.openInputStream(uri)
                ?: throw FileNotFoundException("无法打开原始文件输入流: $uri")

            inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    PdfDocumentExporter.exportPdf(input, output, annotations, pageSpecs, context.cacheDir)
                }
            }

            // 2. 写入目标文件 (优先使用 ParcelFileDescriptor 并截断原有长度，防止脏数据残留损坏 PDF)
            val targetPfd = try {
                if (uri.scheme == "content") {
                    resolver.openFileDescriptor(uri, "wt") ?: resolver.openFileDescriptor(uri, "rw")
                } else null
            } catch (e: SecurityException) {
                throw SecurityException("该文件只读或无写权限，请使用【另存为】功能保存副本", e)
            } catch (_: Exception) {
                null
            }

            if (targetPfd != null) {
                targetPfd.use { pfdItem ->
                    FileOutputStream(pfdItem.fileDescriptor).use { fos ->
                        val channel = fos.channel
                        channel.position(0)
                        channel.truncate(0)
                        tempFile.inputStream().use { tempIn ->
                            tempIn.channel.transferTo(0, tempFile.length(), channel)
                        }
                        channel.force(true)
                    }
                }
            } else {
                val outputStream = try {
                    if (uri.scheme == "file") {
                        File(uri.path ?: "").outputStream()
                    } else {
                        resolver.openOutputStream(uri, "wt") ?: resolver.openOutputStream(uri, "w")
                    }
                } catch (e: SecurityException) {
                    throw SecurityException("该文件只读或无写权限，请使用【另存为】功能保存副本", e)
                } catch (e: Exception) {
                    try {
                        resolver.openOutputStream(uri, "w")
                    } catch (e2: SecurityException) {
                        throw SecurityException("该文件只读或无写权限，请使用【另存为】功能保存副本", e2)
                    } catch (e2: Exception) {
                        throw IOException("无法获取写入流: ${e2.message}", e2)
                    }
                } ?: throw IOException("无法获取写入流: $uri")

                outputStream.use { targetOut ->
                    tempFile.inputStream().use { tempIn ->
                        tempIn.copyTo(targetOut)
                        targetOut.flush()
                    }
                }
            }

            // 3. 打开新文件的 ParcelFileDescriptor，并热重载底层渲染引擎，杜绝 UI 闪退
            val pfd = resolver.openFileDescriptor(uri, "r")
                ?: throw FileNotFoundException("无法获取重新装载的文件描述符: $uri")

            val newInfo = pdfEngine.reload(pfd)
            val title = queryDocumentTitle(uri) ?: newInfo.title
            historyDb.recordProgress(uri.toString(), title, newInfo.pageCount, newInfo.initialPage)
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
    ): Unit = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val tempFile = File.createTempFile("lumina_export_", ".pdf", context.cacheDir)
        try {
            // 导出前先主动释放位图内存缓存，为 PDFBox 腾出足够的堆内存
            pdfEngine.clearMemoryCache()

            val inputStream = resolver.openInputStream(sourceUri)
                ?: throw FileNotFoundException("无法打开源文件输入流: $sourceUri")

            inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    PdfDocumentExporter.exportPdf(input, output, annotations, pageSpecs, context.cacheDir)
                }
            }

            val targetPfd = try {
                if (targetUri.scheme == "content") {
                    resolver.openFileDescriptor(targetUri, "wt") ?: resolver.openFileDescriptor(targetUri, "rw")
                } else null
            } catch (_: Exception) {
                null
            }

            if (targetPfd != null) {
                targetPfd.use { pfdItem ->
                    FileOutputStream(pfdItem.fileDescriptor).use { fos ->
                        val channel = fos.channel
                        channel.position(0)
                        channel.truncate(0)
                        tempFile.inputStream().use { tempIn ->
                            tempIn.channel.transferTo(0, tempFile.length(), channel)
                        }
                        channel.force(true)
                    }
                }
            } else {
                val targetOut = try {
                    if (targetUri.scheme == "file") {
                        File(targetUri.path ?: "").outputStream()
                    } else {
                        resolver.openOutputStream(targetUri, "wt") ?: resolver.openOutputStream(targetUri, "w")
                    }
                } catch (e: Exception) {
                    resolver.openOutputStream(targetUri, "w")
                } ?: throw IOException("无法创建目标输出流: $targetUri")

                targetOut.use { out ->
                    tempFile.inputStream().use { tempIn ->
                        tempIn.copyTo(out)
                        out.flush()
                    }
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
