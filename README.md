# Lumina Reader (LuminaPDF)

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_16+_(Baklava)-0284C7?style=for-the-badge&logo=android&logoColor=white" alt="Android 16 Ready" />
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin 2.0" />
  <img src="https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Material 3" />
  <img src="https://img.shields.io/badge/Permissions-Zero_SAF-success?style=for-the-badge" alt="Zero Permissions" />
  <img src="https://img.shields.io/badge/License-GPL_v3.0-blue?style=for-the-badge" alt="License GPLv3" />
</p>

<p align="center">
  <b>Next-Gen Minimalist High-Performance Android PDF Reader · Built for Android 16+</b><br/>
  <i>Lightning-Fast Rendering · Smart Auto-Crop 2.0 · Dual-Style Dark Theme · Zero-Permission SAF · Immersive Edge-to-Edge</i>
</p>

<p align="center">
  <b>English</b> | <a href="doc/README_CN.md">简体中文</a>
</p>

---

## 🌟 Key Highlights

### 🌓 Dual-Style Dark Theme & Customization
- **Multi-Mode Theme Management**: Seamlessly switch between **Follow System**, **Daylight**, and **Dark** modes.
- **Two Distinct Dark Palettes**:
  - **Slate Charcoal** (`#0F172A` / `#1E293B`): Soft, eye-caring contrast with rich Material 3 tonal hierarchy. Ideal for reading in dim environments without harsh contrast.
  - **AMOLED Pure Black** (`#000000` / `#0C0C0E`): True black pixels for maximum battery savings and seamless screen borders on OLED displays.
- **Modern M3 Bottom Sheet**: Tap the palette icon on the bookshelf top bar to open `ThemeSettingsBottomSheet`, featuring fluid animations and modular slots for future preferences.
- **Full Edge-to-Edge Immersion**: Fully transparent status and navigation bars with automatic light/dark icon luminance adjustment. Includes `values-night` resources to eliminate cold-start white flashes.

### ✂️ Smart Auto-Crop 2.0 & Column Focus
- **Proven Algorithm Reborn**: Inherits and enhances EBookDroid's four-direction luminance differential and column boundary detection algorithm.
- **Pure Kotlin Optimization**: Rebuilt with modern Kotlin bitwise operations without native C/C++ dependencies. Detection latency is **< 1.5ms** on modern SoCs.
- **Effective Margin Elimination**: Automatically trims white borders from scanned documents, books, and academic papers, expanding visible text area by **25% ~ 35%**.
- **Double-Column Tap Focus**: Simply tap on either column in academic papers to automatically identify margins and zoom in on that column in a full-width view.

### ⚡ Native 16KB Page Size Alignment (Android 15 & 16 Ready)
- Leverages the official modern `android.graphics.pdf.PdfRenderer` combined with a coroutine scheduling pipeline.
- 100% immune to legacy NDK crashes caused by 16KB memory page alignment (`dlopen failed: 16KB segment alignment`).
- Ultra-compact package size: APK remains under **5MB**, with instant cold starts and restrained memory usage.

### 🛡️ Pure Zero-Permission Architecture (SAF)
- Complies strictly with Android Scoped Storage—**requires zero dangerous storage permissions** (`MANAGE_EXTERNAL_STORAGE` or `READ_EXTERNAL_STORAGE`).
- Integrated with Storage Access Framework (SAF), allowing direct file opening from system pickers, downloads, chat apps (WeChat, Telegram), and email attachments.

### 🚀 Out-of-Memory (OOM) Protection
- Features `BitmapLruCache` to recycle and reuse rendered page bitmaps within a memory cap (25% of available heap), preventing GC stuttering and out-of-memory errors during rapid continuous scrolling.

---

## 🏗️ Architecture Overview

```
org.lumina.reader
├── core
│   ├── crop         # PageCropper2: Smart auto-crop 2.0 & double-column boundary detection
│   ├── engine       # PdfEngine contract & AndroidPdfRendererEngine native implementation
│   ├── cache        # BitmapLruCache: Memory-safe LRU bitmap recycling pool
│   └── model        # Page geometry, reading filters, and configuration entities
├── data
│   ├── db           # HistoryDatabase: Zero-dependency native SQLite history & pinning
│   ├── preferences  # ThemePreferences: SharedPreferences + StateFlow theme persistence
│   └── repository   # DocumentRepository: SAF pipeline & document lifecycle manager
└── ui
    ├── shelf        # ShelfScreen: Minimalist reactive bookshelf & file opener
    ├── viewer       # ViewerScreen: Continuous waterfall viewer & gesture interaction
    ├── settings     # ThemeSettingsBottomSheet: Appearance & customization sheet
    └── theme        # Material 3 tokens, dynamic palettes & immersive system bar controller
```

---

## 📚 Technical Documentation

- 🏛️ **System Architecture & Specifications**: [English (doc/ARCHITECTURE_EN.md)](doc/ARCHITECTURE_EN.md) | [简体中文 (doc/ARCHITECTURE.md)](doc/ARCHITECTURE.md)
- 📘 [Appearance & Dark Theme Design Whitepaper (doc/THEME_DESIGN.md)](doc/THEME_DESIGN.md): Detailed analysis of dual dark styles, M3 color token mapping, and immersive status bar adaptation.
- 🛠️ [Developer Guide & Contribution Norms (doc/DEVELOPMENT_GUIDE.md)](doc/DEVELOPMENT_GUIDE.md): Environment setup, Gradle commands, coding standards, and Conventional Commits guidelines.
- 📋 [Next-Gen PDF Evolution Plan (doc/NEXT_GEN_PDF_PLAN.md)](doc/NEXT_GEN_PDF_PLAN.md): Algorithm reverse-engineering background and feature roadmap.

---

## 🚀 Getting Started & Build Requirements

### Environment Prerequisites
- **Android Studio**: Ladybug (2024.2+), Meerkat, or newer
- **JDK**: Java 17 or 21 (OpenJDK / Temurin recommended)
- **Gradle**: 8.7+
- **Min SDK**: 26 (Android 8.0 Oreo)
- **Target SDK**: 36 (Android 16 / Baklava)

### Building Debug APK
```bash
# Grant execution permissions to gradlew
chmod +x gradlew

# Build Debug APK
./gradlew assembleDebug
```
The output APK will be located at: `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See the [LICENSE](LICENSE) file for details.
