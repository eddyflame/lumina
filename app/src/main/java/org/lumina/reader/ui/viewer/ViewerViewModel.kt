package org.lumina.reader.ui.viewer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lumina.reader.core.crop.PageCropper2
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
    val errorMessage: String? = null
)

class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DocumentRepository(application)
    private val themePreferences = ThemePreferences(application)

    val recentDocuments: StateFlow<List<RecentDocument>> = repository.recentDocuments
    val themeSettings: StateFlow<ThemeSettings> = themePreferences.themeSettings

    fun setThemeMode(mode: AppThemeMode) {
        themePreferences.setThemeMode(mode)
    }

    fun setDarkThemeStyle(style: DarkThemeStyle) {
        themePreferences.setDarkThemeStyle(style)
    }

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    fun openDocument(uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    activeColumnBounds = null,
                    isFullscreen = false,
                    isLandscape = false
                )
            }
            try {
                val doc = repository.openDocument(uri)
                val outlines = repository.pdfEngine.getOutlines()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        documentInfo = doc,
                        currentPageIndex = doc.initialPage,
                        outlines = outlines,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "打开文档失败: ${e.localizedMessage ?: e.message}"
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

    fun clearAllHistory() {
        repository.clearAllHistory()
    }

    override fun onCleared() {
        super.onCleared()
        repository.pdfEngine.close()
    }
}
