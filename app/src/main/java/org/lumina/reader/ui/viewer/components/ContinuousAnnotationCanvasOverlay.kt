package org.lumina.reader.ui.viewer.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.lumina.reader.core.annotation.AnnotationTool
import org.lumina.reader.core.annotation.NormalizedPoint
import org.lumina.reader.core.crop.PageCropper2
import org.lumina.reader.core.model.PageEditSpec
import org.lumina.reader.ui.viewer.ViewerUiState
import org.lumina.reader.ui.viewer.ViewerViewModel
import kotlin.math.max
import kotlin.math.min

/**
 * 连续纵向瀑布流专用全局手绘注释图层：
 *
 * 覆盖在整个 LazyColumn 视口上方，彻底打破单个页面卡片的 clipToBounds 边界隔离，
 * 实现真正的跨页连续书写、笔划不间断实时预览以及原子事务多页注释分发。
 */
@Composable
fun ContinuousAnnotationCanvasOverlay(
    listState: LazyListState,
    effectiveSpecs: List<PageEditSpec>,
    uiState: ViewerUiState,
    viewModel: ViewerViewModel,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val activeTool = uiState.annotationTool
    val annotationColor = uiState.annotationColor
    val strokeWidthDp = uiState.annotationStrokeWidthDp
    val isHighlighter = activeTool == AnnotationTool.HIGHLIGHTER
    val dividerHeightPx = with(density) { 18.dp.toPx() }

    var activeRawOffsets by remember { mutableStateOf<List<Offset>>(emptyList()) }

    val gestureModifier = Modifier.pointerInput(activeTool, annotationColor, strokeWidthDp) {
        if (activeTool == AnnotationTool.PEN || activeTool == AnnotationTool.HIGHLIGHTER) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                activeRawOffsets = listOf(down.position)

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.pressed) {
                        change.consume()
                        activeRawOffsets = activeRawOffsets + change.position
                    } else {
                        break
                    }
                }

                if (activeRawOffsets.isNotEmpty()) {
                    commitContinuousStroke(
                        rawOffsets = activeRawOffsets,
                        listState = listState,
                        effectiveSpecs = effectiveSpecs,
                        uiState = uiState,
                        viewModel = viewModel,
                        isHighlighter = isHighlighter,
                        dividerHeightPx = dividerHeightPx
                    )
                }
                activeRawOffsets = emptyList()
            }
        } else if (activeTool == AnnotationTool.ERASER) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                eraseAtViewportOffset(
                    offset = down.position,
                    listState = listState,
                    effectiveSpecs = effectiveSpecs,
                    uiState = uiState,
                    viewModel = viewModel,
                    dividerHeightPx = dividerHeightPx
                )

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.pressed) {
                        change.consume()
                        eraseAtViewportOffset(
                            offset = change.position,
                            listState = listState,
                            effectiveSpecs = effectiveSpecs,
                            uiState = uiState,
                            viewModel = viewModel,
                            dividerHeightPx = dividerHeightPx
                        )
                    } else {
                        break
                    }
                }
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .then(gestureModifier)
    ) {
        if (activeRawOffsets.isNotEmpty() && (activeTool == AnnotationTool.PEN || activeTool == AnnotationTool.HIGHLIGHTER)) {
            val liveColor = if (isHighlighter) {
                Color(annotationColor).copy(alpha = 0.40f)
            } else {
                Color(annotationColor)
            }
            val liveBlendMode = if (isHighlighter) BlendMode.Multiply else BlendMode.SrcOver
            val liveWidthPx = strokeWidthDp * density.density
            val livePath = buildOverlayBezierPath(activeRawOffsets)

            drawPath(
                path = livePath,
                color = liveColor,
                style = Stroke(
                    width = liveWidthPx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                ),
                blendMode = liveBlendMode
            )
        }
    }
}

/**
 * 将视口像素级别的笔画切分并分发到对应页面的矢量注释模型中
 */
