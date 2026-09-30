package org.lumina.reader.ui.viewer.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.lumina.reader.core.annotation.AnnotationTool
import org.lumina.reader.core.model.ReadingColorMode
import org.lumina.reader.core.model.ReadingLayoutMode
import org.lumina.reader.ui.viewer.ViewerUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerTopBar(
    uiState: ViewerUiState,
    isDrawerOpen: Boolean,
    onBack: () -> Unit,
    onToggleDrawer: () -> Unit,
    onToggleAutoCrop: () -> Unit,
    onToggleColorMode: () -> Unit,
    onToggleAnnotationMode: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onToggleLandscape: () -> Unit,
    onToggleLayoutMode: () -> Unit,
    onSetColorMode: (ReadingColorMode) -> Unit,
    onOpenPageOrganizer: () -> Unit,
    onShowJumpDialog: () -> Unit,
    onShowDocInfoDialog: () -> Unit,
    onSaveDocument: () -> Unit,
    onSaveAsDocument: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isMenuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 6.dp
    ) {
        TopAppBar(
            title = {
                Text(
                    text = uiState.documentInfo?.title ?: "Lumina Reader",
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
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
                    isActive = isDrawerOpen || uiState.isOutlineDrawerOpen,
                    onClick = onToggleDrawer
                )

                // 2. 智能切白边
                ViewerActionButton(
                    icon = Icons.Default.Crop,
                    contentDescription = if (uiState.isAutoCropEnabled) "已开启智能裁切" else "已关闭智能裁切",
                    isActive = uiState.isAutoCropEnabled,
                    onClick = onToggleAutoCrop
                )

                // 3. 护眼暗色模式
                ViewerActionButton(
                    icon = if (uiState.colorMode != ReadingColorMode.NORMAL) Icons.Default.DarkMode else Icons.Default.LightMode,
                    contentDescription = if (uiState.colorMode != ReadingColorMode.NORMAL) "退出暗色模式" else "护眼暗色模式",
                    isActive = uiState.colorMode != ReadingColorMode.NORMAL,
                    onClick = onToggleColorMode
                )

                // 4. 文档注释与涂鸦工具开关
                ViewerActionButton(
                    icon = Icons.Default.Draw,
                    contentDescription = if (uiState.annotationTool != AnnotationTool.NONE) "退出注释模式" else "进入注释模式",
                    isActive = uiState.annotationTool != AnnotationTool.NONE,
                    onClick = onToggleAnnotationMode
                )

                // 5. 保存文档
                val hasModifications = uiState.annotations.isNotEmpty() || (
                    uiState.pageSpecs.isNotEmpty() && (
                        uiState.pageSpecs.size != (uiState.documentInfo?.pageCount ?: 0) ||
                        uiState.pageSpecs.mapIndexed { idx, spec -> idx == spec.originalPageIndex && spec.normalizedRotation == 0 }.any { !it }
                    )
                )

                if (uiState.isSaving) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    ViewerActionButton(
                        icon = Icons.Default.Save,
                        contentDescription = "保存修改",
                        isActive = hasModifications,
                        onClick = onSaveDocument
                    )
                }

                // 6. 更多菜单
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
                                onToggleAutoCrop()
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
                                onToggleFullscreen()
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
                                onToggleLandscape()
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
                                onToggleLayoutMode()
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
                                onSetColorMode(ReadingColorMode.NORMAL)
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
                                onSetColorMode(ReadingColorMode.SOFT_DARK)
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
                                onSetColorMode(ReadingColorMode.AMOLED_DARK)
                            }
                        )

                        HorizontalDivider()

                        DropdownMenuItem(
                            text = { Text("保存修改 (Save)") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Save,
                                    contentDescription = null,
                                    tint = if (hasModifications) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {
                                isMenuOpen = false
                                onSaveDocument()
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("另存为新文档 (Save As...)") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.SaveAs,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            onClick = {
                                isMenuOpen = false
                                onSaveAsDocument()
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("页面组织与编辑") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.DashboardCustomize,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            onClick = {
                                isMenuOpen = false
                                onOpenPageOrganizer()
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("跳转到指定页") },
                            leadingIcon = { Icon(Icons.Default.Numbers, contentDescription = null) },
                            onClick = {
                                isMenuOpen = false
                                onShowJumpDialog()
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("文档信息") },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                            onClick = {
                                isMenuOpen = false
                                onShowDocInfoDialog()
                            }
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )
    }
}

@Composable
fun ViewerActionButton(
    icon: ImageVector,
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
