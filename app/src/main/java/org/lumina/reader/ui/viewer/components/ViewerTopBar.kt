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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.lumina.reader.R
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
    onOpenPageOrganizer: () -> Unit,
    onShowJumpDialog: () -> Unit,
    onShowDocInfoDialog: () -> Unit,
    onShowAboutDialog: () -> Unit,
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
                    text = uiState.documentInfo?.title ?: stringResource(R.string.app_name),
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back_to_shelf)
                    )
                }
            },
            actions = {
                // 1. 目录大纲抽屉唤起按钮
                ViewerActionButton(
                    icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                    contentDescription = stringResource(R.string.outline_drawer),
                    isActive = isDrawerOpen || uiState.isOutlineDrawerOpen,
                    onClick = onToggleDrawer
                )

                // 2. 智能切白边
                ViewerActionButton(
                    icon = Icons.Default.Crop,
                    contentDescription = if (uiState.isAutoCropEnabled) {
                        stringResource(R.string.crop_enabled)
                    } else {
                        stringResource(R.string.crop_disabled)
                    },
                    isActive = uiState.isAutoCropEnabled,
                    onClick = onToggleAutoCrop
                )

                // 3. 护眼暗色模式
                ViewerActionButton(
                    icon = if (uiState.colorMode != ReadingColorMode.NORMAL) Icons.Default.DarkMode else Icons.Default.LightMode,
                    contentDescription = if (uiState.colorMode != ReadingColorMode.NORMAL) {
                        stringResource(R.string.dark_mode_exit)
                    } else {
                        stringResource(R.string.dark_mode_enter)
                    },
                    isActive = uiState.colorMode != ReadingColorMode.NORMAL,
                    onClick = onToggleColorMode
                )

                // 4. 文档注释与涂鸦工具开关
                ViewerActionButton(
                    icon = Icons.Default.Draw,
                    contentDescription = if (uiState.annotationTool != AnnotationTool.NONE) {
                        stringResource(R.string.annotation_mode_exit)
                    } else {
                        stringResource(R.string.annotation_mode_enter)
                    },
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
                        modifier = Modifier.size(40.dp),
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
                        icon = Icons.Default.SaveAs,
                        contentDescription = stringResource(R.string.save_as_document),
                        isActive = hasModifications,
                        onClick = onSaveAsDocument
                    )
                }

                // 6. 更多菜单
                Box {
                    ViewerActionButton(
                        icon = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.more_settings),
                        isActive = isMenuOpen,
                        onClick = { isMenuOpen = true }
                    )

                    DropdownMenu(
                        expanded = isMenuOpen,
                        onDismissRequest = { isMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.page_organizer)) },
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

                        HorizontalDivider()

                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (uiState.isFullscreen) {
                                        stringResource(R.string.fullscreen_exit)
                                    } else {
                                        stringResource(R.string.fullscreen_enter)
                                    }
                                )
                            },
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
                            text = {
                                Text(
                                    if (uiState.isLandscape) {
                                        stringResource(R.string.landscape_exit)
                                    } else {
                                        stringResource(R.string.landscape_enter)
                                    }
                                )
                            },
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
                                Text(
                                    if (uiState.layoutMode == ReadingLayoutMode.CONTINUOUS_VERTICAL) {
                                        stringResource(R.string.layout_single_page)
                                    } else {
                                        stringResource(R.string.layout_continuous)
                                    }
                                )
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
                            text = { Text(stringResource(R.string.jump_to_page)) },
                            leadingIcon = { Icon(Icons.Default.Numbers, contentDescription = null) },
                            onClick = {
                                isMenuOpen = false
                                onShowJumpDialog()
                            }
                        )

                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.document_info)) },
                            leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                            onClick = {
                                isMenuOpen = false
                                onShowDocInfoDialog()
                            }
                        )

                        HorizontalDivider()

                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.about_app)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            onClick = {
                                isMenuOpen = false
                                onShowAboutDialog()
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

    val iconColor by animateColorAsState(
        targetValue = if (isActive) activeColor else inactiveColor,
        label = "viewer_icon_color"
    )

    IconButton(
        onClick = onClick,
        modifier = modifier.size(40.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(22.dp)
        )
    }
}
