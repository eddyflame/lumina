package org.lumina.reader.data.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用主题模式
 */
enum class AppThemeMode {
    /** 跟随系统设置 */
    SYSTEM,
    /** 浅色明亮模式 */
    LIGHT,
    /** 夜间暗黑模式 */
    DARK
}

/**
 * 深色主题质感风格
 */
enum class DarkThemeStyle {
    /** 板岩深蓝灰 (Slate, 柔和护眼、层次细腻, #0F172A) */
    SLATE,
    /** 极致纯黑 (AMOLED, 纯黑像素、高反差、极致省电, #000000) */
    AMOLED
}

/**
 * 主题与视觉偏好状态
 */
data class ThemeSettings(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val darkThemeStyle: DarkThemeStyle = DarkThemeStyle.SLATE
)

/**
 * 主题偏好设置管理器
 *
 * 采用原生 SharedPreferences + StateFlow 实现，零第三方库开销，启动瞬间无缝加载
 */
class ThemePreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeSettings = MutableStateFlow(loadSettings())
    val themeSettings: StateFlow<ThemeSettings> = _themeSettings.asStateFlow()

    private fun loadSettings(): ThemeSettings {
        val modeStr = prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.name) ?: AppThemeMode.SYSTEM.name
        val styleStr = prefs.getString(KEY_DARK_STYLE, DarkThemeStyle.SLATE.name) ?: DarkThemeStyle.SLATE.name

        val mode = runCatching { AppThemeMode.valueOf(modeStr) }.getOrDefault(AppThemeMode.SYSTEM)
        val style = runCatching { DarkThemeStyle.valueOf(styleStr) }.getOrDefault(DarkThemeStyle.SLATE)

        return ThemeSettings(themeMode = mode, darkThemeStyle = style)
    }

    fun setThemeMode(mode: AppThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeSettings.value = _themeSettings.value.copy(themeMode = mode)
    }

    fun setDarkThemeStyle(style: DarkThemeStyle) {
        prefs.edit().putString(KEY_DARK_STYLE, style.name).apply()
        _themeSettings.value = _themeSettings.value.copy(darkThemeStyle = style)
    }

    companion object {
        private const val PREFS_NAME = "lumina_theme_prefs"
        private const val KEY_THEME_MODE = "key_theme_mode"
        private const val KEY_DARK_STYLE = "key_dark_style"
    }
}
