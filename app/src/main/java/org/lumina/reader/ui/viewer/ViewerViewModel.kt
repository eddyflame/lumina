package org.lumina.reader.ui.viewer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.lumina.reader.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lumina.reader.core.crop.PageCropper2
import org.lumina.reader.core.model.PageEditSpec
import org.lumina.reader.core.model.PageInfo
import org.lumina.reader.core.model.PdfDocumentInfo
import org.lumina.reader.core.model.PdfOutlineItem
import org.lumina.reader.core.model.ReadingColorMode
import org.lumina.reader.core.model.ReadingLayoutMode
import org.lumina.reader.data.db.RecentDocument
import org.lumina.reader.data.preferences.AppThemeMode
import org.lumina.reader.data.preferences.DarkThemeStyle
import org.lumina.reader.data.preferences.ThemePreferences
import org.lumina.reader.data.preferences.ThemeSettings
import org.lumina.reader.data.repository.DocumentRepository

import org.lumina.reader.core.annotation.AddAnnotationCommand
import org.lumina.reader.core.annotation.AnnotationCommand
import org.lumina.reader.core.annotation.AnnotationStore
import org.lumina.reader.core.annotation.AnnotationTool
import org.lumina.reader.core.annotation.CompoundAnnotationCommand
import org.lumina.reader.core.annotation.DeleteAnnotationCommand
import org.lumina.reader.core.annotation.NormalizedPoint
import org.lumina.reader.core.annotation.PdfAnnotation
import org.lumina.reader.core.annotation.UndoRedoManager

data class ViewerUiState(
    val isLoading: Boolean = false,
    val documentInfo: PdfDocumentInfo? = null,
    val currentPageIndex: Int = 0,
    val isAutoCropEnabled: Boolean = true,
    val activeColumnBounds: PageCropper2.CropBounds? = null,
    val colorMode: ReadingColorMode = ReadingColorMode.NORMAL,
    val layoutMode: ReadingLayoutMode = ReadingLayoutMode.CONTINUOUS_VERTICAL,
    val isOverlayVisible: Boolean = true,
    val isOutlineDrawerOpen: Boolean = false,
    val isFullscreen: Boolean = false,
    val isLandscape: Boolean = false,
    val outlines: List<PdfOutlineItem> = emptyList(),
    val errorMessage: String? = null,
    val annotationTool: AnnotationTool = AnnotationTool.NONE,
    val annotationColor: Long = 0xFF0066FF, // 默认品牌天蓝
    val annotationStrokeWidthDp: Float = 3f,
    val annotations: Map<Int, List<PdfAnnotation>> = emptyMap(),
    val canUndoAnnotation: Boolean = false,
    val canRedoAnnotation: Boolean = false,
    val pageSpecs: List<PageEditSpec> = emptyList(),
    val isPageOrganizerOpen: Boolean = false,
    val isSaving: Boolean = false,
    val saveUserMessage: String? = null
)

