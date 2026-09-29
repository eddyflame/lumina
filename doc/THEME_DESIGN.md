# Lumina Reader - 外观与暗黑主题架构设计白皮书

> **文档版本**：v1.1.0  
> **更新时间**：2026-09  
> **适用模块**：`org.lumina.reader.ui.theme`、`org.lumina.reader.data.preferences`、`org.lumina.reader.ui.settings`

---

## 1. 业务痛点与设计目标

### 1.1 PDF 阅读类应用的特殊挑战
在常规应用中，“暗黑主题”仅涉及应用界面控件（按钮、卡片、背景）的反色适配；然而对于 **PDF 阅读器**，存在两大核心差异痛点：
1. **页面内容强眩光**：PDF 文件通常是由渲染器（如 Android `PdfRenderer`）实时栅格化为 Bitmap。即便外壳（Toolbar / Scaffold）是深黑色，PDF 页面正文依然是高亮白底黑字，夜间阅读时极易刺眼眩目。
2. **彩色图表失真**：若简单采用粗暴的色彩反转（Invert Color Matrix），学术论文或教材中的彩色示意图、照片会变成惨白的负片，破坏阅读体验。
3. **不同屏幕材质偏好**：
   - **普通 LCD / 护眼诉求**：偏好柔和的深灰 / 板岩色，避免纯黑与白字之间对比度过高导致视觉疲劳；
   - **AMOLED 屏幕 / 极客省电**：偏好绝对纯黑（`#000000`），实现屏幕像素完全熄灭与零功耗。

### 1.2 核心设计目标
- **双模深色质感**：同源提供 **板岩深灰 (Slate Charcoal)** 与 **极致纯黑 (AMOLED Pure Black)** 两种深色质感供用户随时切换；
- **优雅 M3 底部设置面板**：提供现代化的 `ModalBottomSheet` 交互入口，并预留后续排版与功能配置槽位；
- **全链路沉浸 (Edge-to-Edge)**：状态栏与手势导航栏图标根据深浅模式自动反转，冷启动杜绝闪白；
- **轻量零依赖持久化**：采用 `SharedPreferences` + `StateFlow`，启动瞬间同步还原，零外部库依赖。

---

## 2. 整体架构与状态流转

```mermaid
graph TD
    subgraph 偏好存储层 (Preferences)
        SP[SharedPreferences 本地磁盘] -->|初始化无缝加载| TP[ThemePreferences 管理器]
        UserAction[用户切换模式 / 风格] -->|写回| TP
        TP -->|暴露响应式流| ThemeStateFlow[StateFlow&lt;ThemeSettings&gt;]
    end

    subgraph 表现层 (ViewModel & Activity)
        ThemeStateFlow --> VM[ViewerViewModel]
        VM --> MainActivity[MainActivity 根容器]
    end

    subgraph 设计系统容器 (LuminaTheme)
        MainActivity --> LuminaTheme[LuminaTheme 组合项]
        Sys[isSystemInDarkTheme 探测] --> LuminaTheme
        LuminaTheme --> ThemeLogic{模式决断: 跟随系统/浅色/深色}
        ThemeLogic --> StyleLogic{深色风格: 板岩深灰 vs AMOLED纯黑}
        StyleLogic --> M3Color[MaterialTheme.colorScheme]
        LuminaTheme --> InsetsController[WindowInsetsControllerCompat 状态栏图标自适应]
    end

    subgraph 消费组件 (UI Screens)
        M3Color --> Shelf[ShelfScreen 书架: 微边框/语义卡片]
        M3Color --> Viewer[ViewerScreen 阅读器: 半透明沉浸工具条]
        M3Color --> BottomSheet[ThemeSettingsBottomSheet 底部面板]
    end
```

---

## 3. 配色规范与色彩空间映射

Lumina 建立在 Material 3 设计系统之上，完全杜绝在具体 UI 页面硬编码 RGB 色值，统一由 `MaterialTheme.colorScheme` 语义插槽分发：

### 3.1 调色板映射对照表

