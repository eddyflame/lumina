package org.lumina.reader.ui.viewer.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
                Text("跳转")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun DocInfoDialog(
    docInfo: PdfDocumentInfo,
    currentPageIndex: Int,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文档信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("名称: ${docInfo.title}", style = MaterialTheme.typography.bodyMedium)
                Text("总页数: ${docInfo.pageCount} 页", style = MaterialTheme.typography.bodyMedium)
                Text("当前阅读: 第 ${currentPageIndex + 1} 页", style = MaterialTheme.typography.bodyMedium)
                if (docInfo.fileSize > 0) {
                    val sizeMb = String.format("%.2f MB", docInfo.fileSize / (1024f * 1024f))
                    Text("文件大小: $sizeMb", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定")
            }
        }
    )
}
