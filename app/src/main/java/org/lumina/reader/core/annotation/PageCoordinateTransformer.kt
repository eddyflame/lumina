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
     *
     * 支持自适应页面旋转角度补偿 (0, 90, 180, 270 度)：
     * 屏幕渲染视图呈现给用户的是直立视图，而 PDF 规范要求 /Annots 字典必须记录在未旋转的默认用户空间中。
     * 当外部阅读器渲染带 /Rotate 标记的页面时会自动顺时针旋转，因此此处需进行精确逆向映射。
     */
    fun normalizedToPdfPoint(
        point: NormalizedPoint,
        pageWidthPt: Float,
        pageHeightPt: Float,
        rotationDegrees: Int = 0
    ): Pair<Float, Float> {
        val normRot = ((rotationDegrees % 360) + 360) % 360
        val u = point.x
        val v = point.y
        return when (normRot) {
            90 -> {
                val pdfX = v * pageWidthPt
                val pdfY = u * pageHeightPt
                Pair(pdfX, pdfY)
            }
            180 -> {
                val pdfX = (1f - u) * pageWidthPt
                val pdfY = v * pageHeightPt
                Pair(pdfX, pdfY)
            }
            270 -> {
                val pdfX = (1f - v) * pageWidthPt
                val pdfY = (1f - u) * pageHeightPt
                Pair(pdfX, pdfY)
            }
            else -> { // 0°
                val pdfX = u * pageWidthPt
                // PDF 坐标系 Y 轴朝上，原点在左下角；屏幕 Y 轴朝下，原点在左上角
                val pdfY = (1f - v) * pageHeightPt
                Pair(pdfX, pdfY)
            }
        }
    }

    /**
     * ISO 32000-1 标准 PDF 物理点 -> 归一化坐标
     */
    fun pdfPointToNormalized(
        pdfX: Float,
        pdfY: Float,
        pageWidthPt: Float,
        pageHeightPt: Float,
        rotationDegrees: Int = 0
    ): NormalizedPoint {
        if (pageWidthPt <= 0f || pageHeightPt <= 0f) return NormalizedPoint(0f, 0f)
        val normRot = ((rotationDegrees % 360) + 360) % 360
        return when (normRot) {
            90 -> {
                val u = (pdfY / pageHeightPt).coerceIn(0f, 1f)
                val v = (pdfX / pageWidthPt).coerceIn(0f, 1f)
                NormalizedPoint(u, v)
            }
            180 -> {
                val u = (1f - (pdfX / pageWidthPt)).coerceIn(0f, 1f)
                val v = (pdfY / pageHeightPt).coerceIn(0f, 1f)
                NormalizedPoint(u, v)
            }
            270 -> {
                val u = (1f - (pdfY / pageHeightPt)).coerceIn(0f, 1f)
                val v = (1f - (pdfX / pageWidthPt)).coerceIn(0f, 1f)
                NormalizedPoint(u, v)
            }
            else -> { // 0°
                val u = (pdfX / pageWidthPt).coerceIn(0f, 1f)
                val v = (1f - (pdfY / pageHeightPt)).coerceIn(0f, 1f)
                NormalizedPoint(u, v)
            }
        }
    }

    /**
     * 将视口触控点按页面旋转角进行逆向旋转补偿，消除旋转图层下的手绘与擦除错位
     */
    fun unrotatePoint(
        localX: Float,
        localY: Float,
        width: Float,
        height: Float,
        rotationDegrees: Int
    ): Pair<Float, Float> {
        val normRot = ((rotationDegrees % 360) + 360) % 360
        if (normRot == 0) return Pair(localX, localY)

        val cx = width / 2f
        val cy = height / 2f
        val dx = localX - cx
        val dy = localY - cy

        return when (normRot) {
            90 -> Pair(cx + dy, cy - dx)
            180 -> Pair(cx - dx, cy - dy)
            270 -> Pair(cx - dy, cy + dx)
            else -> {
                val rad = Math.toRadians(-normRot.toDouble())
                val cosA = Math.cos(rad).toFloat()
                val sinA = Math.sin(rad).toFloat()
                Pair(cx + dx * cosA - dy * sinA, cy + dx * sinA + dy * cosA)
            }
        }
    }

    /**
     * 将未旋转坐标按页面旋转角正向旋转，映射回当前屏幕渲染视图空间
     */
    fun rotatePoint(
        localX: Float,
        localY: Float,
        width: Float,
        height: Float,
        rotationDegrees: Int
    ): Pair<Float, Float> {
        val normRot = ((rotationDegrees % 360) + 360) % 360
        if (normRot == 0) return Pair(localX, localY)

        val cx = width / 2f
        val cy = height / 2f
        val dx = localX - cx
        val dy = localY - cy

        return when (normRot) {
            90 -> Pair(cx - dy, cy + dx)
            180 -> Pair(cx - dx, cy - dy)
            270 -> Pair(cx + dy, cy - dx)
            else -> {
                val rad = Math.toRadians(normRot.toDouble())
                val cosA = Math.cos(rad).toFloat()
                val sinA = Math.sin(rad).toFloat()
                Pair(cx + dx * cosA - dy * sinA, cy + dx * sinA + dy * cosA)
            }
        }
    }

    /**
     * 将视口屏幕坐标点映射为页面未缩放的本地坐标 (逆向仿射变换)
     *
     * @param viewportX 视口绝对 X
     * @param viewportY 视口绝对 Y
     * @param itemOffsetX LazyColumn 中该 Item 的布局 offset.y
     * @param itemWidth Item 布局宽度 (通常为 viewportWidth)
     * @param itemHeight Item 布局高度 (含 divider)
     * @param scale 页面当前缩放比例
     * @param offsetX 页面当前 X 轴平移
     * @param offsetY 页面当前 Y 轴平移
     * @return 页面本地坐标 (localX, localY)，其中 localY 的有效页面范围为 0..contentHeight
     */
    fun screenToLocal(
        viewportX: Float,
        viewportY: Float,
        itemOffsetX: Float,
        itemWidth: Float,
        itemHeight: Float,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    ): Pair<Float, Float> {
        val s = scale.coerceAtLeast(0.01f)
        val pivotX = itemWidth / 2f
        val pivotY = itemHeight / 2f
        val centerViewportX = itemWidth / 2f
        val centerViewportY = itemOffsetX + pivotY

        val localX = pivotX + (viewportX - centerViewportX - offsetX) / s
        val localY = pivotY + (viewportY - centerViewportY - offsetY) / s
        return Pair(localX, localY)
    }

    /**
     * 将页面本地坐标映射回视口屏幕坐标 (正向仿射变换)
     */
    fun localToScreen(
        localX: Float,
        localY: Float,
        itemOffsetX: Float,
        itemWidth: Float,
        itemHeight: Float,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    ): Pair<Float, Float> {
        val pivotX = itemWidth / 2f
        val pivotY = itemHeight / 2f
        val centerViewportX = itemWidth / 2f
        val centerViewportY = itemOffsetX + pivotY

        val screenX = centerViewportX + (localX - pivotX) * scale + offsetX
        val screenY = centerViewportY + (localY - pivotY) * scale + offsetY
        return Pair(screenX, screenY)
    }
}

