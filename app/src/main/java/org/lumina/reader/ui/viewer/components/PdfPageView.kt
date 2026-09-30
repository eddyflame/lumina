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
    }

    val pageModifier = if (layoutMode == ReadingLayoutMode.SINGLE_PAGE_HORIZONTAL) {
        Modifier.fillMaxSize()
    } else {
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
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
                .clip(RoundedCornerShape(4.dp))
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
                val canvasDrawingModifier = if (activeTool == AnnotationTool.PEN || activeTool == AnnotationTool.HIGHLIGHTER) {
                    Modifier.pointerInput(pageIndex, activeTool, annotationColor, annotationStrokeWidthDp) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val pt = PageCoordinateTransformer.canvasToNormalized(
                                    offset.x, offset.y, size.width.toFloat(), size.height.toFloat()
                                )
                                activeStrokePoints = listOf(pt)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val pt = PageCoordinateTransformer.canvasToNormalized(
                                    change.position.x, change.position.y, size.width.toFloat(), size.height.toFloat()
                                )
                                activeStrokePoints = activeStrokePoints + pt
                            },
                            onDragEnd = {
                                if (activeStrokePoints.isNotEmpty()) {
                                    onAddInkAnnotation(
                                        pageIndex,
                                        listOf(activeStrokePoints),
                                        activeTool == AnnotationTool.HIGHLIGHTER
                                    )
                                    activeStrokePoints = emptyList()
                                }
                            },
                            onDragCancel = {
                                activeStrokePoints = emptyList()
                            }
                        )
                    }
                } else if (activeTool == AnnotationTool.ERASER) {
                    Modifier.pointerInput(pageIndex) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val pt = PageCoordinateTransformer.canvasToNormalized(
                                    offset.x, offset.y, size.width.toFloat(), size.height.toFloat()
                                )
                                onEraseAnnotation(pageIndex, pt)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val pt = PageCoordinateTransformer.canvasToNormalized(
                                    change.position.x, change.position.y, size.width.toFloat(), size.height.toFloat()
                                )
                                onEraseAnnotation(pageIndex, pt)
                            }
                        )
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
