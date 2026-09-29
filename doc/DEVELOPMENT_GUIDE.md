# Lumina Reader - 开发者指南与贡献规范

> 本指南旨在帮助开发者快速搭建开发环境、理解工程规范，并参与到 Lumina Reader 的演进中。

---

## 1. 开发环境要求

- **操作系统**：Linux、macOS 或 Windows (WSL2 / 原生)
- **JDK**：Java 17 或 21（推荐 OpenJDK 17/21 或 Temurin）
- **Android Studio**：Ladybug (2024.2+)、Meerkat 或更高版本
- **Android SDK**：
  - Compile SDK: `36` (Android 16 / Baklava)
  - Target SDK: `36`
  - Min SDK: `26` (Android 8.0 Oreo)
- **NDK**：无需独立安装 NDK，核心算法已完成纯 Kotlin 高性能重写

---

## 2. 本地构建与运行

克隆代码并进入项目目录：

```bash
git clone https://github.com/your-username/lumina-pdf.git
cd lumina-pdf
```

### 常用 Gradle 命令

```bash
# 赋予 gradlew 执行权限 (Unix / macOS)
chmod +x gradlew

# 编译 Debug APK
./gradlew assembleDebug

# 运行单元测试
./gradlew test

# 代码静态检查 (Lint)
./gradlew lintDebug

# 清理构建缓存
./gradlew clean
```

生成的安装包位于：`app/build/outputs/apk/debug/app-debug.apk`。

---

## 3. 代码架构与研发准则

为保持项目极简、流畅与高品质的设计语言，请遵循以下开发准则：

### 3.1 严格遵循 Material 3 色彩规范
- **禁止硬编码色彩**：绝对不要在任何 Composable 视图中直接写 `Color(0xFF...)` 或硬编码 `Color.White`、`Color.Black`；
- **使用语义色彩槽**：所有界面元素统一从 `MaterialTheme.colorScheme` 获取，例如：
  - 页面背景使用 `MaterialTheme.colorScheme.background`
  - 卡片表面使用 `MaterialTheme.colorScheme.surface`
  - 次级辅助表面使用 `MaterialTheme.colorScheme.surfaceVariant`
  - 主高亮与进度条使用 `MaterialTheme.colorScheme.primary`
  - 描边线使用 `MaterialTheme.colorScheme.outlineVariant`

### 3.2 响应式与单向数据流 (UDF)
- UI 必须是纯展示层，所有状态由 `ViewerViewModel` 通过 `StateFlow` 单向分发；
- 界面事件通过 ViewModel 公开方法触发，避免在 Composable 内部直接操作数据库或文件 IO。

### 3.3 线程调度与内存防爆
- 所有 PDF 渲染解码、位图缩放、文件描述符读取，必须置于 `Dispatchers.IO`；
- 避免持有长生命周期的 `Bitmap` 引用，渲染完成的位图交由 `BitmapLruCache` 管理，防止连续翻页导致 OOM。

---

## 4. Git 提交与 PR 规范

推荐遵循 [Conventional Commits](https://www.conventionalcommits.org/) 规范提交 Commit 信息：

- `feat:` 新增特性（例如：`feat: add amoled dark theme and bottom sheet`）
- `fix:` 修复缺陷（例如：`fix: prevent status bar flicker on cold start`）
- `docs:` 文档变更（例如：`docs: update architecture design spec`）
- `refactor:` 代码重构（不影响功能的结构优化）
- `perf:` 性能提升（例如：`perf: optimize PageCropper2 bit-scan speed`）
- `style:` 代码格式与排版调整（不影响逻辑）

欢迎提交 Issue 与 Pull Request！
