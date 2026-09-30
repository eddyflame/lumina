package org.lumina.reader.ui.viewer

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch
import org.lumina.reader.core.annotation.AnnotationTool
import org.lumina.reader.core.model.ReadingColorMode
import org.lumina.reader.core.model.ReadingLayoutMode
import org.lumina.reader.ui.theme.NightBackground
import org.lumina.reader.ui.theme.SoftDarkBackground
import org.lumina.reader.ui.viewer.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    onBackToShelf: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? Activity

    val docInfo = uiState.documentInfo
    val pageCount = docInfo?.pageCount ?: 1

    val listState = rememberLazyListState()
    val pagerState = rememberPagerState(
        initialPage = uiState.currentPageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
        pageCount = { pageCount }
    )

    // 全屏沉浸式模式控制：隐藏/展示系统状态栏与导航栏
    DisposableEffect(uiState.isFullscreen) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (uiState.isFullscreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            activity?.window?.let { w ->
                val controller = WindowCompat.getInsetsController(w, w.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 屏幕横竖屏旋转控制
    DisposableEffect(uiState.isLandscape) {
        activity?.requestedOrientation = if (uiState.isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 自动恢复至上次阅读位置
    LaunchedEffect(docInfo) {
        val initialPage = docInfo?.initialPage ?: 0
        if (initialPage > 0) {
            listState.scrollToItem(initialPage)
            if (initialPage < pageCount) {
                pagerState.scrollToPage(initialPage)
            }
        }
    }

    // 监听连续纵向滚动位置更新页码
    LaunchedEffect(listState.firstVisibleItemIndex) {
        if (uiState.layoutMode == ReadingLayoutMode.CONTINUOUS_VERTICAL && !uiState.isLoading) {
            viewModel.onPageChanged(listState.firstVisibleItemIndex)
        }
    }

    // 监听横向单页左右翻页位置更新页码
    LaunchedEffect(pagerState.currentPage) {
        if (uiState.layoutMode == ReadingLayoutMode.SINGLE_PAGE_HORIZONTAL && !uiState.isLoading) {
            viewModel.onPageChanged(pagerState.currentPage)
        }
    }

    // 模式切换时平滑同步当前页
    LaunchedEffect(uiState.layoutMode) {
        val current = uiState.currentPageIndex
        if (uiState.layoutMode == ReadingLayoutMode.CONTINUOUS_VERTICAL) {
            if (listState.firstVisibleItemIndex != current) {
                listState.scrollToItem(current)
            }
        } else {
            if (pagerState.currentPage != current && current < pageCount) {
                pagerState.scrollToPage(current)
            }
        }
    }

    // 目录大纲抽屉状态绑定
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    LaunchedEffect(uiState.isOutlineDrawerOpen) {
        if (uiState.isOutlineDrawerOpen && drawerState.isClosed) {
            drawerState.open()
        } else if (!uiState.isOutlineDrawerOpen && drawerState.isOpen) {
            drawerState.close()
        }
    }
    LaunchedEffect(drawerState.isOpen) {
        if (uiState.isOutlineDrawerOpen != drawerState.isOpen) {
            viewModel.setOutlineDrawerOpen(drawerState.isOpen)
        }
    }

    // 弹窗状态
    var showJumpDialog by remember { mutableStateOf(false) }
    var showDocInfoDialog by remember { mutableStateOf(false) }

    // 细粒度预测性返回拦截
    BackHandler(enabled = true) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            uiState.annotationTool != AnnotationTool.NONE -> viewModel.setAnnotationTool(AnnotationTool.NONE)
            uiState.activeColumnBounds != null -> viewModel.clearColumnFocus()
            uiState.isFullscreen -> viewModel.setFullscreen(false)
            else -> onBackToShelf()
        }
    }

    // 统一页面跳转函数
    fun navigateToPage(targetIndex: Int, animate: Boolean = false) {
        val safeIndex = targetIndex.coerceIn(0, pageCount - 1)
        viewModel.onPageChanged(safeIndex)
        scope.launch {
            if (uiState.layoutMode == ReadingLayoutMode.CONTINUOUS_VERTICAL) {
                if (animate) listState.animateScrollToItem(safeIndex)
                else listState.scrollToItem(safeIndex)
            } else {
                if (animate) pagerState.animateScrollToPage(safeIndex)
                else pagerState.scrollToPage(safeIndex)
            }
        }
    }

    val backgroundColor = when (uiState.colorMode) {
        ReadingColorMode.NORMAL -> MaterialTheme.colorScheme.background
        ReadingColorMode.SOFT_DARK -> SoftDarkBackground
        ReadingColorMode.AMOLED_DARK -> NightBackground
    }

    val pageColorFilter = when (uiState.colorMode) {
        ReadingColorMode.NORMAL -> null
        ReadingColorMode.SOFT_DARK -> ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    -0.722f,  0f,      0f,      0f, 214f,
                     0f,     -0.729f,  0f,      0f, 220f,
                     0f,      0f,     -0.729f,  0f, 229f,
                     0f,      0f,      0f,      1f,   0f
                )
            )
        )
        ReadingColorMode.AMOLED_DARK -> ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    -0.80f,  0f,     0f,     0f, 204f,
                     0f,    -0.80f,  0f,     0f, 204f,
                     0f,     0f,    -0.80f,  0f, 204f,
                     0f,     0f,     0f,     1f,   0f
                )
            )
        )
    }

    // 目录大纲抽屉容器
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ViewerOutlineDrawerSheet(
                pageCount = docInfo?.pageCount ?: 0,
                currentPageIndex = uiState.currentPageIndex,
                outlines = uiState.outlines,
                onSelectOutline = { selectedPage ->
                    scope.launch { drawerState.close() }
                    navigateToPage(selectedPage, animate = true)
                }
            )
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .pointerInput(uiState.annotationTool) {
                    if (uiState.annotationTool == AnnotationTool.NONE) {
                        detectTapGestures(onTap = { viewModel.toggleOverlay() })
                    }
                }
        ) {
            if (docInfo == null || uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                when (uiState.layoutMode) {
                    ReadingLayoutMode.CONTINUOUS_VERTICAL -> {
                        // 连续纵向瀑布流阅读
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
                                    viewModel = viewModel,
                                    layoutMode = uiState.layoutMode,
                                    activeTool = uiState.annotationTool,
                                    annotationColor = uiState.annotationColor,
                                    annotationStrokeWidthDp = uiState.annotationStrokeWidthDp,
                                    pageAnnotations = uiState.annotations[pageIndex] ?: emptyList(),
                                    onAddInkAnnotation = { idx, strokes, isHighlighter ->
                                        viewModel.addInkAnnotation(idx, strokes, isHighlighter)
                                    },
                                    onEraseAnnotation = { idx, point ->
                                        viewModel.eraseAnnotationAt(idx, point)
                                    },
                                    onTap = {
                                        if (uiState.annotationTool == AnnotationTool.NONE) {
                                            viewModel.toggleOverlay()
                                        }
                                    }
                                )
                            }
                        }
                    }
                    ReadingLayoutMode.SINGLE_PAGE_HORIZONTAL -> {
                        // 横向单页左右滑动翻页
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize()
                        ) { pageIndex ->
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                PdfPageView(
                                    pageIndex = pageIndex,
                                    isAutoCrop = uiState.isAutoCropEnabled,
                                    activeColumnBounds = uiState.activeColumnBounds,
                                    colorFilter = pageColorFilter,
                                    viewModel = viewModel,
                                    layoutMode = uiState.layoutMode,
                                    activeTool = uiState.annotationTool,
                                    annotationColor = uiState.annotationColor,
                                    annotationStrokeWidthDp = uiState.annotationStrokeWidthDp,
                                    pageAnnotations = uiState.annotations[pageIndex] ?: emptyList(),
                                    onAddInkAnnotation = { idx, strokes, isHighlighter ->
                                        viewModel.addInkAnnotation(idx, strokes, isHighlighter)
                                    },
                                    onEraseAnnotation = { idx, point ->
                                        viewModel.eraseAnnotationAt(idx, point)
                                    },
                                    onTap = {
                                        if (uiState.annotationTool == AnnotationTool.NONE) {
                                            viewModel.toggleOverlay()
                                        }
                                    }
                                )
                            }
                        }
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
                ViewerTopBar(
                    uiState = uiState,
                    isDrawerOpen = drawerState.isOpen,
                    onBack = onBackToShelf,
                    onToggleDrawer = {
                        scope.launch {
                            if (drawerState.isOpen) drawerState.close() else drawerState.open()
                        }
                    },
                    onToggleAutoCrop = { viewModel.toggleAutoCrop() },
                    onToggleColorMode = {
                        val next = if (uiState.colorMode == ReadingColorMode.NORMAL) {
                            ReadingColorMode.SOFT_DARK
                        } else {
                            ReadingColorMode.NORMAL
                        }
                        viewModel.setColorMode(next)
                    },
                    onToggleAnnotationMode = {
                        val nextTool = if (uiState.annotationTool == AnnotationTool.NONE) {
                            AnnotationTool.PEN
                        } else {
                            AnnotationTool.NONE
                        }
                        viewModel.setAnnotationTool(nextTool)
                    },
                    onToggleFullscreen = { viewModel.toggleFullscreen() },
                    onToggleLandscape = { viewModel.toggleLandscape() },
                    onToggleLayoutMode = { viewModel.toggleLayoutMode() },
                    onSetColorMode = { viewModel.setColorMode(it) },
                    onShowJumpDialog = { showJumpDialog = true },
                    onShowDocInfoDialog = { showDocInfoDialog = true }
                )
            }

            // 底部常规控制台与翻页滑块 (仅在非注释模式且 Overlay 可见时展示)
            AnimatedVisibility(
                visible = uiState.isOverlayVisible && uiState.annotationTool == AnnotationTool.NONE,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                ViewerBottomBar(
                    currentPageIndex = uiState.currentPageIndex,
                    pageCount = pageCount,
                    isAutoCropEnabled = uiState.isAutoCropEnabled,
                    layoutMode = uiState.layoutMode,
                    onPageSelected = { navigateToPage(it) },
                    onShowJumpDialog = { showJumpDialog = true }
                )
            }

            // 底部悬浮注释工具条 (处于注释模式时常驻吸底)
            AnnotationToolbar(
                activeTool = uiState.annotationTool,
                currentColor = uiState.annotationColor,
                currentStrokeWidthDp = uiState.annotationStrokeWidthDp,
                canUndo = uiState.canUndoAnnotation,
                canRedo = uiState.canRedoAnnotation,
                onToolChange = { viewModel.setAnnotationTool(it) },
                onColorChange = { viewModel.setAnnotationColor(it) },
                onStrokeWidthChange = { viewModel.setAnnotationStrokeWidth(it) },
                onUndo = { viewModel.undoAnnotation() },
                onRedo = { viewModel.redoAnnotation() },
                onClearPage = { viewModel.clearAllAnnotationsForPage(uiState.currentPageIndex) },
                onClose = { viewModel.setAnnotationTool(AnnotationTool.NONE) },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    // 页面跳转弹窗
    if (showJumpDialog) {
        JumpPageDialog(
            totalPages = pageCount,
            initialTargetText = (uiState.currentPageIndex + 1).toString(),
            onDismiss = { showJumpDialog = false },
            onConfirm = { targetIndex ->
                navigateToPage(targetIndex)
                showJumpDialog = false
            }
        )
    }

    // 文档详情弹窗
    if (showDocInfoDialog && docInfo != null) {
        DocInfoDialog(
            docInfo = docInfo,
            currentPageIndex = uiState.currentPageIndex,
            onDismiss = { showDocInfoDialog = false }
        )
    }
}
