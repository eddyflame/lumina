# Lumina Reader (LuminaPDF) - 中文说明文档

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_16+_(Baklava)-0284C7?style=for-the-badge&logo=android&logoColor=white" alt="Android 16 Ready" />
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin 2.0" />
  <img src="https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Material 3" />
  <img src="https://img.shields.io/badge/Permissions-Zero_SAF-success?style=for-the-badge" alt="Zero Permissions" />
  <img src="https://img.shields.io/badge/License-GPL_v3.0-blue?style=for-the-badge" alt="License GPLv3" />
</p>

<p align="center">
  <b>下一代极简高性能 Android PDF 阅读器 · 专为 Android 16+ 现代体验打造</b><br/>
  <i>极速渲染 · 智能白边裁切 2.0 · 双模暗黑主题 · 零权限 SAF · Edge-to-Edge 沉浸美学</i>
</p>

<p align="center">
  <a href="../README.md">English</a> | 简体中文
</p>

---

## 🌟 核心特性亮点

### 🌓 全景暗黑与个性化外观 (Dual-Style Dark Theme)
- **多维度主题掌控**：支持 **跟随系统**、**日间浅色**、**夜间暗黑** 三大核心模式；
- **同源双深色质感**：
  - **板岩深灰 (Slate Charcoal)**：采用 `#0F172A` / `#1E293B` 色阶，层次细腻，护眼柔和；
  - **极致纯黑 (AMOLED Pure Black)**：采用 `#000000` / `#0C0C0E` 绝对全黑，实现 OLED 像素完全熄灭与零功耗；
- **现代 M3 底部设置面板**：书架顶栏轻触调出 `ThemeSettingsBottomSheet`，提供平滑流体交互，并为后续功能预留模块化扩展槽位；
- **全屏边到边沉浸 (Edge-to-Edge)**：状态栏与手势导航栏完全透明，图标明暗随主题自动毫秒级适配，冷启动内置 `values-night` 杜绝白屏闪烁。

### ✂️ 智能白边裁切 2.0 (Smart Auto-Crop 2.0)
- 传承并超越 EBookDroid 经典的四向亮度差分与分栏探测算法；
- 纯 Kotlin 位运算高性能重构，无需任何 C/C++ 动态链接库，子图探测耗时 **< 1.5ms**；
- 智能识别并自动切除扫描件、书籍与学术论文四周空白边缘，使正文有效显示面积扩大 **25% ~ 35%**；
- **双栏论文单栏聚焦**：轻触双栏文献正文，智能识别左右栏边界并全屏居中放大，免除繁琐的手动左右平移。

### ⚡ 原生 16KB Page Size 架构 (Android 15 & 16 Ready)
- 采用 Android 官方现代化 `PdfRenderer` + 协程调度流水线；
- 100% 避免老旧 NDK 动态库在 16KB 内存分页系统下的段对齐崩溃（`dlopen failed: 16KB segment alignment`）；
- 包体积控制在 **5MB 以内**，启动秒开，内存占用克制。

### 🛡️ 纯粹 SAF 零权限设计
- 严格遵循现代 Android 分区存储规范，**无需申请 `MANAGE_EXTERNAL_STORAGE` 或读写权限**；
- 基于 Storage Access Framework，无缝支持系统文件选择器、下载管理器、微信聊天文件与邮件附件直接调用。

---

## 🏗️ 架构概览

```
org.lumina.reader
├── core
│   ├── crop         # PageCropper2: 白边自适应裁切与双栏定位核心算法
│   ├── engine       # PdfEngine 抽象契约与 AndroidPdfRendererEngine 原生实现
│   ├── cache        # BitmapLruCache: 内存防爆位图复用池
│   └── model        # 页面几何、阅读状态与配置实体
├── data
│   ├── db           # HistoryDatabase: 原生零依赖 SQLite 历史记录与置顶状态
│   ├── preferences  # ThemePreferences: 主题模式与深色质感持久化
│   └── repository   # DocumentRepository: SAF 文件管道中枢
└── ui
    ├── shelf        # ShelfScreen: 极简响应式书架与文件拾取
    ├── viewer       # ViewerScreen: 连续瀑布流阅读与手势交互
    ├── settings     # ThemeSettingsBottomSheet: 外观与个性化配置面板
    └── theme        # Material 3 调色板映射与沉浸式系统栏自适应
```

---

## 📚 详细设计文档

- 🏛️ **系统架构与技术规范**：[简体中文 (doc/ARCHITECTURE.md)](ARCHITECTURE.md) | [English Edition (doc/ARCHITECTURE_EN.md)](ARCHITECTURE_EN.md)
- 📘 [外观与暗黑主题设计白皮书 (doc/THEME_DESIGN.md)](THEME_DESIGN.md)：深入解析双模深色质感、M3 色彩槽映射与全沉浸适配；
- 🛠️ [开发者指南与贡献规范 (doc/DEVELOPMENT_GUIDE.md)](DEVELOPMENT_GUIDE.md)：开发环境配置、编译命令、编码规范与 PR 准则；
- 📋 [下一代 PDF 重构规划书 (doc/NEXT_GEN_PDF_PLAN.md)](NEXT_GEN_PDF_PLAN.md)：产品演进背景与白边算法逆向解析。

---

## 🚀 快速开始与构建要求

### 构建环境
- **Android Studio**: Ladybug (2024.2+)、Meerkat 或更高版本
- **JDK**: Java 17 或 21
- **Gradle**: 8.7+
- **Min SDK**: 26 (Android 8.0+)
- **Target SDK**: 36 (Android 16 / Baklava)

### 编译 Debug APK
```bash
# 赋予 gradlew 执行权限
chmod +x gradlew

# 编译生成 Debug APK
./gradlew assembleDebug
```
产物位置：`app/build/outputs/apk/debug/app-debug.apk`

---

## 📄 开源许可证

本项目基于 **GNU General Public License v3.0 (GPL-3.0)** 开源，详情请参阅 [LICENSE](../LICENSE) 文件。
