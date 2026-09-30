# Lumina Reader - 开发者指南与工程贡献规范 (Development Guide)

> 本指南旨在帮助开发者快速搭建本地开发环境、理解 Lumina Reader 的现代化架构理念、掌握各项构建与测试命令，并遵循规范参与项目的演进与贡献。  
> **语言版本 (Language)**：**简体中文** | [English Edition](DEVELOPMENT_GUIDE_EN.md)

---

## 1. 当前项目概览与技术规格

Lumina Reader 是专为 Android 16+ 打造的下一代极简高性能 PDF 阅读器，采用现代 Android 顶级工具链开发：

- **系统平台**：Android 16+ (Baklava / API 36 Ready)，向下兼容至 Android 8.0 (API 26)
- **编程语言**：Kotlin `2.2.20` + Compose Compiler 插件
- **构建工具链**：Gradle `9.6+` + Android Gradle Plugin (AGP) `9.4.1`
- **UI 框架**：Jetpack Compose + Material 3 (1.3.1) 全响应式流式布局
- **异步调度**：Kotlin Coroutines `1.9.0` + StateFlow 响应式状态流
- **PDF 引擎体系**：
  - **快速渲染内核**：基于 Android 原生现代化 `android.graphics.pdf.PdfRenderer`，极轻包体积与系统级硬件加速；
  - **标准导出与编辑引擎**：基于纯 JVM 的 Apache PDFBox Android (`com.tom-roush:pdfbox-android:2.0.27.0`)，实现批注标准回写、`/AP` 外观流构建与物理页面重构；
- **16KB Page Size 架构**：全项目为 **纯 JVM + Android Framework 原生架构**，无任何第三方 C/C++ 本地动态库 (`.so`)，**天然免疫 Android 15/16 上的 16KB 内存分页对齐崩溃**；
- **存储权限**：严格遵循 Scoped Storage（分区存储）规范，**零危险权限申请**，完全依赖 Storage Access Framework (SAF)。

---

## 2. 开发环境准备

### 2.1 基础软硬件依赖
- **操作系统**：Linux (Ubuntu 22.04+/24.04+)、macOS (Apple Silicon / Intel) 或 Windows (原生或 WSL2)；
- **JDK 版本**：Java 17 或 Java 21 (推荐 Eclipse Temurin 21 或 Android Studio 自带的 JBR)；
- **IDE 推荐**：Android Studio Meerkat (2024.3+)、Ladybug (2024.2+) 或更高版本；
- **Android SDK**：
  - Compile SDK: `36` (Android 16)
  - Target SDK: `36`
  - Min SDK: `26`
  - 需在 SDK Manager 中安装 `Android SDK Platform 36` 与 `Android SDK Build-Tools 36.x`。

### 2.2 环境变量配置
在 `~/.bashrc` 或 `~/.zshrc` 中确保配置了 `JAVA_HOME` 和 `ANDROID_HOME`：

```bash
# 示例配置 (根据本地实际路径微调)
export JAVA_HOME="/path/to/jdk-21"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

---

## 3. 本地构建、测试与运行

### 3.1 获取源码
```bash
git clone https://github.com/your-username/lumina-pdf.git
cd lumina-pdf
```

### 3.2 常用 Gradle 命令

```bash
# 赋予 gradlew 执行权限 (Unix / macOS)
chmod +x gradlew

# 编译 Debug APK
./gradlew assembleDebug

# 运行全量单元测试
./gradlew test

# 执行特定单元测试类
./gradlew testDebugUnitTest --tests "org.lumina.reader.core.export.PdfDocumentExporterTest"

# 执行代码静态质量检查 (Android Lint)
./gradlew lintDebug

