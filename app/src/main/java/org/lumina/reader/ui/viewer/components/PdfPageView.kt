package org.lumina.reader.ui.viewer.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.lumina.reader.core.annotation.AnnotationTool
import org.lumina.reader.core.annotation.NormalizedPoint
import org.lumina.reader.core.annotation.PageCoordinateTransformer
import org.lumina.reader.core.annotation.PdfAnnotation
import org.lumina.reader.core.crop.PageCropper2
import org.lumina.reader.core.model.ReadingLayoutMode
import org.lumina.reader.ui.viewer.ViewerViewModel
import kotlin.math.roundToInt

/**
 * 现代分层 PDF 单页渲染容器
 *
 * Layer 1: 原生光栅化位图渲染层 + 柔和/AMOLED 色彩映射
 * Layer 2: 文本与选区层 (预留文本划词与高亮)
 * Layer 3: 矢量注释与手写层 (Compose Canvas + 贝塞尔平滑 + 正片叠底荧光笔)
 * Layer 4: 触控交互与工具分流层 (画笔拦截 vs 手势翻页缩放)
 */
@Composable
fun PdfPageView(
    pageIndex: Int,
    pageCount: Int = 0,
    rotationDegrees: Int = 0,
    isAutoCrop: Boolean,
    activeColumnBounds: PageCropper2.CropBounds?,
    colorFilter: ColorFilter?,
    viewModel: ViewerViewModel,
    layoutMode: ReadingLayoutMode,
    activeTool: AnnotationTool,
    annotationColor: Long,
    annotationStrokeWidthDp: Float,
    pageAnnotations: List<PdfAnnotation>,
    onAddInkAnnotation: (pageIndex: Int, strokes: List<List<NormalizedPoint>>, isHighlighter: Boolean) -> Unit,
    onEraseAnnotation: (pageIndex: Int, point: NormalizedPoint) -> Unit,
    onTap: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }

    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cropBounds by remember { mutableStateOf(PageCropper2.CropBounds.FULL) }

    // 缩放手势状态
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // 当前正在手绘中的未闭合笔迹点序列
    var activeStrokePoints by remember { mutableStateOf<List<NormalizedPoint>>(emptyList()) }

    // 当页面索引、屏幕分辨率或排版模式变化时重新渲染高质量位图
    LaunchedEffect(pageIndex, screenWidthPx, screenHeightPx, layoutMode) {
        try {
            val info = viewModel.getPageInfo(pageIndex)
            val aspectRatio = info.width.toFloat() / info.height.toFloat()

            val renderW = if (layoutMode == ReadingLayoutMode.SINGLE_PAGE_HORIZONTAL) {
                val fitByWidthH = screenWidthPx / aspectRatio
                if (fitByWidthH <= screenHeightPx) {
                    (screenWidthPx * 1.5f).toInt()
                } else {
                    ((screenHeightPx * aspectRatio) * 1.5f).toInt()
                }
            } else {
                (screenWidthPx * 1.5f).toInt()
            }.coerceAtLeast(100)

            val renderH = (renderW / aspectRatio).roundToInt().coerceAtLeast(100)

            pageBitmap = viewModel.renderPage(pageIndex, renderW, renderH)
            cropBounds = viewModel.getCropBounds(pageIndex)
        } catch (_: Exception) {
            // 捕获并发重载或加载间隙的偶发异常，优雅降级，防止整个界面闪退
        }
    }

    val pageModifier = if (layoutMode == ReadingLayoutMode.SINGLE_PAGE_HORIZONTAL) {
        Modifier.fillMaxSize()
    } else {
        Modifier
            .fillMaxWidth()
            .padding(vertical = 0.dp)
    }

    // 根据当前是否处于注释模式构建手势拦截 Modifier
    val gestureModifier = if (activeTool == AnnotationTool.NONE) {
        Modifier
            .pointerInput(pageIndex) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { offset ->
                        if (scale > 1.05f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            val tapX = (offset.x / size.width).coerceIn(0f, 1f)
                            val tapY = (offset.y / size.height).coerceIn(0f, 1f)
                            if (activeColumnBounds != null) {
                                viewModel.clearColumnFocus()
                            } else {
                                viewModel.focusColumnAt(pageIndex, tapX, tapY)
                            }
                        }
                    }
                )
            }
            .pointerInput(pageIndex) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var pastTouchSlop = false
                    val touchSlop = viewConfiguration.touchSlop
                    var panAccumulated = Offset.Zero

                    do {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.size
                        if (pointerCount >= 2) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            scale = (scale * zoomChange).coerceIn(1f, 4f)
                            if (scale > 1f) {
                                val maxOffsetX = (size.width * (scale - 1f)) / 2f
                                val maxOffsetY = (size.height * (scale - 1f)) / 2f
                                offsetX = (offsetX + panChange.x).coerceIn(-maxOffsetX, maxOffsetX)
                                offsetY = (offsetY + panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                            event.changes.forEach { it.consume() }
                        } else if (scale > 1.05f) {
                            val panChange = event.calculatePan()
                            if (!pastTouchSlop) {
                                panAccumulated += panChange
                                if (panAccumulated.getDistance() > touchSlop) {
                                    pastTouchSlop = true
                                }
                            }
                            if (pastTouchSlop) {
                                val maxOffsetX = (size.width * (scale - 1f)) / 2f
                                val maxOffsetY = (size.height * (scale - 1f)) / 2f
                                offsetX = (offsetX + panChange.x).coerceIn(-maxOffsetX, maxOffsetX)
                                offsetY = (offsetY + panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
                                event.changes.forEach {
                                    if (it.positionChanged()) it.consume()
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (scale <= 1.05f) {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
    } else {
        // 注释模式：单指画线/擦除独占消费手势
        Modifier
    }

    Box(
        modifier = pageModifier
            .then(gestureModifier)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
            },
        contentAlignment = Alignment.Center
    ) {
        val bitmap = pageBitmap
        if (bitmap != null) {
            val activeCrop = when {
                activeColumnBounds != null -> activeColumnBounds
                isAutoCrop -> cropBounds
                else -> PageCropper2.CropBounds.FULL
            }

            val contentWidthRatio = activeCrop.width.coerceAtLeast(0.2f)
            val zoomFactor = if (isAutoCrop || activeColumnBounds != null) 1f / contentWidthRatio else 1f

            val contentBoxModifier = (if (layoutMode == ReadingLayoutMode.SINGLE_PAGE_HORIZONTAL) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .graphicsLayer {
                    if (rotationDegrees != 0) {
                        rotationZ = rotationDegrees.toFloat()
                    }
                    if (isAutoCrop || activeColumnBounds != null) {
                        scaleX = zoomFactor
                        scaleY = zoomFactor
                        val centerShiftX = ((activeCrop.left + activeCrop.right) / 2f - 0.5f) * size.width
                        val centerShiftY = ((activeCrop.top + activeCrop.bottom) / 2f - 0.5f) * size.height
                        translationX = -centerShiftX * zoomFactor
                        translationY = -centerShiftY * zoomFactor
                    }
                }

            Box(
                modifier = contentBoxModifier,
                contentAlignment = Alignment.Center
            ) {
                // Layer 1: PDF 位图底图
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    contentScale = if (layoutMode == ReadingLayoutMode.SINGLE_PAGE_HORIZONTAL) ContentScale.Fit else ContentScale.FillWidth,
                    colorFilter = colorFilter,
                    modifier = if (layoutMode == ReadingLayoutMode.SINGLE_PAGE_HORIZONTAL) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
                )

                // Layer 3: 矢量注释与交互绘制画布 (叠加于页面正上方，与页面同尺寸并同步缩放)
                var activeRawOffsets by remember { mutableStateOf<List<Offset>>(emptyList()) }
                val canvasDrawingModifier = if (activeTool == AnnotationTool.PEN || activeTool == AnnotationTool.HIGHLIGHTER) {
                    Modifier.pointerInput(pageIndex, pageCount, activeTool, annotationColor, annotationStrokeWidthDp) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                activeRawOffsets = listOf(offset)
                                val pt = PageCoordinateTransformer.canvasToNormalized(
                                    offset.x, offset.y, size.width.toFloat(), size.height.toFloat()
                                )
                                activeStrokePoints = listOf(pt)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                activeRawOffsets = activeRawOffsets + change.position
                                val pt = PageCoordinateTransformer.canvasToNormalized(
                                    change.position.x, change.position.y, size.width.toFloat(), size.height.toFloat()
                                )
                                activeStrokePoints = activeStrokePoints + pt
                            },
                            onDragEnd = {
                                commitCrossPageStroke(
                                    pageIndex = pageIndex,
                                    pageCount = pageCount,
                                    rawOffsets = activeRawOffsets,
                                    viewWidth = size.width.toFloat(),
                                    viewHeight = size.height.toFloat(),
                                    isHighlighter = activeTool == AnnotationTool.HIGHLIGHTER,
                                    onAddInkAnnotation = onAddInkAnnotation
                                )
                                activeRawOffsets = emptyList()
                                activeStrokePoints = emptyList()
                            },
                            onDragCancel = {
                                if (activeRawOffsets.isNotEmpty()) {
                                    commitCrossPageStroke(
                                        pageIndex = pageIndex,
                                        pageCount = pageCount,
                                        rawOffsets = activeRawOffsets,
                                        viewWidth = size.width.toFloat(),
                                        viewHeight = size.height.toFloat(),
                                        isHighlighter = activeTool == AnnotationTool.HIGHLIGHTER,
                                        onAddInkAnnotation = onAddInkAnnotation
                                    )
                                }
                                activeRawOffsets = emptyList()
                                activeStrokePoints = emptyList()
                            }
                        )
                    }
                } else if (activeTool == AnnotationTool.ERASER) {
                    Modifier.pointerInput(pageIndex, pageCount) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val startPt = PageCoordinateTransformer.canvasToNormalized(
                                down.position.x, down.position.y, w, h
                            )
                            onEraseAnnotation(pageIndex, startPt)

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.pressed) {
                                    change.consume()
                                    val currY = change.position.y
                                    val currX = change.position.x
                                    val targetPageIndex = when {
                                        currY < 0 && pageIndex > 0 -> pageIndex - 1
                                        currY > h && pageCount > 0 && pageIndex + 1 < pageCount -> pageIndex + 1
                                        else -> pageIndex
                                    }
                                    val normX = (currX / w).coerceIn(0f, 1f)
                                    val normY = when {
                                        currY < 0 && pageIndex > 0 -> (1f + currY / h).coerceIn(0f, 1f)
                                        currY > h && pageCount > 0 && pageIndex + 1 < pageCount -> ((currY - h) / h).coerceIn(0f, 1f)
                                        else -> (currY / h).coerceIn(0f, 1f)
                                    }
                                    onEraseAnnotation(targetPageIndex, NormalizedPoint(normX, normY))
                                } else {
                                    break
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }

                Canvas(
                    modifier = (if (layoutMode == ReadingLayoutMode.SINGLE_PAGE_HORIZONTAL) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(
                        with(density) { (bitmap.height / 1.5f).toDp() }
                    )).then(canvasDrawingModifier)
                ) {
                    val w = size.width
                    val h = size.height

                    // 1. 绘制已提交的历史注释笔迹
                    for (annotation in pageAnnotations) {
                        if (annotation is PdfAnnotation.Ink) {
                            val strokeColor = if (annotation.isHighlighter) {
                                Color(annotation.color).copy(alpha = 0.40f)
                            } else {
                                Color(annotation.color)
                            }
                            val blendMode = if (annotation.isHighlighter) BlendMode.Multiply else BlendMode.SrcOver
                            val strokeWidthPx = annotation.strokeWidthDp * density.density

                            for (stroke in annotation.strokes) {
                                val path = buildSmoothBezierPath(stroke, w, h)
                                drawPath(
                                    path = path,
                                    color = strokeColor,
                                    style = Stroke(
                                        width = strokeWidthPx,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    ),
                                    blendMode = blendMode
                                )
                            }
                        }
                    }

                    // 2. 绘制当前正在手绘中的活跃笔迹
                    if (activeStrokePoints.isNotEmpty()) {
                        val liveColor = if (activeTool == AnnotationTool.HIGHLIGHTER) {
                            Color(annotationColor).copy(alpha = 0.40f)
                        } else {
                            Color(annotationColor)
                        }
                        val liveBlendMode = if (activeTool == AnnotationTool.HIGHLIGHTER) BlendMode.Multiply else BlendMode.SrcOver
                        val liveWidthPx = annotationStrokeWidthDp * density.density
                        val livePath = buildSmoothBezierPath(activeStrokePoints, w, h)

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
        } else {
            Box(
                modifier = (if (layoutMode == ReadingLayoutMode.SINGLE_PAGE_HORIZONTAL) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(480.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

/**
 * 将归一化点序列平滑插值为二阶贝塞尔样条曲线，消除手绘锯齿与折线感
 */
private fun buildSmoothBezierPath(pts: List<NormalizedPoint>, width: Float, height: Float): Path {
    val path = Path()
    if (pts.isEmpty()) return path
    if (pts.size == 1) {
        val x = pts[0].x * width
        val y = pts[0].y * height
        path.moveTo(x, y)
        path.lineTo(x + 0.1f, y + 0.1f)
        return path
    }

    path.moveTo(pts[0].x * width, pts[0].y * height)
    for (i in 1 until pts.size) {
        val prev = pts[i - 1]
        val curr = pts[i]
        val midX = ((prev.x + curr.x) / 2f) * width
        val midY = ((prev.y + curr.y) / 2f) * height
        path.quadraticTo(prev.x * width, prev.y * height, midX, midY)
    }
    val last = pts.last()
    path.lineTo(last.x * width, last.y * height)
    return path
}

/**
 * 跨页笔迹几何拆分与归一化分发：
 * 当用户在连续阅读模式下跨越页面接缝绘制时，平滑拆分为对应页面的矢量注释，保证不会断触或丢失笔画
 */
private fun commitCrossPageStroke(
    pageIndex: Int,
    pageCount: Int,
    rawOffsets: List<Offset>,
    viewWidth: Float,
    viewHeight: Float,
    isHighlighter: Boolean,
    onAddInkAnnotation: (pageIndex: Int, strokes: List<List<NormalizedPoint>>, isHighlighter: Boolean) -> Unit
) {
    if (rawOffsets.isEmpty() || viewWidth <= 0f || viewHeight <= 0f) return

    // 1. 本页点序列 (映射在 [0f, 1f] 归一化空间内)
    val currPagePoints = rawOffsets.map { pt ->
        val nx = (pt.x / viewWidth).coerceIn(0f, 1f)
        val ny = (pt.y / viewHeight).coerceIn(0f, 1f)
        NormalizedPoint(nx, ny)
    }.distinct()

    if (currPagePoints.isNotEmpty()) {
        onAddInkAnnotation(pageIndex, listOf(currPagePoints), isHighlighter)
    }

    // 2. 跨页延伸至下一页的点序列 (y >= viewHeight)
    if (pageCount > 0 && pageIndex + 1 < pageCount) {
        val nextRaw = rawOffsets.filter { it.y >= viewHeight }
        if (nextRaw.isNotEmpty()) {
            val nextPoints = nextRaw.map { pt ->
                val nx = (pt.x / viewWidth).coerceIn(0f, 1f)
                val ny = ((pt.y - viewHeight) / viewHeight).coerceIn(0f, 1f)
                NormalizedPoint(nx, ny)
            }.distinct()
            if (nextPoints.isNotEmpty()) {
                onAddInkAnnotation(pageIndex + 1, listOf(nextPoints), isHighlighter)
            }
        }
    }

    // 3. 跨页延伸至上一页的点序列 (y <= 0)
    if (pageIndex > 0) {
        val prevRaw = rawOffsets.filter { it.y <= 0f }
        if (prevRaw.isNotEmpty()) {
            val prevPoints = prevRaw.map { pt ->
                val nx = (pt.x / viewWidth).coerceIn(0f, 1f)
                val ny = (1f + pt.y / viewHeight).coerceIn(0f, 1f)
                NormalizedPoint(nx, ny)
            }.distinct()
            if (prevPoints.isNotEmpty()) {
                onAddInkAnnotation(pageIndex - 1, listOf(prevPoints), isHighlighter)
            }
        }
    }
}
