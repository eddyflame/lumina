package org.lumina.reader.ui.viewer

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.lumina.reader.core.crop.PageCropper2
import org.lumina.reader.core.model.ReadingColorMode
import org.lumina.reader.ui.theme.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    onBackToShelf: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 自动恢复至上次阅读位置
    LaunchedEffect(uiState.documentInfo) {
        val initialPage = uiState.documentInfo?.initialPage ?: 0
        if (initialPage > 0) {
            listState.scrollToItem(initialPage)
        }
    }

    // 监听当前滚动位置更新页码并记录历史
    LaunchedEffect(listState.firstVisibleItemIndex) {
        viewModel.onPageChanged(listState.firstVisibleItemIndex)
    }

    val backgroundColor = when (uiState.colorMode) {
        ReadingColorMode.NORMAL -> MaterialTheme.colorScheme.background
        ReadingColorMode.NIGHT_INVERT -> NightBackground
        ReadingColorMode.SEPIA -> SepiaBackground
    }

    val pageColorFilter = when (uiState.colorMode) {
        ReadingColorMode.NORMAL -> null
        ReadingColorMode.NIGHT_INVERT -> ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    -1f,  0f,  0f, 0f, 255f,
                     0f, -1f,  0f, 0f, 255f,
                     0f,  0f, -1f, 0f, 255f,
                     0f,  0f,  0f, 1f,   0f
                )
            )
        )
        ReadingColorMode.SEPIA -> ColorFilter.tint(
            Color(0xFF8D6E63),
            androidx.compose.ui.graphics.BlendMode.Multiply
        )
    }

    // 目录大纲抽屉容器
    ModalNavigationDrawer(
        drawerState = rememberDrawerState(
            initialValue = if (uiState.isOutlineDrawerOpen) DrawerValue.Open else DrawerValue.Closed,
            confirmValueChange = {
                viewModel.setOutlineDrawerOpen(it == DrawerValue.Open)
                true
            }
        ),
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    text = "目录大纲",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(16.dp)
                )
                HorizontalDivider()
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(uiState.outlines) { item ->
                        NavigationDrawerItem(
                            label = { Text(item.title, fontSize = 14.sp) },
                            selected = uiState.currentPageIndex == item.pageIndex,
                            onClick = {
                                viewModel.setOutlineDrawerOpen(false)
                                scope.launch {
                                    listState.animateScrollToItem(item.pageIndex)
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            viewModel.toggleOverlay()
                        }
                    )
                }
        ) {
            val docInfo = uiState.documentInfo
            if (docInfo == null || uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                // PDF 页面瀑布流
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    items(docInfo.pageCount) { pageIndex ->
                        PdfPageView(
                            pageIndex = pageIndex,
                            isAutoCrop = uiState.isAutoCropEnabled,
                            activeColumnBounds = uiState.activeColumnBounds,
                            colorFilter = pageColorFilter,
                            viewModel = viewModel
                        )
                    }
                }
            }

            // 双栏锁定提示横条
            if (uiState.activeColumnBounds != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 80.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    tonalElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "已锁定单栏聚焦阅读",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "取消单栏聚焦",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .clickable { viewModel.clearColumnFocus() }
                        )
                    }
                }
            }

            // 顶部沉浸式工具栏
            AnimatedVisibility(
                visible = uiState.isOverlayVisible,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    tonalElevation = 6.dp
                ) {
                    TopAppBar(
                        title = {
                            Text(
                                text = docInfo?.title ?: "Lumina Reader",
                                maxLines = 1,
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onBackToShelf) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回书架"
                                )
                            }
                        },
                        actions = {
                            // 目录大纲抽屉唤起
                            IconButton(onClick = { viewModel.setOutlineDrawerOpen(true) }) {
                                Icon(
                                    Icons.Default.FormatListBulleted,
                                    contentDescription = "目录大纲"
                                )
                            }

                            // 智能白边裁切开关
                            FilledTonalIconToggleButton(
                                checked = uiState.isAutoCropEnabled,
                                onCheckedChange = { viewModel.toggleAutoCrop() }
                            ) {
                                Icon(
                                    Icons.Default.Crop,
                                    contentDescription = "智能白边裁切",
                                    tint = if (uiState.isAutoCropEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // 色彩模式切换
                            IconButton(onClick = {
                                val nextMode = when (uiState.colorMode) {
                                    ReadingColorMode.NORMAL -> ReadingColorMode.NIGHT_INVERT
                                    ReadingColorMode.NIGHT_INVERT -> ReadingColorMode.SEPIA
                                    ReadingColorMode.SEPIA -> ReadingColorMode.NORMAL
                                }
                                viewModel.setColorMode(nextMode)
                            }) {
                                Icon(
                                    when (uiState.colorMode) {
                                        ReadingColorMode.NORMAL -> Icons.Default.LightMode
                                        ReadingColorMode.NIGHT_INVERT -> Icons.Default.DarkMode
                                        ReadingColorMode.SEPIA -> Icons.Default.MenuBook
                                    },
                                    contentDescription = "阅读色彩模式"
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                    )
                }
            }

            // 底部悬浮控制台与翻页滑块
            AnimatedVisibility(
                visible = uiState.isOverlayVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    tonalElevation = 8.dp,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        val pageCount = docInfo?.pageCount ?: 1
                        val currentIdx = uiState.currentPageIndex

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${currentIdx + 1} / $pageCount",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )

                            if (uiState.isAutoCropEnabled) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = "Auto-Crop 2.0 ON",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Slider(
                            value = currentIdx.toFloat(),
                            onValueChange = { targetPage ->
                                val page = targetPage.roundToInt().coerceIn(0, pageCount - 1)
                                scope.launch {
                                    listState.scrollToItem(page)
                                }
                            },
                            valueRange = 0f..(pageCount - 1).toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PdfPageView(
    pageIndex: Int,
    isAutoCrop: Boolean,
    activeColumnBounds: PageCropper2.CropBounds?,
    colorFilter: ColorFilter?,
    viewModel: ViewerViewModel
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }

    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cropBounds by remember { mutableStateOf(PageCropper2.CropBounds.FULL) }

    // 缩放手势状态
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(pageIndex) {
        val info = viewModel.getPageInfo(pageIndex)
        val renderW = (screenWidthPx * 1.5f).toInt()
        val aspectRatio = info.width.toFloat() / info.height.toFloat()
        val renderH = (renderW / aspectRatio).roundToInt()

        pageBitmap = viewModel.renderPage(pageIndex, renderW, renderH)
        cropBounds = viewModel.getCropBounds(pageIndex)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        // 双击智能定位分栏 (论文双栏一键聚焦)
                        val tapX = (offset.x / size.width).coerceIn(0f, 1f)
                        val tapY = (offset.y / size.height).coerceIn(0f, 1f)
                        if (activeColumnBounds != null) {
                            viewModel.clearColumnFocus()
                        } else {
                            viewModel.focusColumnAt(pageIndex, tapX, tapY)
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
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
            // 计算视口裁切与分栏聚焦范围
            val activeCrop = when {
                activeColumnBounds != null -> activeColumnBounds
                isAutoCrop -> cropBounds
                else -> PageCropper2.CropBounds.FULL
            }

            val contentWidthRatio = activeCrop.width.coerceAtLeast(0.2f)
            val zoomFactor = if (isAutoCrop || activeColumnBounds != null) 1f / contentWidthRatio else 1f

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .graphicsLayer {
                        if (isAutoCrop || activeColumnBounds != null) {
                            scaleX = zoomFactor
                            scaleY = zoomFactor
                            val centerShiftX = ((activeCrop.left + activeCrop.right) / 2f - 0.5f) * size.width
                            val centerShiftY = ((activeCrop.top + activeCrop.bottom) / 2f - 0.5f) * size.height
                            translationX = -centerShiftX * zoomFactor
                            translationY = -centerShiftY * zoomFactor
                        }
                    }
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    contentScale = ContentScale.FillWidth,
                    colorFilter = colorFilter,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp)
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
