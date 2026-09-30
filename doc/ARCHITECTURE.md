# Lumina Reader - 系统架构与技术规范

> **工程定位**：面向 Android 16+ 的下一代极简高性能 PDF 阅读器  
> **核心哲学**：极致纯粹 · 飞速渲染 · 智能白边裁切 2.0 · 零权限 SAF · 16KB Page Size 兼容 · 沉浸无边阅读  
> **语言版本**：**简体中文** | [English](file:///home/eddy/myplace/project/lumina-pdf/doc/ARCHITECTURE_EN.md)

---

## 1. 总体架构设计

Lumina 遵循清晰的分层架构（Layered Architecture）与单向数据流（Unidirectional Data Flow, UDF），保证了高内聚与低耦合：

```mermaid
graph TD
    subgraph UI 表现层 (Jetpack Compose + Material 3)
        MainActivity[MainActivity: 全屏 Edge-to-Edge / Intent 调度 / 预测性返回]
        ShelfScreen[ShelfScreen: 极简响应式书架 / 独立操作菜单]
        ViewerScreen[ViewerScreen: 瀑布流或横向翻页 / 手势交互 / 全屏沉浸]
        ThemeSheet[ThemeSettingsBottomSheet: 外观与个性化面板]
    end

    subgraph ViewModel 状态中枢 (MVI / StateFlow)
        VM[ViewerViewModel: UI 状态流转 / 跨屏生命周期协调]
        UIState[ViewerUiState: 页码/缩放/裁切/滤镜/抽屉/全屏/横屏/排版]
        ThemeState[ThemeSettings: 主题模式与深色质感]
    end

    subgraph Domain 领域核心层 (Core)
        Cropper[PageCropper2: 智能白边裁切 2.0 与双栏定位]
        Engine[PdfEngine 抽象接口]
        PdfRendererImpl[AndroidPdfRendererEngine 原生渲染内核]
        Cache[BitmapLruCache: 内存防爆位图复用池]
    end

    subgraph Data 数据持久化与系统交互层 (Data)
        Repo[DocumentRepository: 文档管理中枢]
        SAF[Storage Access Framework: ParcelFileDescriptor 流式管道]
        HistoryDB[HistoryDatabase: 原生零依赖 SQLite 历史记录]
        ThemePref[ThemePreferences: 轻量主题偏好管理]
    end

    MainActivity --> ShelfScreen
    MainActivity --> ViewerScreen
    ShelfScreen --> ThemeSheet
    ShelfScreen --> VM
    ViewerScreen --> VM
    VM --> UIState
    VM --> ThemeState
    VM --> Repo
    Repo --> Engine
    Engine --> PdfRendererImpl
    Engine --> Cropper
    Engine --> Cache
    Repo --> SAF
    Repo --> HistoryDB
    VM --> ThemePref
```

---

## 2. 核心子系统剖析

### 2.1 智能白边裁切 2.0 (`core/crop/PageCropper2.kt`)
- **算法前身**：脱胎自著名阅读器 EBookDroid 的原生 C 语言 `PageCropper.c`；
- **纯 Kotlin 重构优势**：
  - 传统 NDK C 库在 Android 15/16 面对 16KB Page Size 架构时，面临 ELF 段对齐崩溃（`dlopen failed: 16KB segment alignment`）的风险；
  - `PageCropper2` 采用纯 Kotlin 编写，利用现代 JVM JIT 优化与位运算展开，对 400px 采样子图的四向差分探测耗时在现代 SoC 上 **小于 1.5ms**；
- **自适应边界与全白页面防误裁**：
  - 四向步进探测：扫描未触及非白内容时，安全返回边界原点 `0f` 或终点 `1f`，杜绝空白页误裁为局部残片的边界漂移；
- **双栏学术论文自动聚焦 (`calculateColumnBounds`)**：
  - 基于全局页面背景平均亮度基准（`max(200, calculateAvgLum)`），解决点击黑色文字局部导致亮度差分失效的问题；
  - 自动向两翼扫描中缝空白，定位正文单栏的最佳视口坐标，使学术论文阅读免于左右手动反复拖动。

### 2.2 现代化渲染内核与内存防护 (`core/engine` & `core/cache`)
- **零依赖原生引擎**：基于 `android.graphics.pdf.PdfRenderer`，无需集成体积动辄 15MB~30MB 的第三方 C++ 渲染库，包体积压缩至 **5MB 以内**；
- **并发锁与线程隔离**：`PdfRenderer` 原生不支持并发渲染单页。`AndroidPdfRendererEngine` 内部采用轻量级 `Mutex` 结合协程 `withContext(Dispatchers.IO)`，确保页面渲染绝对线程安全；
- **防 OOM 复用池 (`BitmapLruCache`)**：
  - 计算设备可用内存的 25% 作为上限；
  - 自动将离开视口的旧页面位图加入复用池，杜绝快速翻页时的频繁 GC 掉帧与内存抖动。

### 2.3 手势交互与触控冲突消解 (`ui/viewer/ViewerScreen.kt`)
- **多层级手势判定流水线**：
  - **单指未缩放状态（`scale <= 1.05f`）**：手势处理器不消费任何 Touch Pointer 事件，将拖拽滑动无损传递给外层滚动容器（纵向 `LazyColumn` 或横向 `HorizontalPager`），实现极致顺滑的滑动与翻页体验；
  - **双指捏合（Pinch-to-Zoom）**：捕获双指触控计算 `calculateZoom()`，支持 1.0x ~ 4.0x 连续缩放，且在缩放期间消费手势以锁定滚动容器；
  - **放大后单指拖拽**：在 `scale > 1.05f` 时接管单指位移，在当前放大页面视图边界内进行平移，并限制最大位移避免漂出屏幕；
  - **单击（Single Tap）**：无论点击页面正文还是边缘间隙，灵敏唤出或隐藏悬浮菜单与控制面板；
  - **双击（Double Tap）**：放大状态下双击一键复位至 1.0x 原始比例；原始比例下双击触发双栏论文单栏聚焦。

### 2.4 全屏沉浸式阅读架构 (`ui/viewer` & `MainActivity`)
- **系统栏动态接管**：
  - 基于 `WindowCompat.getInsetsController` 实现沉浸式全屏切换；
  - 设置行为模式为 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`，全屏时完全隐藏系统状态栏与底部手势导航条，从屏幕边缘向内滑动时临时浮出半透明状态条并随后自动隐藏；
- **分级预测性返回拦截（Predictive Back）**：
  - 第一优先级：目录大纲抽屉打开时，拦截返回键关闭抽屉；
  - 第二优先级：沉浸全屏模式开启时，拦截返回键退出全屏、恢复系统状态栏；
  - 第三优先级：退出阅读界面，返回主书架，并自动恢复系统默认屏幕方向与状态栏显示。

### 2.5 横屏与多元排版翻页体系
- **屏幕方向随心控制**：
  - 提供一键横屏/竖屏切换开关，切换横屏时启用 `ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE`（双向重力感应横屏支持），退出时安全恢复 `SCREEN_ORIENTATION_UNSPECIFIED`；
- **双排版阅读引擎**：
  - `ReadingLayoutMode.CONTINUOUS_VERTICAL`：连续纵向瀑布流，利用 `LazyColumn` 实现行云流水的上下滑动；
  - `ReadingLayoutMode.SINGLE_PAGE_HORIZONTAL`：单页横向左右翻页，基于 Compose `HorizontalPager` 实现仿真书本翻页；
  - 两者切换时状态无缝同步，当前阅读进度页码毫厘不差；
- **自适应动态分辨率重绘**：
  - 页面渲染监听屏幕方向、宽度与排版模式变化，旋转至横屏或切换单页时自动按照当前宽高比与屏幕尺寸重新渲染最高画质位图。

### 2.6 零权限存储与历史管道 (`data`)
- **现代 SAF 架构**：应用无需声明 `READ_EXTERNAL_STORAGE` 或 `MANAGE_EXTERNAL_STORAGE` 权限；
- 通过 `ActivityResultContracts.OpenDocument` 或系统的 `ACTION_VIEW` Intent 获得 `content://` 授权 Uri，直接获取 `ParcelFileDescriptor`。
- **免 KSP 原生 SQLite 持久化 (`HistoryDatabase`)**：
  - 使用轻量原生 `SQLiteOpenHelper` 搭配 `StateFlow` 实现全响应式数据流；
  - 规避了 Room 与 KSP 编译插件在不同 Kotlin/AGP 大版本间的配置冲突，极速编译。

### 2.7 主题与外观子系统 (`ui/theme` & `ui/settings`)
- 详见专题白皮书：[THEME_DESIGN.md](file:///home/eddy/myplace/project/lumina-pdf/doc/THEME_DESIGN.md)。
- 实现了跟随系统、日间浅色、夜间板岩灰、AMOLED 纯黑四维矩阵，全屏状态栏与导航栏无感自适应。

---

## 3. 代码模块目录结构

```
app/src/main/
├── AndroidManifest.xml              # 清单配置 (Edge-to-Edge 沉浸、uiMode 变更、PDF Intent-Filter)
├── java/org/lumina/reader/
│   ├── MainActivity.kt              # 单 Activity 现代架构，预测性返回与全屏驱动
│   ├── core/
│   │   ├── crop/
│   │   │   └── PageCropper2.kt      # 白边裁切 2.0 与双栏定位核心算法
│   │   ├── engine/
│   │   │   ├── PdfEngine.kt         # 渲染引擎契约接口
│   │   │   └── AndroidPdfRendererEngine.kt # 原生渲染引擎实现
│   │   ├── cache/
│   │   │   └── BitmapLruCache.kt    # 位图防爆 LRU 缓存复用
│   │   └── model/
│   │       └── PdfModels.kt         # 几何尺寸、阅读滤镜与排版模型
│   ├── data/
│   │   ├── db/
│   │   │   ├── HistoryDatabase.kt   # 历史阅读与置顶记录数据库
│   │   │   └── RecentDocument.kt    # 书架文档数据实体
│   │   ├── preferences/
│   │   │   └── ThemePreferences.kt  # 外观与暗黑模式持久化
│   │   └── repository/
│   │       └── DocumentRepository.kt# SAF 管道中枢
│   └── ui/
│       ├── settings/
│       │   └── ThemeSettingsBottomSheet.kt # 现代 M3 外观设置面板
│       ├── shelf/
│       │   └── ShelfScreen.kt       # 极简响应式书架与卡片独立交互
│       ├── viewer/
│       │   ├── ViewerScreen.kt      # 瀑布流/横向翻页阅读主屏、手势交互与全功能菜单
│       │   └── ViewerViewModel.kt   # 统一视图状态流转 ViewModel
│       └── theme/
│           ├── Color.kt             # Lumina 核心色彩与滤镜色槽
│           ├── Theme.kt             # Material 3 调色板装配与沉浸式系统栏
│           └── Type.kt              # 现代化字体排印系统
└── res/
    ├── values/themes.xml            # 日间模式初始主题
    └── values-night/themes.xml      # 暗黑模式夜间主题 (防止冷启动闪白)
```
