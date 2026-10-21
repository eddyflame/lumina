package org.lumina.reader.ui.shelf

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.lumina.reader.R
import org.lumina.reader.data.db.RecentDocument
import org.lumina.reader.ui.settings.ThemeSettingsBottomSheet
import org.lumina.reader.ui.viewer.ViewerViewModel
import org.lumina.reader.ui.viewer.components.AboutAppDialog
import org.lumina.reader.ui.viewer.components.resolveDisplayPath
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(
    viewModel: ViewerViewModel,
    onOpenPdfUri: (Uri) -> Unit
) {
    val recentList by viewModel.recentDocuments.collectAsState()
    val themeSettings by viewModel.themeSettings.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showThemeSheet by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var docForDetails by remember { mutableStateOf<RecentDocument?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 页面呈现时异步校验书架中各文档的有效性
    LaunchedEffect(Unit) {
        viewModel.validateRecentDocuments()
    }

    // 监听文档打开错误通知并展示轻提示
    LaunchedEffect(Unit) {
        viewModel.openDocumentErrorEvent.receiveAsFlow().collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val filteredList = remember(recentList, searchQuery) {
        if (searchQuery.isBlank()) recentList
        else recentList.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    // 现代 SAF 系统文件选择器 (零权限接入)
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { onOpenPdfUri(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Lumina",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.5).sp
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.app_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showThemeSheet = true }) {
                        Icon(
                            Icons.Default.Palette,
                            contentDescription = stringResource(R.string.theme_settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(R.string.about_app),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (recentList.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirmDialog = true }) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.clear_bookshelf),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                icon = { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.open_pdf)) },
                text = { Text(stringResource(R.string.action_open_document), fontWeight = FontWeight.Bold) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { innerPadding ->
        if (recentList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        modifier = Modifier.size(92.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(46.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.shelf_empty_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.no_recent_documents_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_open_document), fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 搜索框
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.search_recent_placeholder)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear_search))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }

                // 最近文档标题栏
                item {
                    Text(
                        text = if (searchQuery.isNotBlank()) {
                            stringResource(R.string.search_results_title, filteredList.size)
                        } else {
                            stringResource(R.string.recent_reading_count, filteredList.size)
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                // 书架文档列表
                if (filteredList.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.size(52.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.no_matching_documents),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                TextButton(onClick = { searchQuery = "" }) {
                                    Text(stringResource(R.string.clear_search))
                                }
                            }
                        }
                    }
                } else {
                    items(filteredList, key = { it.uriString }) { doc ->
                        RecentDocumentCard(
                            doc = doc,
                            onClick = { onOpenPdfUri(doc.uri) },
                            onInvalidClick = {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.document_invalid_message)
                                    )
                                }
                            },
                            onTogglePin = { viewModel.togglePinRecentDocument(doc.uriString, !doc.isPinned) },
                            onDelete = { viewModel.deleteRecentDocument(doc.uriString) },
                            onShowDetails = { docForDetails = doc }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(88.dp))
                }
            }
        }
    }

    if (showThemeSheet) {
        ThemeSettingsBottomSheet(
            themeSettings = themeSettings,
            onThemeModeSelected = { viewModel.setThemeMode(it) },
            onDarkThemeStyleSelected = { viewModel.setDarkThemeStyle(it) },
            onDismissRequest = { showThemeSheet = false }
        )
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text(stringResource(R.string.clear_history_title)) },
            text = { Text(stringResource(R.string.clear_history_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllHistory()
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showAboutDialog) {
        AboutAppDialog(
            onDismiss = { showAboutDialog = false }
        )
    }

    docForDetails?.let { doc ->
        ShelfDocumentInfoDialog(
            doc = doc,
            onDismiss = { docForDetails = null },
            onCopySuccess = { msg ->
                scope.launch {
                    snackbarHostState.showSnackbar(msg)
                }
            }
        )
    }
}

@Composable
fun RecentDocumentCard(
    doc: RecentDocument,
    onClick: () -> Unit,
    onInvalidClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onShowDetails: () -> Unit
) {
    var isMenuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (!doc.isValid) Modifier.alpha(0.65f) else Modifier),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                !doc.isValid -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                doc.isPinned -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            1.dp,
            if (!doc.isValid) MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (doc.isValid) 1.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 点击主体区域：有效文档打开，失效文档拦截并提示
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = if (doc.isValid) onClick else onInvalidClick),
                verticalAlignment = Alignment.Top
            ) {
                // 文档格式图标徽章
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when {
                                !doc.isValid -> MaterialTheme.colorScheme.errorContainer
                                doc.isPinned -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (!doc.isValid) Icons.Default.BrokenImage else Icons.Default.Description,
                        contentDescription = null,
                        tint = when {
                            !doc.isValid -> MaterialTheme.colorScheme.onErrorContainer
                            doc.isPinned -> MaterialTheme.colorScheme.onPrimary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        if (doc.isPinned && doc.isValid) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = stringResource(R.string.pinned),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 2.dp, end = 4.dp)
                                    .size(16.dp)
                            )
                        }
                        Text(
                            text = doc.title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                lineBreak = LineBreak.Paragraph
                            ),
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = true,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (!doc.isValid) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.document_invalid_badge),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = if (doc.isValid) {
                            "${doc.getProgressFormatted(context)} · ${doc.getTimeFormatted(context)}"
                        } else {
                            stringResource(R.string.document_invalid_message)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (doc.isValid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )

                    if (doc.isValid) {
                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = { doc.progressPercent },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 独立选项菜单按钮，避免手势与卡片点击冲突
            Box(modifier = Modifier.padding(top = 0.dp)) {
                IconButton(onClick = { isMenuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.action_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(
                    expanded = isMenuOpen,
                    onDismissRequest = { isMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.document_details)) },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                        onClick = {
                            isMenuOpen = false
                            onShowDetails()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (doc.isPinned) {
                                    stringResource(R.string.unpin_document)
                                } else {
                                    stringResource(R.string.pin_document)
                                }
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                        onClick = {
                            isMenuOpen = false
                            onTogglePin()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remove_from_shelf), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            isMenuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ShelfDocumentInfoDialog(
    doc: RecentDocument,
    onDismiss: () -> Unit,
    onCopySuccess: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val displayPath = remember(doc.uri) {
        resolveDisplayPath(context, doc.uri)
    }
    val fullReadTime = remember(doc.lastReadTime) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sdf.format(Date(doc.lastReadTime))
    }
    val fileSizeFormatted = remember(doc.uri) {
        var size: Long = -1
        try {
            if (doc.uri.scheme == "content") {
                context.contentResolver.query(
                    doc.uri,
                    arrayOf(OpenableColumns.SIZE),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
            } else if (doc.uri.scheme == "file") {
                val file = File(doc.uri.path ?: "")
                if (file.exists()) {
                    size = file.length()
                }
            }
        } catch (_: Exception) {}
        if (size > 0) {
            String.format(Locale.getDefault(), "%.2f MB", size / (1024f * 1024f))
        } else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(26.dp)
                )
            }
        },
        title = {
            Text(
                text = stringResource(R.string.document_details),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. 完整文件名展示与一键复制
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.doc_info_filename),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(doc.title))
                                    onCopySuccess(context.getString(R.string.filename_copied))
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.copy_filename),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = doc.title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    // 2. 存储路径展示与一键复制
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.doc_info_path, "").trimEnd(':', ' ', '：'),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(displayPath))
                                    onCopySuccess(context.getString(R.string.filepath_copied))
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.copy_filepath),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        ) {
                            Text(
                                text = displayPath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    // 3. 详细元数据
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.doc_info_total_pages, doc.pageCount),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.doc_info_current_page, doc.lastReadPage + 1) + " (${(doc.progressPercent * 100).toInt()}%)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        fileSizeFormatted?.let { sizeStr ->
                            Text(
                                text = stringResource(R.string.doc_info_size, sizeStr),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            text = stringResource(R.string.doc_info_last_read, fullReadTime),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(
                                R.string.doc_info_status,
                                stringResource(if (doc.isValid) R.string.doc_status_normal else R.string.doc_status_invalid)
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (doc.isValid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                            fontWeight = if (!doc.isValid) FontWeight.Bold else FontWeight.Normal
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

