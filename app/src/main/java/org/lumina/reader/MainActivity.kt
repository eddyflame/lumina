package org.lumina.reader

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.lumina.reader.ui.shelf.ShelfScreen
import org.lumina.reader.ui.theme.LuminaTheme
import org.lumina.reader.ui.viewer.ViewerScreen
import org.lumina.reader.ui.viewer.ViewerViewModel

class MainActivity : ComponentActivity() {

    private val viewerViewModel: ViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启用 Android 15/16 推荐的强制沉浸式全屏布局 (Edge-to-Edge)
        enableEdgeToEdge()

        setContent {
            val themeSettings by viewerViewModel.themeSettings.collectAsState()
            val viewerUiState by viewerViewModel.uiState.collectAsState()

            LuminaTheme(
                themeMode = themeSettings.themeMode,
                darkThemeStyle = themeSettings.darkThemeStyle
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var currentDocumentUri by remember { mutableStateOf<Uri?>(null) }

                    // 预测性返回拦截：优先关闭大纲/退出全屏，最后退出阅读器
                    BackHandler(enabled = currentDocumentUri != null) {
                        if (viewerUiState.isOutlineDrawerOpen) {
                            viewerViewModel.setOutlineDrawerOpen(false)
                        } else if (viewerUiState.isFullscreen) {
                            viewerViewModel.setFullscreen(false)
                        } else {
                            currentDocumentUri = null
                        }
                    }

                    // 退出阅读器回到书架时，恢复系统状态栏与屏幕方向
                    LaunchedEffect(currentDocumentUri) {
                        if (currentDocumentUri == null) {
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            val window = this@MainActivity.window
                            val controller = WindowCompat.getInsetsController(window, window.decorView)
                            controller.show(WindowInsetsCompat.Type.systemBars())
                        }
                    }

                    // 监听外部应用 Intent 传入的 PDF 文件 (例如微信、邮件、第三方文件管理器)
                    LaunchedEffect(intent) {
                        handleIncomingIntent(intent) { uri ->
                            currentDocumentUri = uri
                            viewerViewModel.openDocument(uri)
                        }
                    }

                    if (currentDocumentUri != null) {
                        ViewerScreen(
                            viewModel = viewerViewModel,
                            onBackToShelf = {
                                viewerViewModel.resetViewerModes()
                                currentDocumentUri = null
                            }
                        )
                    } else {
                        ShelfScreen(
                            viewModel = viewerViewModel,
                            onOpenPdfUri = { uri ->
                                currentDocumentUri = uri
                                viewerViewModel.openDocument(uri)
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent) { uri ->
            viewerViewModel.openDocument(uri)
        }
    }

    private fun handleIncomingIntent(intent: Intent?, onUriReceived: (Uri) -> Unit) {
        if (intent == null) return
        val action = intent.action
        val data = intent.data
        if (Intent.ACTION_VIEW == action && data != null) {
            onUriReceived(data)
        }
    }
}
