package org.lumina.reader.core.annotation

/**
 * PDF 页面几何与坐标变换核心纯函数工具
 *
 * 处理三种坐标系之间的双向无损变换：
 * 1. 本地画布像素坐标 Local Canvas Pixel (0..viewWidth, 0..viewHeight)
 * 2. 归一化页面坐标 Normalized Coordinate (0.0f..1.0f)
 * 3. PDF 工业标准物理坐标 PDF Point (以 72 DPI 为基准，原点在左下角，符合 ISO 32000-1 规范)
 */
object PageCoordinateTransformer {

    /**
     * 将本地画布像素坐标转换为归一化页面坐标 (0.0f..1.0f)
     */
    fun canvasToNormalized(
        canvasX: Float,
        canvasY: Float,
        viewWidth: Float,
        viewHeight: Float
    ): NormalizedPoint {
        if (viewWidth <= 0f || viewHeight <= 0f) return NormalizedPoint(0f, 0f)
        val u = (canvasX / viewWidth).coerceIn(0f, 1f)
        val v = (canvasY / viewHeight).coerceIn(0f, 1f)
        return NormalizedPoint(u, v)
    }

    /**
     * 将归一化页面坐标转换为本地画布像素坐标
     */
    fun normalizedToCanvas(
        point: NormalizedPoint,
        viewWidth: Float,
        viewHeight: Float
    ): Pair<Float, Float> {
        val x = point.x * viewWidth
        val y = point.y * viewHeight
        return Pair(x, y)
    }

    /**
     * 将归一化矩形转换为本地画布像素矩形
     */
    fun normalizedRectToCanvas(
        rect: NormalizedRect,
        viewWidth: Float,
        viewHeight: Float
    ): FloatArray { // [left, top, right, bottom]
        return floatArrayOf(
            rect.left * viewWidth,
            rect.top * viewHeight,
            rect.right * viewWidth,
            rect.bottom * viewHeight
        )
    }

    /**
     * 归一化坐标 -> ISO 32000-1 标准 PDF 物理点 (72 DPI，原点在左下角)
     */
    fun normalizedToPdfPoint(
        point: NormalizedPoint,
        pageWidthPt: Float,
        pageHeightPt: Float
    ): Pair<Float, Float> {
        val pdfX = point.x * pageWidthPt
        // PDF 坐标系 Y 轴朝上，原点在左下角；屏幕 Y 轴朝下，原点在左上角
        val pdfY = (1f - point.y) * pageHeightPt
        return Pair(pdfX, pdfY)
    }

    /**
     * ISO 32000-1 标准 PDF 物理点 -> 归一化坐标
     */
    fun pdfPointToNormalized(
        pdfX: Float,
        pdfY: Float,
        pageWidthPt: Float,
        pageHeightPt: Float
    ): NormalizedPoint {
        if (pageWidthPt <= 0f || pageHeightPt <= 0f) return NormalizedPoint(0f, 0f)
        val u = (pdfX / pageWidthPt).coerceIn(0f, 1f)
        val v = (1f - (pdfY / pageHeightPt)).coerceIn(0f, 1f)
        return NormalizedPoint(u, v)
    }
}
