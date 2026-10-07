package org.lumina.reader.ui.viewer.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
 * 现代悬浮注释工具条
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
    var showColorPicker by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = activeTool != AnnotationTool.NONE,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            tonalElevation = 10.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 顶部工具切换与主操作行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 工具类型组
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ToolIconButton(
                            icon = Icons.Default.Edit,
                            label = stringResource(R.string.tool_pen),
                            isSelected = activeTool == AnnotationTool.PEN,
                            onClick = { onToolChange(AnnotationTool.PEN) }
                        )

                        ToolIconButton(
                            icon = Icons.Default.BorderColor,
                            label = stringResource(R.string.tool_highlighter),
                            isSelected = activeTool == AnnotationTool.HIGHLIGHTER,
                            onClick = { onToolChange(AnnotationTool.HIGHLIGHTER) }
                        )

                        ToolIconButton(
                            icon = Icons.AutoMirrored.Filled.Backspace,
                            label = stringResource(R.string.tool_eraser),
                            isSelected = activeTool == AnnotationTool.ERASER,
                            onClick = { onToolChange(AnnotationTool.ERASER) }
                        )
                    }

                    // 颜色与粗细指示气泡 (仅在手写或荧光笔模式下可调)
                    if (activeTool == AnnotationTool.PEN || activeTool == AnnotationTool.HIGHLIGHTER) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 颜色选择器触发展开
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(Color(currentColor))
                                    .border(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                                    .clickable { showColorPicker = !showColorPicker }
                            )

                            // 笔触粗细快速切换
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
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
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
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
                    }

                    // 撤销 / 重做 / 完成退出
                    Row(
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

                        IconButton(
                            onClick = { showClearConfirm = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.action_clear_page),
                                modifier = Modifier.size(18.dp)
                            )
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

                // 展开的调色盘行
                if (showColorPicker && (activeTool == AnnotationTool.PEN || activeTool == AnnotationTool.HIGHLIGHTER)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ANNOTATION_PALETTE.forEach { colorValue ->
                            val isSelected = currentColor == colorValue
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color(colorValue))
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f),
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        onColorChange(colorValue)
                                        showColorPicker = false
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = if (colorValue == 0xFFFFB300) Color.Black else Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
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

@Composable
private fun ToolIconButton(
    icon: ImageVector,
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
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
    }
}