class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DocumentRepository(application)
    private val themePreferences = ThemePreferences(application)
    private val undoRedoManager = UndoRedoManager()

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private val annotationStore = object : AnnotationStore {
        override fun addAnnotation(annotation: PdfAnnotation) {
            _uiState.update { state ->
                val currentList = state.annotations[annotation.pageIndex] ?: emptyList()
                val updated = state.annotations + (annotation.pageIndex to (currentList + annotation))
                state.copy(annotations = updated)
            }
        }

        override fun removeAnnotation(annotationId: String) {
            _uiState.update { state ->
                val updated = state.annotations.mapValues { (_, list) ->
                    list.filter { it.id != annotationId }
                }
                state.copy(annotations = updated)
            }
        }

        override fun getAnnotationsForPage(pageIndex: Int): List<PdfAnnotation> {
            return _uiState.value.annotations[pageIndex] ?: emptyList()
        }
    }

    init {
        viewModelScope.launch {
            undoRedoManager.canUndoFlow.collect { canUndo ->
                _uiState.update { it.copy(canUndoAnnotation = canUndo) }
            }
        }
        viewModelScope.launch {
            undoRedoManager.canRedoFlow.collect { canRedo ->
                _uiState.update { it.copy(canRedoAnnotation = canRedo) }
            }
        }
    }

    val recentDocuments: StateFlow<List<RecentDocument>> = repository.recentDocuments
    val themeSettings: StateFlow<ThemeSettings> = themePreferences.themeSettings
    val triggerSaveAsEvent = Channel<String>(Channel.BUFFERED)

    fun setThemeMode(mode: AppThemeMode) {
        themePreferences.setThemeMode(mode)
    }

    fun setDarkThemeStyle(style: DarkThemeStyle) {
        themePreferences.setDarkThemeStyle(style)
    }

    fun openDocument(uri: Uri) {
        viewModelScope.launch {
            undoRedoManager.clear()
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    activeColumnBounds = null,
                    isFullscreen = false,
                    isLandscape = false,
                    annotationTool = AnnotationTool.NONE,
                    annotations = emptyMap()
                )
            }
            try {
                val doc = repository.openDocument(uri)
                val outlines = repository.pdfEngine.getOutlines()
                val initialSpecs = (0 until doc.pageCount).map { PageEditSpec(it, 0) }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        documentInfo = doc,
                        currentPageIndex = doc.initialPage,
                        outlines = outlines,
                        pageSpecs = initialSpecs,
                        isPageOrganizerOpen = false,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = getApplication<Application>().getString(
                            R.string.error_open_document,
                            e.localizedMessage ?: e.message ?: ""
                        )
                    )
                }
            }
        }
    }

    suspend fun getPageInfo(pageIndex: Int): PageInfo {
        return repository.pdfEngine.getPageInfo(pageIndex)
    }

    suspend fun renderPage(pageIndex: Int, targetWidth: Int, targetHeight: Int): Bitmap {
        return repository.pdfEngine.renderPage(pageIndex, targetWidth, targetHeight)
    }

    suspend fun getCropBounds(pageIndex: Int): PageCropper2.CropBounds {
        return repository.pdfEngine.calculateCropBounds(pageIndex)
    }

    fun getCachedCropBounds(pageIndex: Int): PageCropper2.CropBounds {
        return repository.pdfEngine.getCachedCropBounds(pageIndex) ?: PageCropper2.CropBounds.FULL
    }

    fun toggleAutoCrop() {
        _uiState.update { it.copy(isAutoCropEnabled = !it.isAutoCropEnabled) }
    }

    fun setColorMode(mode: ReadingColorMode) {
        _uiState.update { it.copy(colorMode = mode) }
    }

    fun setLayoutMode(mode: ReadingLayoutMode) {
        _uiState.update { it.copy(layoutMode = mode) }
    }

    fun toggleLayoutMode() {
        _uiState.update {
            val next = if (it.layoutMode == ReadingLayoutMode.CONTINUOUS_VERTICAL) {
                ReadingLayoutMode.SINGLE_PAGE_HORIZONTAL
            } else {
                ReadingLayoutMode.CONTINUOUS_VERTICAL
            }
            it.copy(layoutMode = next)
        }
    }

    fun toggleFullscreen() {
        _uiState.update {
            val nextFullscreen = !it.isFullscreen
            it.copy(
                isFullscreen = nextFullscreen,
                isOverlayVisible = !nextFullscreen
            )
        }
    }

    fun setFullscreen(fullscreen: Boolean) {
        _uiState.update {
            it.copy(
                isFullscreen = fullscreen,
                isOverlayVisible = !fullscreen
            )
        }
    }

    fun toggleLandscape() {
        _uiState.update { it.copy(isLandscape = !it.isLandscape) }
    }

    fun setLandscape(landscape: Boolean) {
        _uiState.update { it.copy(isLandscape = landscape) }
    }

    /** 退出阅读器时一次性重置全屏与横屏状态，避免多次 state update */
    fun resetViewerModes() {
        _uiState.update {
            it.copy(isFullscreen = false, isLandscape = false, isOverlayVisible = true)
        }
    }

    fun onPageChanged(index: Int) {
        if (_uiState.value.currentPageIndex != index) {
            _uiState.update { it.copy(currentPageIndex = index) }
            val doc = _uiState.value.documentInfo
            if (doc != null) {
                repository.saveProgress(doc.uri, doc.title, doc.pageCount, index)
            }
        }
    }

    fun toggleOverlay() {
        _uiState.update { it.copy(isOverlayVisible = !it.isOverlayVisible) }
    }

    fun setOutlineDrawerOpen(isOpen: Boolean) {
        _uiState.update { it.copy(isOutlineDrawerOpen = isOpen) }
    }

    /**
     * 论文双栏轻触聚焦定位
     */
    fun focusColumnAt(pageIndex: Int, tapX: Float, tapY: Float) {
        viewModelScope.launch {
            val colBounds = repository.pdfEngine.calculateColumnBounds(pageIndex, tapX, tapY)
            _uiState.update { it.copy(activeColumnBounds = colBounds) }
        }
    }

    fun clearColumnFocus() {
        _uiState.update { it.copy(activeColumnBounds = null) }
    }

    // 书架操作代理
    fun deleteRecentDocument(uri: String) {
        repository.deleteRecentDocument(uri)
    }

    fun togglePinRecentDocument(uri: String, isPinned: Boolean) {
        repository.togglePinRecentDocument(uri, isPinned)
    }

    // ================================= 注释与手写动作 =================================

    fun setAnnotationTool(tool: AnnotationTool) {
        _uiState.update { it.copy(annotationTool = tool) }
    }

    fun setAnnotationColor(color: Long) {
        _uiState.update { it.copy(annotationColor = color) }
    }

    fun setAnnotationStrokeWidth(widthDp: Float) {
        _uiState.update { it.copy(annotationStrokeWidthDp = widthDp) }
    }

    fun addInkAnnotation(pageIndex: Int, strokes: List<List<NormalizedPoint>>, isHighlighter: Boolean) {
        addMultiPageInkAnnotation(mapOf(pageIndex to strokes), isHighlighter)
    }

    fun addMultiPageInkAnnotation(pageStrokes: Map<Int, List<List<NormalizedPoint>>>, isHighlighter: Boolean) {
        if (pageStrokes.isEmpty()) return
        val commands = pageStrokes.mapNotNull { (pageIndex, strokes) ->
            val validStrokes = strokes.filter { it.isNotEmpty() }
            if (validStrokes.isEmpty()) null
            else {
                val ink = PdfAnnotation.Ink(
                    pageIndex = pageIndex,
                    color = _uiState.value.annotationColor,
                    strokeWidthDp = _uiState.value.annotationStrokeWidthDp,
                    isHighlighter = isHighlighter,
                    strokes = validStrokes
                )
                AddAnnotationCommand(annotationStore, ink)
            }
        }
        if (commands.size == 1) {
            undoRedoManager.execute(commands[0])
        } else if (commands.size > 1) {
            undoRedoManager.execute(CompoundAnnotationCommand(commands))
        }
    }

    fun eraseAnnotationAt(pageIndex: Int, point: NormalizedPoint) {
        val list = _uiState.value.annotations[pageIndex] ?: return
        val target = list.filterIsInstance<PdfAnnotation.Ink>().lastOrNull { it.intersects(point) }
        if (target != null) {
            undoRedoManager.execute(DeleteAnnotationCommand(annotationStore, target))
        }
    }

    fun undoAnnotation() {
        undoRedoManager.undo()
    }

    fun redoAnnotation() {
        undoRedoManager.redo()
    }

    fun clearAllAnnotationsForPage(pageIndex: Int) {
        val list = _uiState.value.annotations[pageIndex] ?: return
        for (annot in list) {
            undoRedoManager.execute(DeleteAnnotationCommand(annotationStore, annot))
        }
    }

    fun clearAllHistory() {
        repository.clearAllHistory()
    }

    // ================================= 页面组织与编辑 =================================

    fun setPageOrganizerOpen(isOpen: Boolean) {
        _uiState.update { it.copy(isPageOrganizerOpen = isOpen) }
    }

    fun rotatePage(virtualIndex: Int, degreesDelta: Int) {
        _uiState.update { state ->
            if (virtualIndex !in state.pageSpecs.indices) return@update state
            val updated = state.pageSpecs.toMutableList()
            val old = updated[virtualIndex]
            updated[virtualIndex] = old.copy(rotationDegrees = old.rotationDegrees + degreesDelta)
            state.copy(pageSpecs = updated)
        }
    }

    fun movePage(fromIndex: Int, toIndex: Int) {
        _uiState.update { state ->
            if (fromIndex !in state.pageSpecs.indices || toIndex !in state.pageSpecs.indices) return@update state
            val updated = state.pageSpecs.toMutableList()
            val item = updated.removeAt(fromIndex)
            updated.add(toIndex, item)
            val newCurrent = if (state.currentPageIndex == fromIndex) toIndex else state.currentPageIndex
            state.copy(pageSpecs = updated, currentPageIndex = newCurrent)
        }
    }

    fun deletePage(virtualIndex: Int) {
        _uiState.update { state ->
            if (state.pageSpecs.size <= 1 || virtualIndex !in state.pageSpecs.indices) return@update state
            val updated = state.pageSpecs.toMutableList()
            updated.removeAt(virtualIndex)
            val newCurrent = state.currentPageIndex.coerceAtMost(updated.size - 1)
            state.copy(pageSpecs = updated, currentPageIndex = newCurrent)
        }
    }

    fun rotateAllPages(degreesDelta: Int) {
        _uiState.update { state ->
            val updated = state.pageSpecs.map { it.copy(rotationDegrees = it.rotationDegrees + degreesDelta) }
            state.copy(pageSpecs = updated)
        }
    }

    fun resetPageEdits() {
        _uiState.update { state ->
            val count = state.documentInfo?.pageCount ?: 0
            val initialSpecs = (0 until count).map { PageEditSpec(it, 0) }
            state.copy(pageSpecs = initialSpecs)
        }
    }

    // ================================= 物理保存与导出 =================================

    fun clearSaveMessage() {
        _uiState.update { it.copy(saveUserMessage = null) }
    }

    /**
     * 文档保存统一走【另存为】副本通道，严格遵循不破坏、不修改原文件原则
     */
    fun saveDocument(onSuccess: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        val doc = _uiState.value.documentInfo ?: return
        if (_uiState.value.isSaving) return

        val suggestedName = doc.title.let {
            val base = if (it.endsWith(".pdf", ignoreCase = true)) it.dropLast(4) else it
            "${base}_edited.pdf"
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    saveUserMessage = getApplication<Application>().getString(R.string.msg_saving_protect_original)
                )
            }
            triggerSaveAsEvent.send(suggestedName)
            onSuccess?.invoke()
        }
    }

    /**
     * 另存为新文档 (Export / Save As)
     */
    fun exportDocument(targetUri: Uri, onSuccess: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        val sourceUri = _uiState.value.documentInfo?.uri ?: return
        if (_uiState.value.isSaving) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                // 另存为前主动清空已缓存的巨幅渲染位图并请求 GC，留出充足 Dalvik 堆内存供 PDFBox 处理
                repository.pdfEngine.clearMemoryCache()
                System.gc()

                repository.exportDocumentToUri(
                    sourceUri = sourceUri,
                    targetUri = targetUri,
                    annotations = _uiState.value.annotations,
                    pageSpecs = _uiState.value.pageSpecs
                )
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveUserMessage = getApplication<Application>().getString(R.string.msg_save_success)
                    )
                }
                onSuccess?.invoke()
            } catch (t: Throwable) {
                val app = getApplication<Application>()
                val msg = when (t) {
                    is OutOfMemoryError -> app.getString(R.string.msg_save_oom)
                    else -> app.getString(R.string.msg_save_failed, t.localizedMessage ?: t.message ?: "")
                }
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveUserMessage = msg
                    )
                }
                onError?.invoke(msg)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.pdfEngine.close()
    }
}