private fun commitContinuousStroke(
    rawOffsets: List<Offset>,
    listState: LazyListState,
    effectiveSpecs: List<PageEditSpec>,
    uiState: ViewerUiState,
    viewModel: ViewerViewModel,
    isHighlighter: Boolean,
    dividerHeightPx: Float
) {
    if (rawOffsets.isEmpty()) return
    val visibleItems = listState.layoutInfo.visibleItemsInfo
    if (visibleItems.isEmpty()) return

    val viewportWidth = listState.layoutInfo.viewportSize.width.toFloat().coerceAtLeast(1f)
    val pageStrokesMap = mutableMapOf<Int, MutableList<List<NormalizedPoint>>>()

    for (item in visibleItems) {
        val virtualIndex = item.index
        val spec = effectiveSpecs.getOrElse(virtualIndex) { PageEditSpec(virtualIndex, 0) }
        val originalPageIndex = spec.originalPageIndex

        val hasDivider = virtualIndex < effectiveSpecs.size - 1
        val itemDividerPx = if (hasDivider) dividerHeightPx else 0f
        val contentTop = item.offset.toFloat()
        val contentBottom = item.offset.toFloat() + item.size.toFloat() - itemDividerPx
        val contentHeightPx = (contentBottom - contentTop).coerceAtLeast(1f)

        // 裁切在当前页面可视内容垂直范围内的笔划段
        val segmentsInPage = clipStrokeToYRange(rawOffsets, contentTop, contentBottom)
        if (segmentsInPage.isEmpty()) continue

        val cropBounds = if (uiState.isAutoCropEnabled) {
            viewModel.getCachedCropBounds(originalPageIndex)
        } else {
            PageCropper2.CropBounds.FULL
        }
        val activeCrop = uiState.activeColumnBounds ?: cropBounds

        for (segment in segmentsInPage) {
            val normalizedPoints = segment.map { pt ->
                val localX = pt.x
                val localY = (pt.y - contentTop).coerceIn(0f, contentHeightPx)

                val normX = (activeCrop.left + (localX / viewportWidth) * activeCrop.width).coerceIn(0f, 1f)
                val normY = (activeCrop.top + (localY / contentHeightPx) * activeCrop.height).coerceIn(0f, 1f)
                NormalizedPoint(normX, normY)
            }.distinct()

            if (normalizedPoints.isNotEmpty()) {
                pageStrokesMap.getOrPut(originalPageIndex) { mutableListOf() }.add(normalizedPoints)
            }
        }
    }

    if (pageStrokesMap.isNotEmpty()) {
        viewModel.addMultiPageInkAnnotation(pageStrokesMap, isHighlighter)
    }
}

/**
 * 视口绝对坐标触控擦除分发
 */
private fun eraseAtViewportOffset(
    offset: Offset,
    listState: LazyListState,
    effectiveSpecs: List<PageEditSpec>,
    uiState: ViewerUiState,
    viewModel: ViewerViewModel,
    dividerHeightPx: Float
) {
    val visibleItems = listState.layoutInfo.visibleItemsInfo
    val item = visibleItems.firstOrNull { offset.y >= it.offset && offset.y < it.offset + it.size } ?: return
    val spec = effectiveSpecs.getOrElse(item.index) { PageEditSpec(item.index, 0) }
    val originalPageIndex = spec.originalPageIndex

    val hasDivider = item.index < effectiveSpecs.size - 1
    val itemDividerPx = if (hasDivider) dividerHeightPx else 0f
    val contentTop = item.offset.toFloat()
    val contentHeightPx = (item.size.toFloat() - itemDividerPx).coerceAtLeast(1f)
    if (offset.y > contentTop + contentHeightPx) return
    val viewportWidth = listState.layoutInfo.viewportSize.width.toFloat().coerceAtLeast(1f)

    val localX = offset.x
    val localY = (offset.y - contentTop).coerceIn(0f, contentHeightPx)

    val cropBounds = if (uiState.isAutoCropEnabled) {
        viewModel.getCachedCropBounds(originalPageIndex)
    } else {
        PageCropper2.CropBounds.FULL
    }
    val activeCrop = uiState.activeColumnBounds ?: cropBounds

    val normX = (activeCrop.left + (localX / viewportWidth) * activeCrop.width).coerceIn(0f, 1f)
    val normY = (activeCrop.top + (localY / contentHeightPx) * activeCrop.height).coerceIn(0f, 1f)

    viewModel.eraseAnnotationAt(originalPageIndex, NormalizedPoint(normX, normY))
}

