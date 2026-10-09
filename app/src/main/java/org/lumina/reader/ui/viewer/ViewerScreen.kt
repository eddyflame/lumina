package org.lumina.reader.ui.viewer

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.lumina.reader.R
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.lumina.reader.core.annotation.AnnotationTool
import org.lumina.reader.core.model.PageEditSpec
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
    val configuration = LocalConfiguration.current
    val orientation = configuration.orientation
    val activity = context as? Activity

    // 屏幕旋转时重置页面临时缩放，防止边界错位
    LaunchedEffect(orientation) {
        viewModel.resetAllPageZooms()
    }

    val docInfo = uiState.documentInfo
    val effectiveSpecs = uiState.pageSpecs.ifEmpty {
        val total = docInfo?.pageCount ?: 1
        (0 until total).map { PageEditSpec(it, 0) }
    }
    val pageCount = effectiveSpecs.size.coerceAtLeast(1)

    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { targetUri ->
        if (targetUri != null) {
            viewModel.exportDocument(targetUri)
        }
    }

    // 监听只读回退或另存为事件，自动调起 SAF 保存选择器
    LaunchedEffect(Unit) {
        viewModel.triggerSaveAsEvent.receiveAsFlow().collect { suggestedName ->
            saveAsLauncher.launch(suggestedName)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.saveUserMessage) {
        uiState.saveUserMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSaveMessage()
        }
    }

    // 页面组织与编辑全屏工作台
    if (uiState.isPageOrganizerOpen) {
        PageOrganizerScreen(
            pageSpecs = effectiveSpecs,
            currentPageIndex = uiState.currentPageIndex,
            viewModel = viewModel,
            onPageSelected = { virtualIndex ->
                viewModel.setPageOrganizerOpen(false)
                viewModel.onPageChanged(virtualIndex)
            },
            onRotatePage = { idx, deg -> viewModel.rotatePage(idx, deg) },
            onMovePage = { from, to -> viewModel.movePage(from, to) },
            onDeletePage = { idx -> viewModel.deletePage(idx) },
            onRotateAll = { deg -> viewModel.rotateAllPages(deg) },
            onResetAll = { viewModel.resetPageEdits() },
            onSave = {
                val defaultName = docInfo?.title?.let {
                    val base = if (it.endsWith(".pdf", ignoreCase = true)) it.dropLast(4) else it
                    "${base}_organized.pdf"
                } ?: "Document_organized.pdf"
                saveAsLauncher.launch(defaultName)
            },
            onClose = { viewModel.setPageOrganizerOpen(false) }
        )
        return
    }

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
    var showAboutDialog by remember { mutableStateOf(false) }

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
                        // 连续纵向瀑布流阅读：注释模式下禁用 LazyColumn 拦截滚动，保障单指画线不被中断断触
                        Box(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                userScrollEnabled = uiState.annotationTool == AnnotationTool.NONE,
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                items(
                                    count = pageCount,
                                    key = { index -> effectiveSpecs.getOrNull(index)?.originalPageIndex ?: index }
                                ) { virtualIndex ->
                                    val spec = effectiveSpecs.getOrElse(virtualIndex) { PageEditSpec(virtualIndex, 0) }
                                    PdfPageView(
                                        pageIndex = spec.originalPageIndex,
                                        pageCount = pageCount,
                                        rotationDegrees = spec.normalizedRotation,
                                        isAutoCrop = uiState.isAutoCropEnabled,
                                        activeColumnBounds = uiState.activeColumnBounds,
                                        activeColumnPageIndex = uiState.activeColumnPageIndex,
                                        colorMode = uiState.colorMode,
                                        colorFilter = pageColorFilter,
                                        viewModel = viewModel,
                                        layoutMode = uiState.layoutMode,
                                        activeTool = uiState.annotationTool,
                                        annotationColor = uiState.annotationColor,
                                        annotationStrokeWidthDp = uiState.annotationStrokeWidthDp,
                                        pageAnnotations = uiState.annotations[spec.originalPageIndex] ?: emptyList(),
                                        showDivider = virtualIndex < pageCount - 1,
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

                            if (uiState.annotationTool != AnnotationTool.NONE) {
                                ContinuousAnnotationCanvasOverlay(
                                    listState = listState,
                                    effectiveSpecs = effectiveSpecs,
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                    ReadingLayoutMode.SINGLE_PAGE_HORIZONTAL -> {
                        // 横向单页左右滑动翻页
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize()
                        ) { virtualIndex ->
                            val spec = effectiveSpecs.getOrElse(virtualIndex) { PageEditSpec(virtualIndex, 0) }
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                PdfPageView(
                                    pageIndex = spec.originalPageIndex,
                                    pageCount = pageCount,
                                    rotationDegrees = spec.normalizedRotation,
                                    isAutoCrop = uiState.isAutoCropEnabled,
                                    activeColumnBounds = uiState.activeColumnBounds,
                                    activeColumnPageIndex = uiState.activeColumnPageIndex,
                                    colorMode = uiState.colorMode,
                                    colorFilter = pageColorFilter,
                                    viewModel = viewModel,
                                    layoutMode = uiState.layoutMode,
                                    activeTool = uiState.annotationTool,
                                    annotationColor = uiState.annotationColor,
                                    annotationStrokeWidthDp = uiState.annotationStrokeWidthDp,
                                    pageAnnotations = uiState.annotations[spec.originalPageIndex] ?: emptyList(),
                                    showDivider = false,
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
                            text = stringResource(R.string.column_focus_locked),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.unlock_column_focus),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .clickable { viewModel.clearColumnFocus() }
                        )
                    }
                }
            }

            // 顶部沉浸式工具栏 (仅在非注释模式且 Overlay 可见时展示)
            AnimatedVisibility(
                visible = uiState.isOverlayVisible && uiState.annotationTool == AnnotationTool.NONE,
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
                    onOpenPageOrganizer = { viewModel.setPageOrganizerOpen(true) },
                    onShowJumpDialog = { showJumpDialog = true },
                    onShowDocInfoDialog = { showDocInfoDialog = true },
                    onShowAboutDialog = { showAboutDialog = true },
                    onSaveAsDocument = {
                        val defaultName = docInfo?.title?.let {
                            val base = if (it.endsWith(".pdf", ignoreCase = true)) it.dropLast(4) else it
                            "${base}_edited.pdf"
                        } ?: "Document_edited.pdf"
                        saveAsLauncher.launch(defaultName)
                    }
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
                colorMode = uiState.colorMode,
                canUndo = uiState.canUndoAnnotation,
                canRedo = uiState.canRedoAnnotation,
                onToolChange = { viewModel.setAnnotationTool(it) },
                onColorChange = { viewModel.setAnnotationColor(it) },
                onStrokeWidthChange = { viewModel.setAnnotationStrokeWidth(it) },
                onUndo = { viewModel.undoAnnotation() },
                onRedo = { viewModel.redoAnnotation() },
                onClearPage = { viewModel.clearAllAnnotationsForPage(effectiveSpecs[uiState.currentPageIndex.coerceIn(0, pageCount - 1)].originalPageIndex) },
                onClose = { viewModel.setAnnotationTool(AnnotationTool.NONE) },
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            // 全局轻量通知提示条
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp)
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

    // 软件关于信息弹窗
    if (showAboutDialog) {
        AboutAppDialog(
            onDismiss = { showAboutDialog = false }
        )
    }
}
