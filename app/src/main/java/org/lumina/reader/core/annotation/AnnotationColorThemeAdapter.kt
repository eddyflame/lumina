package org.lumina.reader.core.annotation

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import org.lumina.reader.core.model.ReadingColorMode
import kotlin.math.roundToInt

/**
 * 注释色彩与阅读主题自适应转换器：
 *
 * 在深色/柔和夜间阅读模式下，文档底色与文字发生反转 (白底黑字 -> 黑底白字)。
 * 本转换器对注释笔迹色彩施加与 PDF 底图严格互逆/一致的仿射反相变换，
 * 解决夜间模式下深色/黑色笔迹融入暗黑背景无法看清的痛点。
 */
object AnnotationColorThemeAdapter {

    /**
     * 将存储的原始注释颜色映射为当前主题下的显示色彩。
     * - 日间普通模式 (NORMAL)：保留原始色彩不变；
     * - 柔和深色模式 (SOFT_DARK)：与 -0.722/-0.729 负斜率反相关矩阵完全匹配；
     * - AMOLED 夜间模式 (AMOLED_DARK)：与 -0.80 负斜率反相关矩阵完全匹配，纯黑/碳黑笔迹映射为纯正明亮的银白色。
     */
    fun resolveDisplayColor(color: Long, colorMode: ReadingColorMode): Color {
        if (colorMode == ReadingColorMode.NORMAL) {
            return Color(color)
        }
        val a = ((color shr 24) and 0xFF).toInt()
        val r = ((color shr 16) and 0xFF).toInt()
        val g = ((color shr 8) and 0xFF).toInt()
        val b = (color and 0xFF).toInt()

        val (rNew, gNew, bNew) = computeInvertedRgb(r, g, b, colorMode)

        return Color(
            red = rNew / 255f,
            green = gNew / 255f,
            blue = bNew / 255f,
            alpha = a / 255f
        )
    }

    /**
     * 返回 32 位 ARGB Long 格式的映射颜色，便于单元测试与色值比较
     */
    fun resolveDisplayColorLong(color: Long, colorMode: ReadingColorMode): Long {
        if (colorMode == ReadingColorMode.NORMAL) {
            return color
        }
        val a = ((color shr 24) and 0xFF).toInt()
        val r = ((color shr 16) and 0xFF).toInt()
        val g = ((color shr 8) and 0xFF).toInt()
        val b = (color and 0xFF).toInt()

        val (rNew, gNew, bNew) = computeInvertedRgb(r, g, b, colorMode)

        return ((a.toLong() and 0xFF) shl 24) or
                ((rNew.toLong() and 0xFF) shl 16) or
                ((gNew.toLong() and 0xFF) shl 8) or
                (bNew.toLong() and 0xFF)
    }

    /**
     * 根据当前阅读主题获取荧光高亮笔的最佳混合模式。
     * - 日间明亮背景：Multiply (正片叠底) 适度加深纸面；
     * - 夜间深色背景：Screen (滤色) 适度提亮黑底，并保持底层浅色文字最高对比度不被盖死。
     */
    fun resolveHighlighterBlendMode(colorMode: ReadingColorMode): BlendMode {
        return if (colorMode == ReadingColorMode.NORMAL) BlendMode.Multiply else BlendMode.Screen
    }

    private fun computeInvertedRgb(r: Int, g: Int, b: Int, colorMode: ReadingColorMode): Triple<Int, Int, Int> {
        return when (colorMode) {
            ReadingColorMode.SOFT_DARK -> {
                Triple(
                    (214f - 0.722f * r).roundToInt().coerceIn(0, 255),
                    (220f - 0.729f * g).roundToInt().coerceIn(0, 255),
                    (229f - 0.729f * b).roundToInt().coerceIn(0, 255)
                )
            }
            ReadingColorMode.AMOLED_DARK -> {
                Triple(
                    (204f - 0.80f * r).roundToInt().coerceIn(0, 255),
                    (204f - 0.80f * g).roundToInt().coerceIn(0, 255),
                    (204f - 0.80f * b).roundToInt().coerceIn(0, 255)
                )
            }
            ReadingColorMode.NORMAL -> Triple(r, g, b)
        }
    }
}
