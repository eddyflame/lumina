package org.lumina.reader.ui.viewer.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.foundation.text.selection.SelectionContainer
import org.lumina.reader.R
import org.lumina.reader.core.model.PdfDocumentInfo

@Composable
fun JumpPageDialog(
    totalPages: Int,
    initialTargetText: String,
    onDismiss: () -> Unit,
    onConfirm: (targetIndex: Int) -> Unit
) {
    var jumpTargetPageText by remember { mutableStateOf(initialTargetText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.jump_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.jump_dialog_prompt, totalPages),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = jumpTargetPageText,
                    onValueChange = { input ->
                        jumpTargetPageText = input.filter { it.isDigit() }
                    },
                    label = { Text(stringResource(R.string.page_input_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val target = jumpTargetPageText.toIntOrNull()
                            if (target != null && target in 1..totalPages) {
                                onConfirm(target - 1)
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
                        onConfirm(target - 1)
                    }
                }
            ) {
                Text(stringResource(R.string.action_jump))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * 尽量将系统 Uri 解析为人类可读的物理文件路径或友好路径
 */
fun resolveDisplayPath(context: Context, uri: Uri): String {
    try {
        if (uri.scheme == "file") {
            return uri.path ?: uri.toString()
        }

        if (uri.scheme == "content") {
            // 1. SAF ExternalStorageProvider 解析
            if (DocumentsContract.isDocumentUri(context, uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                if (uri.authority == "com.android.externalstorage.documents") {
                    val split = docId.split(":")
                    if (split.size >= 2) {
                        val type = split[0]
                        val relativePath = split[1]
                        return if ("primary".equals(type, ignoreCase = true)) {
                            "${Environment.getExternalStorageDirectory().path}/$relativePath"
                        } else {
                            "/storage/$type/$relativePath"
                        }
                    }
                } else if (uri.authority == "com.android.providers.downloads.documents") {
                    if (docId.startsWith("raw:")) {
                        return docId.removePrefix("raw:")
                    }
                }
            }

            // 2. 尝试从 MediaStore 查询 _data 真实文件路径
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DATA),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        if (index != -1) {
                            val data = cursor.getString(index)
                            if (!data.isNullOrBlank() && (data.startsWith("/") || data.startsWith("file://"))) {
                                return data
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            // 3. 回退为 URL 解码后的友好显示路径
            return Uri.decode(uri.toString())
        }
    } catch (_: Exception) {}

    return uri.path ?: uri.toString()
}

@Composable
fun DocInfoDialog(
    docInfo: PdfDocumentInfo,
    currentPageIndex: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val filePath = remember(docInfo.uri) {
        resolveDisplayPath(context, docInfo.uri)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.document_info)) },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.doc_info_name, docInfo.title),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.doc_info_path, filePath),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.doc_info_total_pages, docInfo.pageCount),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.doc_info_current_page, currentPageIndex + 1),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (docInfo.fileSize > 0) {
                        val sizeMb =
                            String.format(java.util.Locale.getDefault(), "%.2f MB", docInfo.fileSize / (1024f * 1024f))
                        Text(
                            stringResource(R.string.doc_info_size, sizeMb),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_confirm))
            }
        }
    )
}

/**
 * 软件信息与关于对话框
 */
@Composable
fun AboutAppDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val versionName = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.1.1"
        } catch (_: Exception) {
            "1.1.1"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Lumina PDF",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "v$versionName",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.app_name_label),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Lumina PDF",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.version_label),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = versionName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.author_label),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "eddy",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.about_app_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_confirm))
            }
        }
    )
}
