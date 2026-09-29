package org.lumina.reader.core.engine

import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.lumina.reader.core.crop.PageCropper2
import org.lumina.reader.core.model.PageInfo
import org.lumina.reader.core.model.PdfDocumentInfo
import org.lumina.reader.core.model.PdfOutlineItem

/**
 * PDF 渲染引擎通用抽象接口
 *
 * 隔离具体渲染实现（官方 PdfRenderer / 16KB 适配版 Pdfium / 现代 MuPDF），确保架构解耦
 */
interface PdfEngine {

    /**
     * 打开 PDF 文件描述符
     */
    suspend fun open(uri: Uri, title: String, pfd: ParcelFileDescriptor, initialPage: Int = 0): PdfDocumentInfo

    /**
     * 获取指定页面元数据
     */
    suspend fun getPageInfo(pageIndex: Int): PageInfo

    /**
     * 渲染指定页面的高分辨率位图
     */
    suspend fun renderPage(pageIndex: Int, targetWidth: Int, targetHeight: Int): Bitmap

    /**
     * 计算指定页面的智能白边裁切边界
     */
    suspend fun calculateCropBounds(pageIndex: Int): PageCropper2.CropBounds

    /**
     * 计算指定触控坐标的分栏锁定边界 (双栏/多栏论文阅读)
     */
    suspend fun calculateColumnBounds(pageIndex: Int, tapX: Float, tapY: Float): PageCropper2.CropBounds

    /**
     * 获取文档大纲目录 (TOC)
     */
    suspend fun getOutlines(): List<PdfOutlineItem>

    /**
     * 关闭并释放底层资源
     */
    fun close()
}
