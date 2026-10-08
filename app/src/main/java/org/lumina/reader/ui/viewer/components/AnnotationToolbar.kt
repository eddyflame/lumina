package org.lumina.reader.ui.viewer.components

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.lumina.reader.R
import org.lumina.reader.core.annotation.AnnotationTool

private val ANNOTATION_PALETTE = listOf(
    0xFF0066FF, // 品牌天蓝
    0xFFE53935, // 醒目烈红
    0xFFFFB300, // 荧光琥珀
    0xFF43A047, // 护眼清绿
    0xFF8E24AA, // 典雅紫罗兰
    0xFF212121  // 纯黑碳素
)

private data class StrokeWidthOption(val widthDp: Float, val labelResId: Int)

private val STROKE_WIDTH_OPTIONS = listOf(
    StrokeWidthOption(2f, R.string.stroke_fine),
    StrokeWidthOption(4f, R.string.stroke_medium),
    StrokeWidthOption(8f, R.string.stroke_thick)
)

/**
 * 现代悬浮注释工具条 (自适应横竖屏布局与橡皮擦反馈)
 */
@Composable
fun AnnotationToolbar(
    activeTool: AnnotationTool,
    currentColor: Long,
    currentStrokeWidthDp: Float,
    canUndo: Boolean,
    canRedo: Boolean,
    onToolChange: (AnnotationTool) -> Unit,
    onColorChange: (Long) -> Unit,
    onStrokeWidthChange: (Float) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClearPage: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showClearConfirm by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isWideScreen = configuration.screenWidthDp >= 600

    AnimatedVisibility(
        visible = activeTool != AnnotationTool.NONE,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            if (isLandscape || isWideScreen) {
                // 横屏与宽屏模式：单行完整展现
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToolsGroup(
                        activeTool = activeTool,
                        onToolChange = onToolChange
                    )

                    VerticalDivider(
                        modifier = Modifier
                            .height(26.dp)
                            .padding(horizontal = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    // 中间属性配置区
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (activeTool == AnnotationTool.PEN || activeTool == AnnotationTool.HIGHLIGHTER) {
                            ColorPaletteRow(
                                currentColor = currentColor,
                                onColorChange = onColorChange
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            StrokeWidthSelector(
                                currentStrokeWidthDp = currentStrokeWidthDp,
                                onStrokeWidthChange = onStrokeWidthChange
                            )
                        } else if (activeTool == AnnotationTool.ERASER) {
                            EraserStatusRow(
                                onClearPageClick = { showClearConfirm = true }
                            )
                        }
                    }

                    VerticalDivider(
                        modifier = Modifier
                            .height(26.dp)
                            .padding(horizontal = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    ActionButtonsGroup(
                        canUndo = canUndo,
                        canRedo = canRedo,
                        onUndo = onUndo,
                        onRedo = onRedo,
                        onClearPageClick = { showClearConfirm = true },
                        onClose = onClose,
                        showClearButton = activeTool != AnnotationTool.ERASER
                    )
                }
            } else {
                // 竖屏模式：层次清晰的双行排版，彻底解决竖屏右侧按钮被截断问题
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 第 1 行：工具切换组 (左) 与操作动作组 (右)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ToolsGroup(
                            activeTool = activeTool,
                            onToolChange = onToolChange
                        )

                        ActionButtonsGroup(
                            canUndo = canUndo,
                            canRedo = canRedo,
                            onUndo = onUndo,
                            onRedo = onRedo,
                            onClearPageClick = { showClearConfirm = true },
                            onClose = onClose,
                            showClearButton = activeTool != AnnotationTool.ERASER
                        )
                    }

                    // 第 2 行：活跃工具专属配置 (颜色/粗细快速切换 或 橡皮擦状态)
                    if (activeTool == AnnotationTool.PEN || activeTool == AnnotationTool.HIGHLIGHTER) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ColorPaletteRow(
                                currentColor = currentColor,
                                onColorChange = onColorChange
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            StrokeWidthSelector(
                                currentStrokeWidthDp = currentStrokeWidthDp,
                                onStrokeWidthChange = onStrokeWidthChange
                            )
                        }
                    } else if (activeTool == AnnotationTool.ERASER) {
                        EraserStatusRow(
                            onClearPageClick = { showClearConfirm = true },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.clear_annotations_title)) },
            text = { Text(stringResource(R.string.clear_annotations_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearPage()
                        showClearConfirm = false
                    }
                ) {
                    Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/**
 * 工具选择组 (钢笔、荧光笔、橡皮擦)
 */
@Composable
private fun ToolsGroup(
    activeTool: AnnotationTool,
    onToolChange: (AnnotationTool) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolIconButton(
            painter = rememberVectorPainter(Icons.Default.Edit),
            label = stringResource(R.string.tool_pen),
            isSelected = activeTool == AnnotationTool.PEN,
            onClick = { onToolChange(AnnotationTool.PEN) }
        )

        ToolIconButton(
            painter = rememberVectorPainter(Icons.Default.BorderColor),
            label = stringResource(R.string.tool_highlighter),
            isSelected = activeTool == AnnotationTool.HIGHLIGHTER,
            onClick = { onToolChange(AnnotationTool.HIGHLIGHTER) }
        )

        ToolIconButton(
            painter = painterResource(R.drawable.ic_eraser),
            label = stringResource(R.string.tool_eraser),
            isSelected = activeTool == AnnotationTool.ERASER,
            onClick = { onToolChange(AnnotationTool.ERASER) }
        )
    }
}

/**
 * 撤销、重做、清空本页、完成按钮组
 */
@Composable
private fun ActionButtonsGroup(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClearPageClick: () -> Unit,
    onClose: () -> Unit,
    showClearButton: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onUndo,
            enabled = canUndo,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Undo,
                contentDescription = stringResource(R.string.action_undo),
                modifier = Modifier.size(18.dp)
            )
        }

        IconButton(
            onClick = onRedo,
            enabled = canRedo,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Redo,
                contentDescription = stringResource(R.string.action_redo),
                modifier = Modifier.size(18.dp)
            )
        }

        if (showClearButton) {
            IconButton(
                onClick = onClearPageClick,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.DeleteSweep,
                    contentDescription = stringResource(R.string.action_clear_page),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // 完成退出按钮
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.action_finish_annotation),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 调色盘行
 */
@Composable
private fun ColorPaletteRow(
    currentColor: Long,
    onColorChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ANNOTATION_PALETTE.forEach { colorValue ->
            val isSelected = currentColor == colorValue
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(colorValue))
                    .border(
                        width = if (isSelected) 2.5.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
                        shape = CircleShape
                    )
                    .clickable { onColorChange(colorValue) },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = if (colorValue == 0xFFFFB300) Color.Black else Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/**
 * 笔触粗细快速切换
 */
@Composable
private fun StrokeWidthSelector(
    currentStrokeWidthDp: Float,
    onStrokeWidthChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            STROKE_WIDTH_OPTIONS.forEach { opt ->
                val isSelected = currentStrokeWidthDp == opt.widthDp
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .clickable { onStrokeWidthChange(opt.widthDp) }
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = stringResource(opt.labelResId),
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 橡皮擦模式提示与快捷清空行
 */
@Composable
private fun EraserStatusRow(
    onClearPageClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_eraser),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = stringResource(R.string.eraser_mode_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
            modifier = Modifier.clickable { onClearPageClick() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.DeleteSweep,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = stringResource(R.string.action_clear_page),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun ToolIconButton(
    painter: Painter,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val containerBg = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(containerBg)
    ) {
        Icon(
            painter = painter,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
    }
}
