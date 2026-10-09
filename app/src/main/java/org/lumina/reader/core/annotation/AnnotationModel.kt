package org.lumina.reader.core.annotation

import java.util.UUID

/**
 * 归一化点坐标 (0.0f .. 1.0f)，保证不同屏幕分辨率与缩放比例下的无损缩放
 */
data class NormalizedPoint(
    val x: Float,
    val y: Float
)

/**
 * 归一化矩形区域 (0.0f .. 1.0f)
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    fun contains(point: NormalizedPoint): Boolean {
        return point.x in left..right && point.y in top..bottom
    }
}

/**
 * 当前激活的注释与手绘工具模式
 */
enum class AnnotationTool {
    /** 正常阅读浏览模式 (翻页/缩放) */
    NONE,
    /** 自由手写笔 (矢量 Ink，可调节颜色与粗细) */
    PEN,
    /** 荧光高亮笔 (半透明正片叠底，可调节颜色与粗细) */
    HIGHLIGHTER,
    /** 橡皮擦 (按笔迹判定擦除) */
    ERASER,
    /** 便签文字批注 */
    NOTE
}

/**
 * PDF 注释抽象基类 (遵循 ISO 32000-1 标准规范)
 */
sealed class PdfAnnotation {
    abstract val id: String
    abstract val pageIndex: Int
    abstract val color: Long // 32-bit ARGB 颜色
    abstract val createdAt: Long
    abstract val boundingBox: NormalizedRect

    /**
     * 自由手写笔迹注释 (对应 PDF 标准 /Ink 字典)
     */
    data class Ink(
        override val id: String = UUID.randomUUID().toString(),
        override val pageIndex: Int,
        override val color: Long = 0xFF0066FF, // 默认主题蓝
        val strokeWidthDp: Float = 3f,
        val isHighlighter: Boolean = false,
        /** 多条连续笔画点序列 (一笔包含多个坐标点) */
        val strokes: List<List<NormalizedPoint>>,
        override val createdAt: Long = System.currentTimeMillis()
    ) : PdfAnnotation() {

        override val boundingBox: NormalizedRect by lazy {
            if (strokes.isEmpty() || strokes.all { it.isEmpty() }) {
                NormalizedRect(0f, 0f, 0f, 0f)
            } else {
                var minX = 1f
                var minY = 1f
                var maxX = 0f
                var maxY = 0f
                for (stroke in strokes) {
                    for (pt in stroke) {
                        if (pt.x < minX) minX = pt.x
                        if (pt.x > maxX) maxX = pt.x
                        if (pt.y < minY) minY = pt.y
                        if (pt.y > maxY) maxY = pt.y
                    }
                }
                NormalizedRect(minX, minY, maxX, maxY)
            }
        }

        /**
         * 判定给定归一化触控点是否与该笔迹相交 (用于橡皮擦精准擦除判定)
         *
         * @param tapPoint 触控点归一化坐标 (0..1)
         * @param threshold 判定半径 (归一化宽度基准，默认 0.025f 对应约 12dp 精细橡皮擦)
         * @param pageAspect 页面高宽比 (height / width)，用于保证各向同性物理圆形碰撞判定
         */
        fun intersects(tapPoint: NormalizedPoint, threshold: Float = 0.025f, pageAspect: Float = 1.4f): Boolean {
            val aspect = pageAspect.coerceIn(0.2f, 5.0f)
            val thresholdSq = threshold * threshold

            // 先通过粗粒度包围盒快速排除 (按高宽比补偿垂直范围)
            val thresholdY = threshold / aspect
            if (tapPoint.x < boundingBox.left - threshold ||
                tapPoint.x > boundingBox.right + threshold ||
                tapPoint.y < boundingBox.top - thresholdY ||
                tapPoint.y > boundingBox.bottom + thresholdY
            ) {
                return false
            }

            // 细粒度线段距离碰撞检测 (使用各向同性真实物理几何距离)
            for (stroke in strokes) {
                if (stroke.isEmpty()) continue
                if (stroke.size == 1) {
                    val p = stroke[0]
                    val dx = tapPoint.x - p.x
                    val dy = (tapPoint.y - p.y) * aspect
                    if (dx * dx + dy * dy <= thresholdSq) {
                        return true
                    }
                } else {
                    for (i in 0 until stroke.size - 1) {
                        val p1 = stroke[i]
                        val p2 = stroke[i + 1]
                        if (distanceSqToSegment(tapPoint, p1, p2, aspect) <= thresholdSq) {
                            return true
                        }
                    }
                }
            }
            return false
        }

        private fun distanceSqToSegment(p: NormalizedPoint, p1: NormalizedPoint, p2: NormalizedPoint, aspect: Float): Float {
            val dx = p2.x - p1.x
            val dy = (p2.y - p1.y) * aspect
            val lengthSq = dx * dx + dy * dy
            val px = p.x - p1.x
            val py = (p.y - p1.y) * aspect
            if (lengthSq <= 1e-8f) {
                return px * px + py * py
            }
            val t = ((px * dx + py * dy) / lengthSq).coerceIn(0f, 1f)
            val distDx = px - t * dx
            val distDy = py - t * dy
            return distDx * distDx + distDy * distDy
        }
    }

    /**
     * 文本高亮注释 (对应 PDF 标准 /Highlight 字典)
     */
    data class Highlight(
        override val id: String = UUID.randomUUID().toString(),
        override val pageIndex: Int,
        override val color: Long = 0x66FFEB3B, // 半透明明黄
        val rects: List<NormalizedRect>,
        val selectedText: String = "",
        override val createdAt: Long = System.currentTimeMillis()
    ) : PdfAnnotation() {
        override val boundingBox: NormalizedRect by lazy {
            if (rects.isEmpty()) {
                NormalizedRect(0f, 0f, 0f, 0f)
            } else {
                NormalizedRect(
                    left = rects.minOf { it.left },
                    top = rects.minOf { it.top },
                    right = rects.maxOf { it.right },
                    bottom = rects.maxOf { it.bottom }
                )
            }
        }
    }

    /**
     * 便签图钉与文字批注 (对应 PDF 标准 /Text 字典)
     */
    data class Note(
        override val id: String = UUID.randomUUID().toString(),
        override val pageIndex: Int,
        override val color: Long = 0xFFFF9800, // 温暖橙黄
        val position: NormalizedPoint,
        val content: String,
        val author: String = "Lumina",
        override val createdAt: Long = System.currentTimeMillis()
    ) : PdfAnnotation() {
        override val boundingBox: NormalizedRect get() = NormalizedRect(
            left = (position.x - 0.02f).coerceAtLeast(0f),
            top = (position.y - 0.02f).coerceAtLeast(0f),
            right = (position.x + 0.02f).coerceAtMost(1f),
            bottom = (position.y + 0.02f).coerceAtMost(1f)
        )
    }
}
