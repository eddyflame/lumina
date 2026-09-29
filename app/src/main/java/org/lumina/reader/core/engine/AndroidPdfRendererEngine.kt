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
import java.io.IOException
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

    private val mutex = Mutex()
    private val pageInfoCache = mutableMapOf<Int, PageInfo>()
    private val cropBoundsCache = mutableMapOf<Int, PageCropper2.CropBounds>()

    override suspend fun open(
        uri: Uri,
        title: String,
        pfd: ParcelFileDescriptor,
        initialPage: Int
    ): PdfDocumentInfo = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeInternal()
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

        mutex.withLock {
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

        mutex.withLock {
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

        mutex.withLock {
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
        mutex.withLock {
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
        val total = docInfo?.pageCount ?: 0
        if (total == 0) return@withContext emptyList()

        // 默认按章节/分页生成快速索引大纲
        val step = if (total > 50) 10 else 5
        val list = mutableListOf<PdfOutlineItem>()
        list.add(PdfOutlineItem("第 1 页 · 起始页", 0, level = 0))

        for (p in step until total step step) {
            list.add(PdfOutlineItem("第 ${p + 1} 页", p, level = 0))
        }

        if (total > 1 && (total - 1) % step != 0) {
            list.add(PdfOutlineItem("第 $total 页 · 结尾", total - 1, level = 0))
        }

        list
    }

    override fun close() {
        mutex.tryLock()
        try {
            closeInternal()
        } finally {
            if (mutex.isLocked) {
                mutex.unlock()
            }
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