# 清理构建缓存与产物
./gradlew clean
```

> **产物路径**：编译成功的 Debug 安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。

### 3.3 单元测试覆盖
项目目前已建立完整的核心算法与导出引擎单元测试，修改相关代码后请务必运行测试以确保零回归：
- **`PageCropper2Test`**：验证智能白边四向差分判定、空白容错、双栏学术论文中缝探测与深色背景下自适应计算；
- **`PdfDocumentExporterTest`**：验证标准 PDF 批注回存、荧光笔恒定透明度（`/CA`）、线宽、颜色矩阵与页面重构；
- **`AnnotationTest`**：验证墨水笔画添加、删除、清空以及 Undo/Redo 命令栈历史一致性；
- **`PageEditSpecTest`**：验证页面物理旋转角度计算、页面重排索引映射与页面物理剔除逻辑。

---

## 4. 架构规范与核心开发准则

为了保持代码的高度内聚、低耦合与极致流畅体验，请在编码过程中严格遵守以下原则：

### 4.1 严格遵循 Material 3 色彩体系
- **严禁硬编码颜色**：绝不允许在 Composable 函数中硬编码 `Color(0xFF...)` 或使用系统 `Color.Black` / `Color.White`；
- **统一使用语义色彩插槽**：
  - 背景画布：`MaterialTheme.colorScheme.background`
  - 卡片/表面容器：`MaterialTheme.colorScheme.surface`
  - 次级辅助表面：`MaterialTheme.colorScheme.surfaceVariant`
  - 核心品牌高亮：`MaterialTheme.colorScheme.primary`
  - 边界描边线：`MaterialTheme.colorScheme.outlineVariant`
- 所有色彩映射与夜间主题配置请参阅设计白皮书 [DESIGN.md](DESIGN.md)。

### 4.2 单向数据流 (UDF) 与状态集中分发
- **UI 纯展示**：Compose 视图禁止直接访问数据库、直接发起底层文件 IO 或持有业务持久状态；
- **ViewModel 统摄**：由 `ViewerViewModel` 统一管理 `StateFlow<ViewerUiState>`，界面通过公共方法触发 Intent 事件；
- **命令模式扩展**：新增任何批注操作必须实现 `AnnotationCommand` 接口，以便纳入 `AnnotationCommandManager` 撤销/重做栈。

### 4.3 线程安全与内存防爆规则
- **IO 线程隔离**：所有页面解码渲染、位图缩放、大纲解析及文件写入必须使用 `withContext(Dispatchers.IO)`；
- **并发互斥**：`PdfRenderer` 不支持并发访问，渲染调度需经由 `AndroidPdfRendererEngine` 内置的 `Mutex` 进行临界区保护；
- **位图生命周期回收**：位图由 `BitmapLruCache`（25% 堆内存上限）统一管理，避免长生命周期强引用导致连续滑动 OOM。

### 4.4 坐标系变换与多端一致性
- 手绘笔画在屏幕上以物理像素（px）绘制，存储和导出时必须通过 `PageCoordinateTransformer` 转换为标准 PDF 72 DPI 点阵（Point）坐标；
- 原点转换规则：屏幕以左上角为原点（Y 向下），PDF 物理空间以左下角为原点（Y 向上），转换时必须进行翻转。

### 4.5 文件安全写入事务
- 禁止对原始 SAF 文件描述符进行非事务性直接覆盖；
- 必须通过应用私有目录下的临时文件完成导出与校验，再以原子方式回写至目标管道，保障断电或崩溃时源文档绝对安全。

---

## 5. Git 提交与贡献规范

### 5.1 Conventional Commits 规范
提交信息（Commit Message）请统一使用动词前缀，格式如下：
`<type>(<scope>): <subject>`

- `feat`: 新增用户功能（如 `feat(export): 实现 PDF 批注回存与物理页面编辑导出功能`）
- `fix`: 修复缺陷（如 `fix(crop): 修复深色背景学术论文中缝探测偏差`）
- `docs`: 文档变更与维护（如 `docs: 更新系统架构设计文档与开发指南`）
- `refactor`: 代码重构（不改变功能与外部表现的代码调整）
- `perf`: 性能优化（如 `perf(engine): 优化 Bitmap 复用池置换策略`）
- `test`: 单元测试与测试用例新增或调整
- `chore`: 构建配置、依赖版本更新或环境工具调整

### 5.2 Pull Request (PR) 流程
1. Fork 本仓库并基于 `develop` 分支创建功能分支（如 `feature/my-feature`）；
2. 编写代码并补充相应的单元测试；
3. 本地执行 `./gradlew test` 确保全部测试通过；
4. 提交清晰规范的 Commit 并推送分支；
5. 向 `develop` 分支发起 Pull Request，并在描述中详述改动背景与自测结论。
