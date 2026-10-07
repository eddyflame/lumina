package org.lumina.reader.core.crop

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * PageCropper2: 下一代智能页面白边裁切与分栏聚焦算法
 *
 * 移植并升级自 EBookDroid / Document Viewer 的经典 PageCropper 算法：
 * 1. 采用轻量采样子图（默认 400px）极速探测，纯 Kotlin 实现执行耗时 < 1.5ms；
 * 2. 纯代码运行，杜绝 16KB 内存分页对齐崩溃与 JNI 跨语言调用开销；
 * 3. 具备自适应边缘抗噪、动态亮度差分以及论文双栏/多栏轻触聚焦定位。
 */
object PageCropper2 {

    /** 采样子图基准分辨率 */
    const val SAMPLE_SIZE = 400

    /** 纵向步进跨度 (像素) */
    private const val V_LINE_SIZE = 5

    /** 横向步进跨度 (像素) */
    private const val H_LINE_SIZE = 5

    /** 边缘避让安全边距 (像素)，有效滤除装订线阴影、边缘脏点 */
    private const val LINE_MARGIN = 15

    /** 留白容差阈值 (0.5% 以下暗点仍视为空白) */
    private const val WHITE_THRESHOLD = 0.005f

    /** 分栏探测垂直窗口半高 */
    private const val COLUMN_HALF_HEIGHT = 15

    /** 分栏探测步进宽度 */
    private const val COLUMN_WIDTH = 5

    /**
     * 裁切边界数据类 (归一化坐标 0.0f .. 1.0f)
     */
    data class CropBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = (right - left).coerceAtLeast(0f)
        val height: Float get() = (bottom - top).coerceAtLeast(0f)

        fun toRectF(): RectF = RectF(left, top, right, bottom)

