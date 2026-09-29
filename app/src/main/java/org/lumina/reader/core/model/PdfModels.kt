package org.lumina.reader.core.model

import android.net.Uri
import org.lumina.reader.core.crop.PageCropper2

/**
 * PDF 文档基本元数据
 */
data class PdfDocumentInfo(
    val uri: Uri,
    val title: String,
    val pageCount: Int,
    val fileSize: Long = 0L,
    val initialPage: Int = 0
)

/**
 * 单页几何信息与智能裁切边界
 */
data class PageInfo(
    val index: Int,
    val width: Int,
    val height: Int,
    val cropBounds: PageCropper2.CropBounds = PageCropper2.CropBounds.FULL,
    val columnBounds: PageCropper2.CropBounds? = null
) {
    val aspectRatio: Float get() = if (height > 0) width.toFloat() / height.toFloat() else 1f
}

/**
 * 目录大纲项
 */
data class PdfOutlineItem(
    val title: String,
    val pageIndex: Int,
    val level: Int = 0
)

/**
 * 视觉色彩模式
 */
enum class ReadingColorMode {
    /** 常规白底黑字 */
    NORMAL,
    /** 夜间纯黑反色 (AMOLED 极致省电护眼) */
    NIGHT_INVERT,
    /** 柔和暖色羊皮纸 (日间舒适护眼) */
    SEPIA
}

/**
 * 排版翻页模式
 */
enum class ReadingLayoutMode {
    /** 连续纵向瀑布流 (阅读顺畅首选) */
    CONTINUOUS_VERTICAL,
    /** 单页横向左右翻页 */
    SINGLE_PAGE_HORIZONTAL
}
