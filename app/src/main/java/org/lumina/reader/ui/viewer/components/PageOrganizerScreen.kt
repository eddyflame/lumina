package org.lumina.reader.ui.viewer.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.lumina.reader.R
import org.lumina.reader.core.model.PageEditSpec
import org.lumina.reader.ui.viewer.ViewerViewModel

/**
 * 页面组织与编辑工作台 (Page Organizer Workbench)
 *
 * 支持多页网格预览、顺时针/逆时针旋转 90°、前移/后移排序、删除页面与全局重置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageOrganizerScreen(
    pageSpecs: List<PageEditSpec>,
    currentPageIndex: Int,
    viewModel: ViewerViewModel,
    onPageSelected: (Int) -> Unit,
    onRotatePage: (virtualIndex: Int, degreesDelta: Int) -> Unit,
    onMovePage: (fromIndex: Int, toIndex: Int) -> Unit,
    onDeletePage: (virtualIndex: Int) -> Unit,
    onRotateAll: (degreesDelta: Int) -> Unit,
    onResetAll: () -> Unit,
    onSave: (() -> Unit)? = null,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteConfirmDialog by remember { mutableStateOf<Int?>(null) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.page_organizer),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = stringResource(R.string.page_organizer_subtitle, pageSpecs.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onRotateAll(90) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.RotateRight,
                            contentDescription = stringResource(R.string.rotate_all_clockwise),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { showResetConfirmDialog = true }) {
                        Icon(
                            Icons.Default.RestartAlt,
                            contentDescription = stringResource(R.string.reset_modifications),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (onSave != null) {
                        IconButton(onClick = onSave) {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = stringResource(R.string.save_modifications),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            itemsIndexed(
                items = pageSpecs,
                key = { index, spec -> "${spec.originalPageIndex}_$index" }
            ) { virtualIndex, spec ->
                PageThumbnailCard(
                    virtualIndex = virtualIndex,
                    spec = spec,
                    isCurrentPage = virtualIndex == currentPageIndex,
                    viewModel = viewModel,
                    canMoveLeft = virtualIndex > 0,
                    canMoveRight = virtualIndex < pageSpecs.size - 1,
                    canDelete = pageSpecs.size > 1,
                    onSelect = { onPageSelected(virtualIndex) },
                    onRotateLeft = { onRotatePage(virtualIndex, -90) },
                    onRotateRight = { onRotatePage(virtualIndex, 90) },
                    onMoveLeft = { onMovePage(virtualIndex, virtualIndex - 1) },
                    onMoveRight = { onMovePage(virtualIndex, virtualIndex + 1) },
                    onDelete = { showDeleteConfirmDialog = virtualIndex }
                )
            }
        }
    }

    // 单页删除确认弹窗
    showDeleteConfirmDialog?.let { targetVirtualIndex ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = { Text(stringResource(R.string.delete_page_title, targetVirtualIndex + 1)) },
            text = { Text(stringResource(R.string.delete_page_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePage(targetVirtualIndex)
                        showDeleteConfirmDialog = null
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 全局重置确认弹窗
    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text(stringResource(R.string.reset_organizer_title)) },
            text = { Text(stringResource(R.string.reset_organizer_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onResetAll()
                        showResetConfirmDialog = false
                    }
                ) {
                    Text(stringResource(R.string.action_reset), color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun PageThumbnailCard(
    virtualIndex: Int,
    spec: PageEditSpec,
    isCurrentPage: Boolean,
    viewModel: ViewerViewModel,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onDelete: () -> Unit
) {
    var thumbnailBitmap by remember(spec.originalPageIndex) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(spec.originalPageIndex) {
        thumbnailBitmap = viewModel.renderPage(spec.originalPageIndex, 240, 320)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isCurrentPage) 2.dp else 1.dp,
                color = if (isCurrentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            ),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        tonalElevation = if (isCurrentPage) 4.dp else 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 缩略图主区域 (点击跳转)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onSelect() },
                contentAlignment = Alignment.Center
            ) {
                val bmp = thumbnailBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.page_thumbnail_desc, virtualIndex + 1),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .rotate(spec.normalizedRotation.toFloat())
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // 旋转角度指示角标
                if (spec.normalizedRotation != 0) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "${spec.normalizedRotation}°",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 页码与原始索引标注
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.page_number_format, virtualIndex + 1),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isCurrentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                if (virtualIndex != spec.originalPageIndex) {
                    Text(
                        text = stringResource(R.string.original_page_format, spec.originalPageIndex + 1),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 快捷操作栏：旋转 / 移动 / 删除
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 逆时针 90°
                IconButton(
                    onClick = onRotateLeft,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.RotateLeft,
                        contentDescription = stringResource(R.string.rotate_counter_clockwise),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 顺时针 90°
                IconButton(
                    onClick = onRotateRight,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.RotateRight,
                        contentDescription = stringResource(R.string.rotate_clockwise),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // 前移
                IconButton(
                    onClick = onMoveLeft,
                    enabled = canMoveLeft,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.move_page_forward),
                        modifier = Modifier.size(16.dp),
                        tint = if (canMoveLeft) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                }

                // 后移
                IconButton(
                    onClick = onMoveRight,
                    enabled = canMoveRight,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.move_page_backward),
                        modifier = Modifier.size(16.dp),
                        tint = if (canMoveRight) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                }

                // 删除
                IconButton(
                    onClick = onDelete,
                    enabled = canDelete,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = stringResource(R.string.delete_page),
                        modifier = Modifier.size(16.dp),
                        tint = if (canDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}