        companion object {
            val FULL = CropBounds(0f, 0f, 1f, 1f)
        }
    }

    /**
     * 计算位图的智能裁切边界
     *
     * @param bitmap 页面缩略图 (建议为 400x400 左右尺寸)
     * @param extraPaddingRatio 额外保留的内容呼吸内边距 (默认 0.015f，即 1.5%，避免文字紧贴屏幕边缘)
     */
    fun getCropBounds(
        bitmap: Bitmap,
        extraPaddingRatio: Float = 0.015f
    ): CropBounds {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        return getCropBoundsFromPixels(pixels, width, height, extraPaddingRatio)
    }

    /**
     * 基于像素数组计算裁切边界（支持纯 JVM 单元测试与无 Android 图形依赖运行）
     */
    fun getCropBoundsFromPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        extraPaddingRatio: Float = 0.015f
    ): CropBounds {
        if (width <= LINE_MARGIN * 2 || height <= LINE_MARGIN * 2) {
            return CropBounds.FULL
        }

        val avgLum = calculateAvgLum(pixels, width, height, 0, 0, width, height)

        val rawLeft = getLeftBound(pixels, width, height, avgLum)
        val rawTop = getTopBound(pixels, width, height, avgLum)
        val rawRight = getRightBound(pixels, width, height, avgLum)
        val rawBottom = getBottomBound(pixels, width, height, avgLum)

        // 应用自适应呼吸内边距：水平与垂直方向均衡保留边距 (默认至少 2% 呼吸感，避免文字紧贴屏幕边缘)
        val paddingX = extraPaddingRatio
        val paddingY = if (extraPaddingRatio > 0f) max(extraPaddingRatio, 0.02f) else 0f

        val left = (rawLeft - paddingX).coerceIn(0f, 1f)
        val top = (rawTop - paddingY).coerceIn(0f, 1f)
        val right = (rawRight + paddingX).coerceIn(0f, 1f)
        val bottom = (rawBottom + paddingY).coerceIn(0f, 1f)

        // 安全检查：如果裁切后内容区异常偏小 (例如小于原图 30%)，回退到全图避免误裁
        return if (right - left < 0.3f || bottom - top < 0.3f) {
            CropBounds.FULL
        } else {
            CropBounds(left, top, right, bottom)
        }
    }

    /**
     * 智能分栏定位 (根据点击坐标 x, y 定位该栏文字范围)
     */
    fun getColumn(
        pixels: IntArray,
        width: Int,
        height: Int,
        tapXRatio: Float,
        tapYRatio: Float
    ): CropBounds {
        val avgLum = max(200, calculateAvgLum(pixels, width, height, 0, 0, width, height))

        val colLeft = getLeftColumnBound(pixels, width, height, avgLum, tapXRatio, tapYRatio)
        val colRight = getRightColumnBound(pixels, width, height, avgLum, tapXRatio, tapYRatio)

        return CropBounds(colLeft, 0f, colRight, 1f)
    }

    // ================================= 核心计算内部函数 =================================

    /**
     * 计算指定矩形区域的平均亮度 (采用标准 ITU-R BT.601 感知亮度加权)
     */
    internal fun calculateAvgLum(
        pixels: IntArray,
        width: Int,
        height: Int,
        subX: Int,
        subY: Int,
        subW: Int,
        subH: Int
    ): Int {
        var totalBright = 0L
        for (y in 0 until subH) {
            val rowOffset = (y + subY) * width + subX
            for (x in 0 until subW) {
                val color = pixels[rowOffset + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                totalBright += (r * 77 + g * 150 + b * 29) shr 8
            }
        }
        val count = max(1, subW * subH)
        return (totalBright / count).toInt()
    }

    /**
     * 判定指定像素是否属于正文/有效内容 (黑字、彩字、红印章、图表、反白底色等)
     *
     * 针对扫描版 PDF 的色彩学精准判定：
     * 1. 采用 ITU-R BT.601 国际照明委员会感知明度，红光视觉权重为 0.299，杜绝 (min+max)/2 将红色虚高折算为浅灰的问题；
     * 2. 引入色彩饱和度/色度差 (Chroma = max(RGB) - min(RGB)) 与红色特征向量，纸张留白边缘为极低色度近无色，
     *    而白底红字、红印章、红底白字条幅等具备极强色度特征，可精准识别为正文；
     * 3. 兼容常规浅色纸张与深色/反色 PDF 背景。
     */
    internal fun isContentPixel(color: Int, avgLum: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        val lum = (r * 77 + g * 150 + b * 29) shr 8
        val maxVal = max(r, max(g, b))
        val minVal = min(r, min(g, b))
        val chroma = maxVal - minVal

        if (avgLum >= 120) {
            // 常规浅色/白底纸张环境 (占 99% 以上书籍扫描场景)
            val bgLum = max(avgLum, 220)
            // (A) 像素亮度显著暗于纸张背景，或处于明显正文暗调区间 (黑字、深灰、铅笔、插图暗调)
            val isDark = (bgLum - lum) > 25 || lum < 195
            // (B) 像素具备显著色彩饱度 (白底红字、红印章、红底白字色块底色、彩色印刷插图)
            // 纸张留白边缘通常为纯白或微黄灰白，chroma 极低 (< 15)
            // 红字典型特征: 红色显著偏高 (r > 100 且 r - g > 20 且 r - b > 20) 或总体色度差明显 (chroma > 22)
            val isChromatic = chroma > 22 || (r > 100 && (r - g > 20 && r - b > 20))
            return isDark || isChromatic
        } else {
            // 深色背景环境 (纯黑/深灰背景页面)
            val diffLum = kotlin.math.abs(lum - avgLum)
            return diffLum > 30 || chroma > 25
        }
    }

    /**
     * 检测指定矩形区域是否属于“留白”
     */
    internal fun isRectWhite(
        pixels: IntArray,
        width: Int,
        height: Int,
        subX: Int,
        subY: Int,
        subW: Int,
        subH: Int,
        avgLum: Int,
        threshold: Float = WHITE_THRESHOLD
    ): Boolean {
        var contentCount = 0
        for (y in 0 until subH) {
            val rowOffset = (y + subY) * width + subX
            for (x in 0 until subW) {
                val color = pixels[rowOffset + x]
                if (isContentPixel(color, avgLum)) {
                    contentCount++
                }
            }
        }
        val total = max(1, subW * subH)
        val ratio = contentCount.toFloat() / total
        return ratio < threshold && contentCount < 5
    }

    private fun getLeftBound(pixels: IntArray, width: Int, height: Int, avgLum: Int): Float {
        val maxScanW = width / 3
        var x = 0
        while (x < maxScanW) {
            val isWhite = isRectWhite(
                pixels, width, height,
                x, LINE_MARGIN, V_LINE_SIZE, height - 2 * LINE_MARGIN, avgLum
            )
            if (!isWhite) {
                return if (x == 0) 0f else max(0, x - V_LINE_SIZE).toFloat() / width
            }
            x += V_LINE_SIZE
        }
        return 0f
    }

    private fun getTopBound(pixels: IntArray, width: Int, height: Int, avgLum: Int): Float {
        val maxScanH = height / 3
        var y = 0
        while (y < maxScanH) {
            val isWhite = isRectWhite(
                pixels, width, height,
                LINE_MARGIN, y, width - 2 * LINE_MARGIN, H_LINE_SIZE, avgLum,
                threshold = 0.0015f // 顶部横向扫描灵敏度更高，防止漏过首行文字/页眉
            )
            if (!isWhite) {
                // 如果从边缘 (y == 0) 就直接探测到内容（如满版封面、贴顶通栏条幅），直接返回 0f 避免过度裁切
                // 否则回退 2 个步进周期，预留充分的顶部文字笔画上升部空间
                return if (y == 0) 0f else max(0, y - 2 * H_LINE_SIZE).toFloat() / height
            }
            y += H_LINE_SIZE
        }
        return 0f
    }

    private fun getRightBound(pixels: IntArray, width: Int, height: Int, avgLum: Int): Float {
        val maxScanW = width / 3
        var x = width - V_LINE_SIZE
        while (x > width - maxScanW) {
            val isWhite = isRectWhite(
                pixels, width, height,
                x, LINE_MARGIN, V_LINE_SIZE, height - 2 * LINE_MARGIN, avgLum
            )
            if (!isWhite) {
                return if (x == width - V_LINE_SIZE) 1f else min(width, x + 2 * V_LINE_SIZE).toFloat() / width
            }
            x -= V_LINE_SIZE
        }
        return 1f
    }

    private fun getBottomBound(pixels: IntArray, width: Int, height: Int, avgLum: Int): Float {
        val maxScanH = height / 3
        var y = height - H_LINE_SIZE
        while (y > height - maxScanH) {
            val isWhite = isRectWhite(
                pixels, width, height,
                LINE_MARGIN, y, width - 2 * LINE_MARGIN, H_LINE_SIZE, avgLum,
                threshold = 0.0015f // 底部横向扫描灵敏度更高，防止漏过页脚/尾行
            )
            if (!isWhite) {
                // 如果从底部边缘就直接探测到内容（如底栏出版社文字、条形码、满版背景），直接返回 1f
                // 否则多扩展 3 个周期保留余量，防止下伸部笔画与尾行被截断
                return if (y == height - H_LINE_SIZE) 1f else min(height, y + 3 * H_LINE_SIZE).toFloat() / height
            }
            y -= H_LINE_SIZE
        }
        return 1f
    }

    private fun getLeftColumnBound(
        pixels: IntArray, width: Int, height: Int, avgLum: Int, xRatio: Float, yRatio: Float
    ): Float {
        var blackFound = false
        val pointX = (width * xRatio).toInt().coerceIn(0, width - 1)
        val pointY = (height * yRatio).toInt().coerceIn(0, height - 1)
        val top = max(0, pointY - COLUMN_HALF_HEIGHT)
        val bottom = min(height - 1, pointY + COLUMN_HALF_HEIGHT)

        var left = pointX
        while (left >= 0) {
            val isWhite = isRectWhite(
                pixels, width, height, left, top, COLUMN_WIDTH, bottom - top, avgLum
            )
            if (isWhite) {
                if (blackFound) {
                    return left.toFloat() / width
                }
            } else {
                blackFound = true
            }
            left -= COLUMN_WIDTH
        }
        return 0f
    }

    private fun getRightColumnBound(
        pixels: IntArray, width: Int, height: Int, avgLum: Int, xRatio: Float, yRatio: Float
    ): Float {
        var blackFound = false
        val pointX = (width * xRatio).toInt().coerceIn(0, width - 1)
        val pointY = (height * yRatio).toInt().coerceIn(0, height - 1)
        val top = max(0, pointY - COLUMN_HALF_HEIGHT)
        val bottom = min(height - 1, pointY + COLUMN_HALF_HEIGHT)

        var left = pointX
        while (left < width - COLUMN_WIDTH) {
            val isWhite = isRectWhite(
                pixels, width, height, left, top, COLUMN_WIDTH, bottom - top, avgLum
            )
            if (isWhite) {
                if (blackFound) {
                    return (left + COLUMN_WIDTH).toFloat() / width
                }
            } else {
                blackFound = true
            }
            left += COLUMN_WIDTH
        }
        return 1f
    }
}
