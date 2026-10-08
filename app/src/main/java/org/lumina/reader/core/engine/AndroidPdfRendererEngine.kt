package org.lumina.reader.core.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.lumina.reader.core.cache.BitmapLruCache
import org.lumina.reader.core.crop.PageCropper2
import org.lumina.reader.core.model.PageInfo
import org.lumina.reader.core.model.PdfDocumentInfo
import org.lumina.reader.core.model.PdfOutlineItem
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

/**
 * 基于 Android 官方现代化 PdfRenderer 的纯原生引擎实现
 */
class AndroidPdfRendererEngine(
    private val bitmapCache: BitmapLruCache = BitmapLruCache()
) : PdfEngine {

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var docInfo: PdfDocumentInfo? = null

    private val renderLock = ReentrantLock()
    @Volatile
    private var isClosed = false

    private val pageInfoCache = mutableMapOf<Int, PageInfo>()
    private val cropBoundsCache = mutableMapOf<Int, PageCropper2.CropBounds>()

    override suspend fun open(
        uri: Uri,
        title: String,
        pfd: ParcelFileDescriptor,
        initialPage: Int
    ): PdfDocumentInfo = withContext(Dispatchers.IO) {
        renderLock.withLock {
            closeInternal()
            isClosed = false
            this@AndroidPdfRendererEngine.pfd = pfd
            try {
                val newRenderer = PdfRenderer(pfd)
                this@AndroidPdfRendererEngine.renderer = newRenderer
                val pageCount = newRenderer.pageCount
                val safeInitialPage = initialPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                val info = PdfDocumentInfo(
                    uri = uri,
                    title = title,
                    pageCount = pageCount,
                    fileSize = pfd.statSize,
                    initialPage = safeInitialPage
                )
                this@AndroidPdfRendererEngine.docInfo = info
                info
            } catch (e: Exception) {
                closeInternal()
                throw IOException("无法使用系统 PdfRenderer 打开文档: ${e.message}", e)
            }
        }
    }

    override suspend fun getPageInfo(pageIndex: Int): PageInfo = withContext(Dispatchers.IO) {
        pageInfoCache[pageIndex]?.let { return@withContext it }

        renderLock.withLock {
            if (isClosed) throw IllegalStateException("PDF 引擎已关闭")
            val r = renderer ?: throw IllegalStateException("PDF 引擎尚未初始化")
            if (pageIndex < 0 || pageIndex >= r.pageCount) {
                throw IndexOutOfBoundsException("页码超出范围: $pageIndex, 总页数: ${r.pageCount}")
            }

            val page = r.openPage(pageIndex)
            val info = PageInfo(
                index = pageIndex,
                width = page.width,
                height = page.height,
                cropBounds = cropBoundsCache[pageIndex] ?: PageCropper2.CropBounds.FULL
            )
            page.close()
            pageInfoCache[pageIndex] = info
            info
        }
    }

    override suspend fun renderPage(
        pageIndex: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap = withContext(Dispatchers.IO) {
        val cacheKey = "page_${pageIndex}_${targetWidth}x${targetHeight}"
        bitmapCache.get(cacheKey)?.let { return@withContext it }

        renderLock.withLock {
            if (isClosed) throw IllegalStateException("PDF 引擎已关闭")
            bitmapCache.get(cacheKey)?.let { return@withLock it }

            val r = renderer ?: throw IllegalStateException("PDF 引擎尚未初始化")
            val page = r.openPage(pageIndex)

            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)

            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            bitmapCache.put(cacheKey, bitmap)
            bitmap
        }
    }

    override suspend fun calculateCropBounds(pageIndex: Int): PageCropper2.CropBounds = withContext(Dispatchers.IO) {
        cropBoundsCache[pageIndex]?.let { return@withContext it }

        renderLock.withLock {
            if (isClosed) throw IllegalStateException("PDF 引擎已关闭")
            cropBoundsCache[pageIndex]?.let { return@withLock it }

            val r = renderer ?: throw IllegalStateException("PDF 引擎尚未初始化")
            val page = r.openPage(pageIndex)

            val sampleW = PageCropper2.SAMPLE_SIZE
            val aspectRatio = page.width.toFloat() / page.height.toFloat()
            val sampleH = (sampleW / aspectRatio).roundToInt().coerceAtLeast(100)

            val sampleBitmap = Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888)
            sampleBitmap.eraseColor(Color.WHITE)

            page.render(sampleBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val bounds = PageCropper2.getCropBounds(sampleBitmap)
            sampleBitmap.recycle()

            cropBoundsCache[pageIndex] = bounds
            bounds
        }
    }

    override suspend fun calculateColumnBounds(
        pageIndex: Int,
        tapX: Float,
        tapY: Float
    ): PageCropper2.CropBounds = withContext(Dispatchers.IO) {
        renderLock.withLock {
            if (isClosed) throw IllegalStateException("PDF 引擎已关闭")
            val r = renderer ?: throw IllegalStateException("PDF 引擎尚未初始化")
            val page = r.openPage(pageIndex)

            val sampleW = PageCropper2.SAMPLE_SIZE
            val aspectRatio = page.width.toFloat() / page.height.toFloat()
            val sampleH = (sampleW / aspectRatio).roundToInt().coerceAtLeast(100)

            val sampleBitmap = Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888)
            sampleBitmap.eraseColor(Color.WHITE)

            page.render(sampleBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val pixels = IntArray(sampleW * sampleH)
            sampleBitmap.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
            sampleBitmap.recycle()

            PageCropper2.getColumn(pixels, sampleW, sampleH, tapX, tapY)
        }
    }

    override suspend fun getOutlines(): List<PdfOutlineItem> = withContext(Dispatchers.IO) {
        val currentPfd = pfd ?: return@withContext emptyList()
        try {
            val dupPfd = currentPfd.dup() ?: return@withContext emptyList()
            dupPfd.use { dpfd ->
                FileInputStream(dpfd.fileDescriptor).use { stream ->
                    PdfOutlineExtractor.extractOutlines(stream)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun reload(pfd: ParcelFileDescriptor): PdfDocumentInfo = withContext(Dispatchers.IO) {
        renderLock.withLock {
            val oldDoc = docInfo ?: throw IllegalStateException("文档尚未打开，无法执行热重载")
            closeInternal()
            isClosed = false
            this@AndroidPdfRendererEngine.pfd = pfd
            try {
                val newRenderer = PdfRenderer(pfd)
                this@AndroidPdfRendererEngine.renderer = newRenderer
                val pageCount = newRenderer.pageCount
                val safeInitialPage = oldDoc.initialPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                val newInfo = oldDoc.copy(
                    pageCount = pageCount,
                    fileSize = pfd.statSize,
                    initialPage = safeInitialPage
                )
                this@AndroidPdfRendererEngine.docInfo = newInfo
                newInfo
            } catch (e: Exception) {
                closeInternal()
                throw IOException("无法重新加载 PdfRenderer: ${e.message}", e)
            }
        }
    }

    override fun clearMemoryCache() {
        bitmapCache.clear()
        pageInfoCache.clear()
        cropBoundsCache.clear()
    }

    override fun getCachedCropBounds(pageIndex: Int): PageCropper2.CropBounds? {
        return cropBoundsCache[pageIndex]
    }

    override fun close() {
        isClosed = true
        renderLock.withLock {
            closeInternal()
        }
    }

    private fun closeInternal() {
        bitmapCache.clear()
        pageInfoCache.clear()
        cropBoundsCache.clear()
        try {
            renderer?.close()
        } catch (_: Exception) {}
        renderer = null

        try {
            pfd?.close()
        } catch (_: Exception) {}
        pfd = null
        docInfo = null
    }
}