| 语义角色 (Semantic Role) | 日间浅色 (`LightColorScheme`) | 板岩深灰 (`SlateDarkColorScheme`) | 极致纯黑 (`AmoledDarkColorScheme`) | 视觉用途 |
| :--- | :--- | :--- | :--- | :--- |
| **`primary`** | `#0284C7` (Lumina Azure) | `#38BDF8` (Lumina Sky) | `#38BDF8` (Lumina Sky) | 核心强调色、FAB、进度条、激活指示器 |
| **`onPrimary`** | `#FFFFFF` | `#00354E` | `#00354E` | 主强调色上的文字与图标 |
| **`primaryContainer`** | `#E0F2FE` (天蓝微光) | `#075985` (深邃幽蓝) | `#0369A1` (深空曜蓝) | 置顶卡片背景、高亮胶囊徽章 |
| **`onPrimaryContainer`** | `#0369A1` | `#E0F2FE` | `#E0F2FE` | 置顶卡片强调文字、徽章内文字 |
| **`background`** | `#F8FAFC` (极浅云灰) | `#020617` (星空墨黑) | `#000000` (AMOLED 纯黑) | 应用全屏基底、阅读器底层画板 |
| **`surface`** | `#FFFFFF` | `#0F172A` (深板岩灰) | `#0C0C0E` (微曜黑) | 卡片表面、底部弹窗面板、大纲抽屉 |
| **`surfaceVariant`** | `#F1F5F9` | `#1E293B` | `#18181B` | 未选中卡片底色、进度条轨道、空态占位 |
| **`onSurface`** | `#0F172A` | `#F8FAFC` | `#FFFFFF` | 页面一级主标题、正文文本 |
| **`onSurfaceVariant`** | `#64748B` | `#94A3B8` | `#A1A1AA` | 副标题、页码信息、微弱辅助提示 |
| **`outlineVariant`** | `#E2E8F0` | `#1E293B` (深色微边框) | `#1F1F23` (纯黑微边框) | 卡片描边，防止深色模式下卡片粘连 |

---

## 4. 关键技术方案实现

### 4.1 双层深色体验（UI 外壳 + PDF 内容滤镜）
1. **外壳层 (Shell Layer)**：
   - 包含书架、大纲抽屉、悬浮工具栏、翻页指示器；
   - 随 `LuminaTheme` 响应式切换，透明度与高斯拟态（Blur）在深色模式下表现极佳。
2. **渲染内容层 (Document Filter Layer)**：
   - 独立提供 `ReadingColorMode` 控制矩阵：
     - `NORMAL`：原版色彩（适用于日光环境）；
     - `NIGHT_INVERT`：高对比反相矩阵（`floatArrayOf(-1, 0, 0, 0, 255, ...)`），纯黑底 AMOLED 阅读；
     - `SEPIA`：乘法混合温润羊皮纸滤镜（护眼模式，`#FBF0D9` + 暖棕文字）。

### 4.2 状态栏与导航栏图标自适应
针对 Android 15/16 强制 Edge-to-Edge 的要求，在 `LuminaTheme` 内部使用 `SideEffect` 挂载系统栏图标深浅控制器：
```kotlin
val view = LocalView.current
if (!view.isInEditMode) {
    SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }
}
```

### 4.3 冷启动与配置变更优化
1. **防闪白**：在 `res/values-night/themes.xml` 中将窗口初始背景定为深色，`windowLightStatusBar` 设为 `false`；
2. **免重启平滑切换**：在 `AndroidManifest.xml` 的 `MainActivity` 节点中将 `uiMode` 加入 `configChanges`：
   ```xml
   android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|uiMode"
   ```
   当系统触发全局暗黑切换时，Activity 无需销毁重建，Compose 毫秒级重组刷新。

---

## 5. 交互扩展指南（给后续开发者的指引）

外观设置面板采用抽屉组件 [ThemeSettingsBottomSheet.kt](file:///home/eddy/myplace/project/lumina-pdf/app/src/main/java/org/lumina/reader/ui/settings/ThemeSettingsBottomSheet.kt)。如需后续添加新功能（例如：字号预设、翻页动画效果、默认裁切模式等）：

1. 在 `ThemePreferences` / `ThemeSettings` 中添加新的偏好字段与存取方法；
2. 在 `ViewerViewModel` 代理相应状态流；
3. 直接在 `ThemeSettingsBottomSheet` 的 `Column` 下新增对应的 `Section` 区域与 `OptionCard` 组件即可，UI 保持高度一致的设计语言与平滑动效。
