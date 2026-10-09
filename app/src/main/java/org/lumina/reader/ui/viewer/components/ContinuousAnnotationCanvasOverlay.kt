package org.lumina.reader.ui.viewer.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.lumina.reader.core.annotation.AnnotationColorThemeAdapter
import org.lumina.reader.core.annotation.AnnotationTool
import org.lumina.reader.core.model.ReadingColorMode
import org.lumina.reader.core.annotation.NormalizedPoint
import org.lumina.reader.core.annotation.PageCoordinateTransformer
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
    val configuration = LocalConfiguration.current
    val orientation = configuration.orientation
    val density = LocalDensity.current
    val activeTool = uiState.annotationTool
    val annotationColor = uiState.annotationColor
    val strokeWidthDp = uiState.annotationStrokeWidthDp
    val isHighlighter = activeTool == AnnotationTool.HIGHLIGHTER
    val dividerHeightPx = with(density) { 18.dp.toPx() }
    val eraserRadiusPx = with(density) { 12.dp.toPx() }

    var activeRawOffsets by remember(orientation) { mutableStateOf<List<Offset>>(emptyList()) }
    var eraserPosition by remember(orientation) { mutableStateOf<Offset?>(null) }

    val gestureModifier = Modifier.pointerInput(activeTool, annotationColor, strokeWidthDp, orientation) {
        if (activeTool == AnnotationTool.PEN || activeTool == AnnotationTool.HIGHLIGHTER) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                var isMultiTouch = false
                activeRawOffsets = listOf(down.position)

                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.size >= 2) {
                        isMultiTouch = true
                        activeRawOffsets = emptyList() // 丢弃多指手势产生的单笔画误触

                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = false)

                        val visibleItems = listState.layoutInfo.visibleItemsInfo
                        val item = visibleItems.firstOrNull {
                            centroid.y >= it.offset && centroid.y < it.offset + it.size
                        } ?: visibleItems.firstOrNull()

                        if (item != null) {
                            val spec = effectiveSpecs.getOrElse(item.index) { PageEditSpec(item.index, 0) }
                            val pageIndex = spec.originalPageIndex
                            val currentZoom = viewModel.getPageZoom(pageIndex)
                            val newScale = (currentZoom.scale * zoomChange).coerceIn(1f, 4f)
                            val vpWidth = listState.layoutInfo.viewportSize.width.toFloat().coerceAtLeast(1f)
                            val itemH = item.size.toFloat()
                            val maxOffsetX = (vpWidth * (newScale - 1f)) / 2f
                            val maxOffsetY = (itemH * (newScale - 1f)) / 2f

                            val newOffsetX = if (newScale > 1f) {
                                (currentZoom.offsetX + panChange.x).coerceIn(-maxOffsetX, maxOffsetX)
                            } else 0f
                            val newOffsetY = if (newScale > 1f) {
                                (currentZoom.offsetY + panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
                            } else 0f

                            viewModel.setPageZoom(pageIndex, newScale, newOffsetX, newOffsetY)
                        }
                        event.changes.forEach { it.consume() }
                    } else {
                        if (isMultiTouch) {
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        } else {
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.pressed) {
                                change.consume()
                                activeRawOffsets = activeRawOffsets + change.position
                            } else {
                                break
                            }
                        }
                    }
                }

                if (!isMultiTouch && activeRawOffsets.isNotEmpty()) {
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
                var isMultiTouch = false
                eraserPosition = down.position
                eraseAtViewportOffset(
                    offset = down.position,
                    listState = listState,
                    effectiveSpecs = effectiveSpecs,
                    uiState = uiState,
                    viewModel = viewModel,
                    dividerHeightPx = dividerHeightPx,
                    eraserRadiusPx = eraserRadiusPx
                )

                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.size >= 2) {
                        isMultiTouch = true
                        eraserPosition = null

                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = false)

                        val visibleItems = listState.layoutInfo.visibleItemsInfo
                        val item = visibleItems.firstOrNull {
                            centroid.y >= it.offset && centroid.y < it.offset + it.size
                        } ?: visibleItems.firstOrNull()

                        if (item != null) {
                            val spec = effectiveSpecs.getOrElse(item.index) { PageEditSpec(item.index, 0) }
                            val pageIndex = spec.originalPageIndex
                            val currentZoom = viewModel.getPageZoom(pageIndex)
                            val newScale = (currentZoom.scale * zoomChange).coerceIn(1f, 4f)
                            val vpWidth = listState.layoutInfo.viewportSize.width.toFloat().coerceAtLeast(1f)
                            val itemH = item.size.toFloat()
                            val maxOffsetX = (vpWidth * (newScale - 1f)) / 2f
                            val maxOffsetY = (itemH * (newScale - 1f)) / 2f

                            val newOffsetX = if (newScale > 1f) {
                                (currentZoom.offsetX + panChange.x).coerceIn(-maxOffsetX, maxOffsetX)
                            } else 0f
                            val newOffsetY = if (newScale > 1f) {
                                (currentZoom.offsetY + panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
                            } else 0f

                            viewModel.setPageZoom(pageIndex, newScale, newOffsetX, newOffsetY)
                        }
                        event.changes.forEach { it.consume() }
                    } else {
                        if (isMultiTouch) {
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        } else {
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.pressed) {
                                change.consume()
                                eraserPosition = change.position
                                eraseAtViewportOffset(
                                    offset = change.position,
                                    listState = listState,
                                    effectiveSpecs = effectiveSpecs,
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    dividerHeightPx = dividerHeightPx,
                                    eraserRadiusPx = eraserRadiusPx
                                )
                            } else {
                                break
                            }
                        }
                    }
                }
                eraserPosition = null
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .then(gestureModifier)
    ) {
        if (activeRawOffsets.isNotEmpty() && (activeTool == AnnotationTool.PEN || activeTool == AnnotationTool.HIGHLIGHTER)) {
            val colorMode = uiState.colorMode
            val baseColor = AnnotationColorThemeAdapter.resolveDisplayColor(annotationColor, colorMode)
            val liveColor = if (isHighlighter) {
                baseColor.copy(alpha = if (colorMode == ReadingColorMode.NORMAL) 0.40f else 0.55f)
            } else {
                baseColor
            }
            val liveBlendMode = if (isHighlighter) {
                AnnotationColorThemeAdapter.resolveHighlighterBlendMode(colorMode)
            } else {
                BlendMode.SrcOver
            }
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

        // 橡皮擦实时触控反馈光圈 (精细高对比度光圈，12.dp 半径防遮挡)
        if (activeTool == AnnotationTool.ERASER) {
            eraserPosition?.let { pos ->
                val radiusPx = 12.dp.toPx()
                // 半透明感应区
                drawCircle(
                    color = Color(0x33FF5252),
                    radius = radiusPx,
                    center = pos
                )
                // 边框描边
                drawCircle(
                    color = Color(0xEEFF5252),
                    radius = radiusPx,
                    center = pos,
                    style = Stroke(width = 1.5.dp.toPx())
                )
                // 精准中心触点
                drawCircle(
                    color = Color(0xFFFF1744),
                    radius = 2.dp.toPx(),
                    center = pos
                )
            }
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

    // 优先处理处于缩放状态的置顶页面
    val sortedItems = visibleItems.sortedByDescending { item ->
        val spec = effectiveSpecs.getOrElse(item.index) { PageEditSpec(item.index, 0) }
        val zoom = viewModel.getPageZoom(spec.originalPageIndex)
        if (zoom.scale > 1.05f) 1 else 0
    }

    for (item in sortedItems) {
        val virtualIndex = item.index
        val spec = effectiveSpecs.getOrElse(virtualIndex) { PageEditSpec(virtualIndex, 0) }
        val originalPageIndex = spec.originalPageIndex

        val hasDivider = virtualIndex < effectiveSpecs.size - 1
        val itemDividerPx = if (hasDivider) dividerHeightPx else 0f
        val contentTop = item.offset.toFloat()
        val contentHeightPx = (item.size.toFloat() - itemDividerPx).coerceAtLeast(1f)

        val zoom = viewModel.getPageZoom(originalPageIndex)
        val scale = zoom.scale.coerceAtLeast(0.1f)
        val offsetX = zoom.offsetX
        val offsetY = zoom.offsetY

        // 将视口绝对触控点全量逆向变换至当前页面未缩放的本地坐标系
        val localPoints = rawOffsets.map { pt ->
            val (lx, ly) = PageCoordinateTransformer.screenToLocal(
                viewportX = pt.x,
                viewportY = pt.y,
                itemOffsetX = contentTop,
                itemWidth = viewportWidth,
                itemHeight = item.size.toFloat(),
                scale = scale,
                offsetX = offsetX,
                offsetY = offsetY
            )
            Offset(lx, ly)
        }

        // 在页面本地坐标空间内按 [0f, contentHeightPx] 进行几何裁剪
        val segmentsInPage = clipStrokeToYRange(localPoints, 0f, contentHeightPx)
        if (segmentsInPage.isEmpty()) continue

        val cropBounds = if (uiState.isAutoCropEnabled) {
            viewModel.getCachedCropBounds(originalPageIndex) ?: PageCropper2.CropBounds.FULL
        } else {
            PageCropper2.CropBounds.FULL
        }
        val activeCrop = if (uiState.activeColumnBounds != null && uiState.activeColumnPageIndex == originalPageIndex) {
            uiState.activeColumnBounds
        } else {
            cropBounds
        }

        for (segment in segmentsInPage) {
            val normalizedPoints = segment.map { pt ->
                val rawLocalX = pt.x.coerceIn(0f, viewportWidth)
                val rawLocalY = pt.y.coerceIn(0f, contentHeightPx)

                val (localX, localY) = PageCoordinateTransformer.unrotatePoint(
                    rawLocalX,
                    rawLocalY,
                    viewportWidth,
                    contentHeightPx,
                    spec.normalizedRotation
                )

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
    dividerHeightPx: Float,
    eraserRadiusPx: Float
) {
    val visibleItems = listState.layoutInfo.visibleItemsInfo
    val viewportWidth = listState.layoutInfo.viewportSize.width.toFloat().coerceAtLeast(1f)

    // 优先命中缩放置顶图层
    val sortedItems = visibleItems.sortedByDescending { item ->
        val spec = effectiveSpecs.getOrElse(item.index) { PageEditSpec(item.index, 0) }
        val zoom = viewModel.getPageZoom(spec.originalPageIndex)
        if (zoom.scale > 1.05f) 1 else 0
    }

    for (item in sortedItems) {
        val virtualIndex = item.index
        val spec = effectiveSpecs.getOrElse(virtualIndex) { PageEditSpec(virtualIndex, 0) }
        val originalPageIndex = spec.originalPageIndex

        val hasDivider = virtualIndex < effectiveSpecs.size - 1
        val itemDividerPx = if (hasDivider) dividerHeightPx else 0f
        val contentTop = item.offset.toFloat()
        val contentHeightPx = (item.size.toFloat() - itemDividerPx).coerceAtLeast(1f)

        val zoom = viewModel.getPageZoom(originalPageIndex)
        val (localX, localY) = PageCoordinateTransformer.screenToLocal(
            viewportX = offset.x,
            viewportY = offset.y,
            itemOffsetX = contentTop,
            itemWidth = viewportWidth,
            itemHeight = item.size.toFloat(),
            scale = zoom.scale,
            offsetX = zoom.offsetX,
            offsetY = zoom.offsetY
        )

        if (localY in 0f..contentHeightPx && localX in -viewportWidth * 0.2f..viewportWidth * 1.2f) {
            val (unrotX, unrotY) = PageCoordinateTransformer.unrotatePoint(
                localX.coerceIn(0f, viewportWidth),
                localY.coerceIn(0f, contentHeightPx),
                viewportWidth,
                contentHeightPx,
                spec.normalizedRotation
            )

            val cropBounds = if (uiState.isAutoCropEnabled) {
                viewModel.getCachedCropBounds(originalPageIndex) ?: PageCropper2.CropBounds.FULL
            } else {
                PageCropper2.CropBounds.FULL
            }
            val activeCrop = if (uiState.activeColumnBounds != null && uiState.activeColumnPageIndex == originalPageIndex) {
                uiState.activeColumnBounds
            } else {
                cropBounds
            }

            val normX = (activeCrop.left + (unrotX / viewportWidth) * activeCrop.width).coerceIn(0f, 1f)
            val normY = (activeCrop.top + (unrotY / contentHeightPx) * activeCrop.height).coerceIn(0f, 1f)

            // 精准计算屏幕 12dp 物理光圈在当前页面缩放与高宽比下的各向同性碰撞判定阈值
            val scale = zoom.scale.coerceAtLeast(1f)
            val dynamicThreshold = (eraserRadiusPx / (viewportWidth * scale)).coerceIn(0.005f, 0.035f)
            val pageAspect = contentHeightPx / viewportWidth

            viewModel.eraseAnnotationAt(
                pageIndex = originalPageIndex,
                point = NormalizedPoint(normX, normY),
                threshold = dynamicThreshold,
                pageAspect = pageAspect
            )
            break
        }
    }
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
