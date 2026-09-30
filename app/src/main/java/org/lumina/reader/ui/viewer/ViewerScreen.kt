package org.lumina.reader.ui.viewer

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch
import org.lumina.reader.core.crop.PageCropper2
import org.lumina.reader.core.model.ReadingColorMode
import org.lumina.reader.core.model.ReadingLayoutMode
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
            val window = activity?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
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

    // 页面跳转弹窗状态
    var showJumpDialog by remember { mutableStateOf(false) }
    var jumpTargetPageText by remember { mutableStateOf("") }

    // 更多菜单状态
    var isMenuOpen by remember { mutableStateOf(false) }

    // 文档详情弹窗状态
    var showDocInfoDialog by remember { mutableStateOf(false) }

    val backgroundColor = when (uiState.colorMode) {
        ReadingColorMode.NORMAL -> MaterialTheme.colorScheme.background
        ReadingColorMode.SOFT_DARK -> SoftDarkBackground
        ReadingColorMode.AMOLED_DARK -> NightBackground
    }

    val pageColorFilter = when (uiState.colorMode) {
        ReadingColorMode.NORMAL -> null
        ReadingColorMode.SOFT_DARK -> ColorFilter.colorMatrix(
            // 柔和暗色：白底(255)->#1E222B(约30,34,43)，黑字(0)->#D6DCE5(约214,220,229)
            // 消除纯黑纯白的刺眼眩光，大幅提高暗光环境下的文字可读性
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
            // 极暗纯黑：纯黑底色，高光字压制到柔和 204，消除刺目反差
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
            ModalDrawerSheet(
                modifier = Modifier.width(320.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "目录大纲",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "共 ${docInfo?.pageCount ?: 0} 页",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider()
                if (uiState.outlines.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "该文档未包含目录大纲",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "可使用底部滑块或跳转功能快速翻页",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(uiState.outlines) { item ->
                            NavigationDrawerItem(
                                label = {
                                    Text(
                                        text = item.title,
                                        fontSize = 14.sp,
                                        maxLines = 2,
                                        modifier = Modifier.padding(start = (item.level * 12).dp)
                                    )
                                },
                                badge = {
                                    Text(
                                        text = "P${item.pageIndex + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                selected = uiState.currentPageIndex == item.pageIndex,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    viewModel.onPageChanged(item.pageIndex)
                                    scope.launch {
                                        if (uiState.layoutMode == ReadingLayoutMode.CONTINUOUS_VERTICAL) {
                                            listState.animateScrollToItem(item.pageIndex)
                                        } else {
                                            pagerState.animateScrollToPage(item.pageIndex)
                                        }
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                            )
                        }
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
                                    onTap = { viewModel.toggleOverlay() }
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
                                    onTap = { viewModel.toggleOverlay() }
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
                            // 1. 目录大纲抽屉唤起按钮
                            ViewerActionButton(
                                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                                contentDescription = "目录大纲",
                                isActive = drawerState.isOpen || uiState.isOutlineDrawerOpen,
                                onClick = {
                                    scope.launch {
                                        if (drawerState.isOpen) drawerState.close() else drawerState.open()
                                    }
                                }
                            )

                            // 2. 智能切白边 (点击开启变成蓝色并带有背景圆角底，关闭恢复原色)
                            ViewerActionButton(
                                icon = Icons.Default.Crop,
                                contentDescription = if (uiState.isAutoCropEnabled) "已开启智能裁切" else "已关闭智能裁切",
                                isActive = uiState.isAutoCropEnabled,
                                onClick = { viewModel.toggleAutoCrop() }
                            )

                            // 3. 护眼暗色模式 (点击开启变成蓝色，关闭恢复原色)
                            ViewerActionButton(
                                icon = if (uiState.colorMode != ReadingColorMode.NORMAL) Icons.Default.DarkMode else Icons.Default.LightMode,
                                contentDescription = if (uiState.colorMode != ReadingColorMode.NORMAL) "退出暗色模式" else "护眼暗色模式",
                                isActive = uiState.colorMode != ReadingColorMode.NORMAL,
                                onClick = {
                                    val next = if (uiState.colorMode == ReadingColorMode.NORMAL) {
                                        ReadingColorMode.SOFT_DARK
                                    } else {
                                        ReadingColorMode.NORMAL
                                    }
                                    viewModel.setColorMode(next)
                                }
                            )

                            // 4. 全屏沉浸模式 (点击开启变成蓝色，关闭恢复原色)
                            ViewerActionButton(
                                icon = if (uiState.isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = if (uiState.isFullscreen) "退出全屏" else "全屏模式",
                                isActive = uiState.isFullscreen,
                                onClick = { viewModel.toggleFullscreen() }
                            )

                            // 5. 屏幕横屏模式 (点击开启变成蓝色，关闭恢复原色)
                            ViewerActionButton(
                                icon = if (uiState.isLandscape) Icons.Default.StayCurrentPortrait else Icons.Default.StayCurrentLandscape,
                                contentDescription = if (uiState.isLandscape) "恢复竖屏" else "横屏模式",
                                isActive = uiState.isLandscape,
                                onClick = { viewModel.toggleLandscape() }
                            )

                            // 6. 翻页排版切换 (横向单页翻页时变蓝，纵向瀑布流时恢复原色)
                            ViewerActionButton(
                                icon = if (uiState.layoutMode == ReadingLayoutMode.CONTINUOUS_VERTICAL) Icons.Default.SwapHoriz else Icons.Default.ViewAgenda,
                                contentDescription = if (uiState.layoutMode == ReadingLayoutMode.CONTINUOUS_VERTICAL) "切换横向翻页" else "切换纵向瀑布流",
                                isActive = uiState.layoutMode == ReadingLayoutMode.SINGLE_PAGE_HORIZONTAL,
                                onClick = { viewModel.toggleLayoutMode() }
                            )

                            // 7. 更多菜单
                            Box {
                                ViewerActionButton(
                                    icon = Icons.Default.MoreVert,
                                    contentDescription = "更多设置",
                                    isActive = isMenuOpen,
                                    onClick = { isMenuOpen = true }
                                )

                                DropdownMenu(
                                    expanded = isMenuOpen,
                                    onDismissRequest = { isMenuOpen = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(if (uiState.isAutoCropEnabled) "智能白边裁切 (已开启)" else "智能白边裁切 (已关闭)") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Crop,
                                                contentDescription = null,
                                                tint = if (uiState.isAutoCropEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        trailingIcon = if (uiState.isAutoCropEnabled) {
                                            { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                        } else null,
                                        onClick = {
                                            isMenuOpen = false
                                            viewModel.toggleAutoCrop()
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = { Text(if (uiState.isFullscreen) "退出全屏模式" else "全屏沉浸模式") },
                                        leadingIcon = {
                                            Icon(
                                                if (uiState.isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                                contentDescription = null,
                                                tint = if (uiState.isFullscreen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        trailingIcon = if (uiState.isFullscreen) {
                                            { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                        } else null,
                                        onClick = {
                                            isMenuOpen = false
                                            viewModel.toggleFullscreen()
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = { Text(if (uiState.isLandscape) "恢复竖屏模式" else "切换横屏阅读") },
                                        leadingIcon = {
                                            Icon(
                                                if (uiState.isLandscape) Icons.Default.StayCurrentPortrait else Icons.Default.StayCurrentLandscape,
                                                contentDescription = null,
                                                tint = if (uiState.isLandscape) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        trailingIcon = if (uiState.isLandscape) {
                                            { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                        } else null,
                                        onClick = {
                                            isMenuOpen = false
                                            viewModel.toggleLandscape()
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = {
                                            Text(if (uiState.layoutMode == ReadingLayoutMode.CONTINUOUS_VERTICAL) "排版: 横向单页翻页" else "排版: 纵向连续瀑布流")
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (uiState.layoutMode == ReadingLayoutMode.CONTINUOUS_VERTICAL) Icons.Default.SwapHoriz else Icons.Default.ViewAgenda,
                                                contentDescription = null,
                                                tint = if (uiState.layoutMode == ReadingLayoutMode.SINGLE_PAGE_HORIZONTAL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        trailingIcon = if (uiState.layoutMode == ReadingLayoutMode.SINGLE_PAGE_HORIZONTAL) {
                                            { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                        } else null,
                                        onClick = {
                                            isMenuOpen = false
                                            viewModel.toggleLayoutMode()
                                        }
                                    )

                                    HorizontalDivider()

                                    DropdownMenuItem(
                                        text = { Text("色彩: 常规白底") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.LightMode,
                                                contentDescription = null,
                                                tint = if (uiState.colorMode == ReadingColorMode.NORMAL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        trailingIcon = if (uiState.colorMode == ReadingColorMode.NORMAL) {
                                            { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                        } else null,
                                        onClick = {
                                            isMenuOpen = false
                                            viewModel.setColorMode(ReadingColorMode.NORMAL)
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = { Text("色彩: 柔和深色 (舒适护眼)") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.DarkMode,
                                                contentDescription = null,
                                                tint = if (uiState.colorMode == ReadingColorMode.SOFT_DARK) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        trailingIcon = if (uiState.colorMode == ReadingColorMode.SOFT_DARK) {
                                            { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                        } else null,
                                        onClick = {
                                            isMenuOpen = false
                                            viewModel.setColorMode(ReadingColorMode.SOFT_DARK)
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = { Text("色彩: 极暗纯黑 (AMOLED 省电)") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.DarkMode,
                                                contentDescription = null,
                                                tint = if (uiState.colorMode == ReadingColorMode.AMOLED_DARK) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        trailingIcon = if (uiState.colorMode == ReadingColorMode.AMOLED_DARK) {
                                            { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                        } else null,
                                        onClick = {
                                            isMenuOpen = false
                                            viewModel.setColorMode(ReadingColorMode.AMOLED_DARK)
                                        }
                                    )

                                    HorizontalDivider()

                                    DropdownMenuItem(
                                        text = { Text("跳转到指定页") },
                                        leadingIcon = { Icon(Icons.Default.Numbers, contentDescription = null) },
                                        onClick = {
                                            isMenuOpen = false
                                            jumpTargetPageText = (uiState.currentPageIndex + 1).toString()
                                            showJumpDialog = true
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = { Text("文档信息") },
                                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                        onClick = {
                                            isMenuOpen = false
                                            showDocInfoDialog = true
                                        }
                                    )
                                }
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
                        val currentIdx = uiState.currentPageIndex

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    jumpTargetPageText = (currentIdx + 1).toString()
                                    showJumpDialog = true
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "${currentIdx + 1} / $pageCount",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (uiState.isAutoCropEnabled) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.padding(end = 6.dp)
                                    ) {
                                        Text(
                                            text = "Auto-Crop ON",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = if (uiState.layoutMode == ReadingLayoutMode.CONTINUOUS_VERTICAL) "纵向连续" else "横向单页",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        if (pageCount > 1) {
                            Slider(
                                value = currentIdx.toFloat().coerceIn(0f, (pageCount - 1).toFloat()),
                                onValueChange = { targetPage ->
                                    val page = targetPage.roundToInt().coerceIn(0, pageCount - 1)
                                    viewModel.onPageChanged(page)
                                    scope.launch {
                                        if (uiState.layoutMode == ReadingLayoutMode.CONTINUOUS_VERTICAL) {
                                            listState.scrollToItem(page)
                                        } else {
                                            pagerState.scrollToPage(page)
                                        }
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

    // 页面跳转弹窗
    if (showJumpDialog) {
        val totalPages = docInfo?.pageCount ?: 1
        AlertDialog(
            onDismissRequest = { showJumpDialog = false },
            title = { Text("跳转页面") },
            text = {
                Column {
                    Text(
                        text = "请输入目标页码 (1 ~ $totalPages)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = jumpTargetPageText,
                        onValueChange = { input ->
                            jumpTargetPageText = input.filter { it.isDigit() }
                        },
                        label = { Text("页码") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                val target = jumpTargetPageText.toIntOrNull()
                                if (target != null && target in 1..totalPages) {
                                    val targetIndex = target - 1
                                    viewModel.onPageChanged(targetIndex)
                                    scope.launch {
                                        if (uiState.layoutMode == ReadingLayoutMode.CONTINUOUS_VERTICAL) {
                                            listState.scrollToItem(targetIndex)
                                        } else {
                                            pagerState.scrollToPage(targetIndex)
                                        }
                                    }
                                    showJumpDialog = false
                                }
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = jumpTargetPageText.toIntOrNull()
                        if (target != null && target in 1..totalPages) {
                            val targetIndex = target - 1
                            viewModel.onPageChanged(targetIndex)
                            scope.launch {
                                if (uiState.layoutMode == ReadingLayoutMode.CONTINUOUS_VERTICAL) {
                                    listState.scrollToItem(targetIndex)
                                } else {
                                    pagerState.scrollToPage(targetIndex)
                                }
                            }
                            showJumpDialog = false
                        }
                    }
                ) {
                    Text("跳转")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJumpDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 文档详情弹窗
    if (showDocInfoDialog && docInfo != null) {
        AlertDialog(
            onDismissRequest = { showDocInfoDialog = false },
            title = { Text("文档信息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("名称: ${docInfo.title}", style = MaterialTheme.typography.bodyMedium)
                    Text("总页数: ${docInfo.pageCount} 页", style = MaterialTheme.typography.bodyMedium)
                    Text("当前阅读: 第 ${uiState.currentPageIndex + 1} 页", style = MaterialTheme.typography.bodyMedium)
                    if (docInfo.fileSize > 0) {
                        val sizeMb = String.format("%.2f MB", docInfo.fileSize / (1024f * 1024f))
                        Text("文件大小: $sizeMb", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDocInfoDialog = false }) {
                    Text("确定")
                }
            }
        )
    }
}

@Composable
fun PdfPageView(
    pageIndex: Int,
    isAutoCrop: Boolean,
    activeColumnBounds: PageCropper2.CropBounds?,
    colorFilter: ColorFilter?,
    viewModel: ViewerViewModel,
    layoutMode: ReadingLayoutMode,
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

    Box(
        modifier = pageModifier
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
                            // 双指捏合缩放：缩放范围 1x ~ 4x，并消费手势防止外层滑动冲突
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
                            // 单指在已放大状态下拖动平移，消费手势
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
                        // scale == 1f 时单指滑动不消费，完全让渡给 LazyColumn / HorizontalPager 流畅滚动
                    } while (event.changes.any { it.pressed })

                    if (scale <= 1.05f) {
                        scale = 1f
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
                modifier = (if (layoutMode == ReadingLayoutMode.SINGLE_PAGE_HORIZONTAL) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
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
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    contentScale = if (layoutMode == ReadingLayoutMode.SINGLE_PAGE_HORIZONTAL) ContentScale.Fit else ContentScale.FillWidth,
                    colorFilter = colorFilter,
                    modifier = if (layoutMode == ReadingLayoutMode.SINGLE_PAGE_HORIZONTAL) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
                )
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
 * 阅读器顶部工具栏操作按钮
 * 支持激活态视觉变化：开启时图标高亮为主题品牌蓝 (Primary)，带有柔和圆形半透明蓝色底色；
 * 关闭时恢复低调的次要图标色，背景透明。支持平滑颜色过渡动画。
 */
@Composable
private fun ViewerActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant
    val activeBg = activeColor.copy(alpha = 0.16f)
    val inactiveBg = Color.Transparent

    val iconColor by animateColorAsState(
        targetValue = if (isActive) activeColor else inactiveColor,
        label = "viewer_icon_color"
    )
    val containerBg by animateColorAsState(
        targetValue = if (isActive) activeBg else inactiveBg,
        label = "viewer_btn_bg"
    )

    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(containerBg)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(20.dp)
        )
    }
}
