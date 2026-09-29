# Lumina Reader - 系统架构与技术规范

> **工程定位**：面向 Android 16+ 的下一代极简高性能 PDF 阅读器  
> **核心哲学**：极致纯粹 · 飞速渲染 · 智能白边裁切 2.0 · 零权限 SAF · 16KB Page Size 兼容

---

## 1. 总体架构设计

Lumina 遵循清晰的分层架构（Layered Architecture）与单向数据流（Unidirectional Data Flow, UDF），保证了高内聚与低耦合：

```mermaid
graph TD
    subgraph UI 表现层 (Jetpack Compose + Material 3)
        MainActivity[MainActivity: 全屏 Edge-to-Edge / Intent 调度]
        ShelfScreen[ShelfScreen: 极简响应式书架 / 快捷打开]
        ViewerScreen[ViewerScreen: 瀑布流页面 / 手势交互 / 悬浮控制台]
        ThemeSheet[ThemeSettingsBottomSheet: 外观与个性化面板]
    end

    subgraph ViewModel 状态中枢 (MVI / StateFlow)
        VM[ViewerViewModel: UI 状态流转 / 跨屏生命周期协调]
        UIState[ViewerUiState: 页面索引/缩放/裁切/滤镜/抽屉]
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
- **双栏学术论文自动聚焦 (`calculateColumnBounds`)**：
  - 自动识别用户点击区域（左栏或右栏）；
  - 向两翼扫描空白留白中缝，计算出正文单栏的最佳视口坐标，使学术论文阅读免于左右反复手动拖动。

### 2.2 现代化渲染内核与内存防护 (`core/engine` & `core/cache`)
- **零依赖原生引擎**：基于 `android.graphics.pdf.PdfRenderer`，无需集成体积动辄 15MB~30MB 的第三方 C++ 渲染库，包体积压缩至 **5MB 以内**；
- **并发锁与线程隔离**：`PdfRenderer` 原生不支持并发渲染单页。`AndroidPdfRendererEngine` 内部采用轻量级 `Mutex` 结合协程 `withContext(Dispatchers.IO)`，确保页面渲染绝对线程安全；
- **防 OOM 复用池 (`BitmapLruCache`)**：
  - 计算设备可用内存的 25% 作为上限；
  - 自动将离开视口的旧页面位图加入复用池，杜绝快速翻页时的频繁 GC 掉帧与内存抖动。

### 2.3 零权限存储与历史管道 (`data`)
- **现代 SAF 架构**：应用无需声明 `READ_EXTERNAL_STORAGE` 或 `MANAGE_EXTERNAL_STORAGE` 权限；
- 通过 `ActivityResultContracts.OpenDocument` 或系统的 `ACTION_VIEW` Intent 获得 `content://` 授权 Uri，直接获取 `ParcelFileDescriptor`。
- **免 KSP 原生 SQLite 持久化 (`HistoryDatabase`)**：
  - 使用轻量原生 `SQLiteOpenHelper` 搭配 `StateFlow` 实现全响应式数据流；
  - 规避了 Room 与 KSP 编译插件在不同 Kotlin/AGP 大版本间的配置冲突，极速编译。

### 2.4 主题与外观子系统 (`ui/theme` & `ui/settings`)
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
│       │   └── ShelfScreen.kt       # 极简响应式书架
│       ├── viewer/
│       │   ├── ViewerScreen.kt      # 瀑布流阅读主屏与手势交互
│       │   └── ViewerViewModel.kt   # 统一视图状态流转 ViewModel
│       └── theme/
│           ├── Color.kt             # Lumina 核心色彩与滤镜色槽
│           ├── Theme.kt             # Material 3 调色板装配与沉浸式系统栏
│           └── Type.kt              # 现代化字体排印系统
└── res/
    ├── values/themes.xml            # 日间模式初始主题
    └── values-night/themes.xml      # 暗黑模式夜间主题 (防止冷启动闪白)
```