/**
 * 将任意笔划点序列沿垂直区间 [yMin, yMax] 进行精确几何裁剪，并在跨界处精确插值边界交点，保证跨页笔画无缝衔接
 */
private fun clipStrokeToYRange(
    rawPoints: List<Offset>,
    yMin: Float,
    yMax: Float
): List<List<Offset>> {
    if (rawPoints.isEmpty()) return emptyList()
    if (rawPoints.size == 1) {
        val p = rawPoints[0]
        return if (p.y in yMin..yMax) listOf(listOf(p)) else emptyList()
    }

    val strokes = mutableListOf<MutableList<Offset>>()
    var currentStroke = mutableListOf<Offset>()

    for (i in 0 until rawPoints.size - 1) {
        val p1 = rawPoints[i]
        val p2 = rawPoints[i + 1]

        val p1In = p1.y in yMin..yMax
        val p2In = p2.y in yMin..yMax

        if (p1In && p2In) {
            if (currentStroke.isEmpty()) currentStroke.add(p1)
            currentStroke.add(p2)
        } else if (p1In && !p2In) {
            if (currentStroke.isEmpty()) currentStroke.add(p1)
            val targetY = if (p2.y > yMax) yMax else yMin
            val dy = p2.y - p1.y
            if (dy != 0f) {
                val t = (targetY - p1.y) / dy
                val interX = p1.x + t * (p2.x - p1.x)
                currentStroke.add(Offset(interX, targetY))
            }
            strokes.add(currentStroke)
            currentStroke = mutableListOf()
        } else if (!p1In && p2In) {
            val targetY = if (p1.y > yMax) yMax else yMin
            val dy = p2.y - p1.y
            if (dy != 0f) {
                val t = (targetY - p1.y) / dy
                val interX = p1.x + t * (p2.x - p1.x)
                currentStroke.add(Offset(interX, targetY))
            }
            currentStroke.add(p2)
        } else {
            val minY = min(p1.y, p2.y)
            val maxY = max(p1.y, p2.y)
            if (minY < yMin && maxY > yMax && (p2.y - p1.y) != 0f) {
                val dy = p2.y - p1.y
                val t1 = (yMin - p1.y) / dy
                val t2 = (yMax - p1.y) / dy
                val enterT = if (p1.y < p2.y) t1 else t2
                val exitT = if (p1.y < p2.y) t2 else t1
                val enterY = if (p1.y < p2.y) yMin else yMax
                val exitY = if (p1.y < p2.y) yMax else yMin
                val enterX = p1.x + enterT * (p2.x - p1.x)
                val exitX = p1.x + exitT * (p2.x - p1.x)
                strokes.add(mutableListOf(Offset(enterX, enterY), Offset(exitX, exitY)))
            }
        }
    }

    if (currentStroke.isNotEmpty()) {
        strokes.add(currentStroke)
    }

    return strokes
}

/**
 * 屏幕像素级实时贝塞尔平滑曲线生成
 */
private fun buildOverlayBezierPath(pts: List<Offset>): Path {
    val path = Path()
    if (pts.isEmpty()) return path
    if (pts.size == 1) {
        val p = pts[0]
        path.moveTo(p.x, p.y)
        path.lineTo(p.x + 0.1f, p.y + 0.1f)
        return path
    }

    path.moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) {
        val prev = pts[i - 1]
        val curr = pts[i]
        val midX = (prev.x + curr.x) / 2f
        val midY = (prev.y + curr.y) / 2f
        path.quadraticTo(prev.x, prev.y, midX, midY)
    }
    path.lineTo(pts.last().x, pts.last().y)
    return path
}
