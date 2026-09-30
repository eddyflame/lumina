# Lumina Reader (LuminaPDF)

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_16+_(Baklava)-0284C7?style=for-the-badge&logo=android&logoColor=white" alt="Android 16 Ready" />
  <img src="https://img.shields.io/badge/Kotlin-2.2.20-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin 2.2" />
  <img src="https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Material 3" />
  <img src="https://img.shields.io/badge/16KB_Page_Size-Immune-success?style=for-the-badge" alt="16KB Page Size Immune" />
  <img src="https://img.shields.io/badge/Permissions-Zero_SAF-emerald?style=for-the-badge" alt="Zero Permissions" />
  <img src="https://img.shields.io/badge/License-GPL_v3.0-blue?style=for-the-badge" alt="License GPLv3" />
</p>

<p align="center">
  <b>Next-Gen Minimalist High-Performance Android PDF Reader · Built for Android 16+</b><br/>
  <i>Lightning-Fast Rendering · Smart Auto-Crop 2.0 · Dual-Style Dark Theme · Interactive Annotations with Standard Export · Physical Page Reorganization · Zero-Permission SAF · 16KB Page Size Native Immunity</i>
</p>

<p align="center">
  <b>English</b> | <a href="doc/README_CN.md">简体中文</a>
</p>

---

## 🌟 Key Highlights

### ⚡ Native 16KB Page Size Immunity & Pure JVM Architecture
- **Framework-Native Rendering**: Combines Android's built-in hardware-accelerated `android.graphics.pdf.PdfRenderer` with pure JVM Apache PDFBox Android (2.0.27.0). Contains **zero third-party C/C++ `.so` native libraries**;
- **100% 16KB Page Alignment Immune**: Completely exempt from segment alignment crashes (`dlopen failed: 16KB segment alignment`) required on Android 15 and 16;
- **Instant Cold Starts**: Sub-**5MB** APK footprint with low memory footprint, featuring `BitmapLruCache` (25% max heap ceiling) for rapid continuous scrolling OOM protection.

### ✂️ Smart Auto-Crop 2.0 & Column Focus
- **Microsecond Algorithm**: Ported and enhanced from EBookDroid's classic four-direction luminance scanning. Optimized in pure Kotlin bitwise operations with execution latency **< 1.5ms**;
- **Eliminates Margin Waste**: Automatically trims scanned borders and paper margins, expanding readable text area by **25% ~ 35%**;
- **Double-Column Single-Tap Focus**: Tap either column in academic papers (IEEE/ACM/arXiv) to detect the gutter and zoom in on that column in a full-width viewport.

### 🌓 Dual-Style Dark Theme & Eye-Care Color Matrix
- **Two Distinct Dark Palettes**:
  - **Slate Charcoal** (`#0F172A` / `#1E293B`): Soft, eye-caring contrast for dim environments;
  - **AMOLED Pure Black** (`#000000` / `#0C0C0E`): True black pixels for maximum battery savings on OLED screens;
- **WCAG AAA 7.8:1 Eye-Care Mapping**: Replaces blurry sepia filters with a calibrated linear `ColorMatrix` that maps white backgrounds and black text into optimal contrast;
- **Edge-to-Edge Immersion**: Fully transparent system bars with automatic light/dark icon adaptation and cold-start `values-night` resources.

### ✏️ Interactive Annotations & Undo/Redo Stacks
- **Full Tooling Suite**: Ink pen, semi-transparent multiply-blended highlighter, and point-to-stroke physical eraser;
- **Command Pattern Architecture**: Comprehensive multi-step Undo, Redo, and page-clearing history stacks;
- **Bidirectional Projection**: `PageCoordinateTransformer` bridges screen viewport pixels ($px$) and standard PDF 72 DPI point coordinates without scaling drift.

### 📑 ISO 32000-1 Compliant Serialization & Page Reorganization
- **Standard Compliant Export**: Serializes strokes and highlights into ISO 32000-1 `/Annots` dictionaries (`/Subtype /Ink`) with dedicated `/AP` (`PDAppearanceStream`) streams—rendering flawlessly in Adobe Acrobat, Chrome, Safari, and Edge;
- **Physical Page Management**: Supports 90° clockwise/counter-clockwise physical page rotation (`/Rotate`), multi-selection deletion, and page reordering;
- **Atomic File Overwrites**: Staged sandbox temporary file writes and safe streaming ensure original documents never corrupt on unexpected termination.

### 🛡️ Pure Zero-Permission Scoped Storage (SAF)
- **Zero Dangerous Permissions**: Operates strictly within Android Scoped Storage without requesting `MANAGE_EXTERNAL_STORAGE` or external read/write permissions;
- **System Integration**: Seamlessly integrates with system file pickers, download managers, chat apps (WeChat, Telegram), and email attachments.

---

## 🏗️ Architecture Overview

```mermaid
graph TD
    subgraph Presentation Layer (Jetpack Compose + Material 3)
        MainActivity[MainActivity: Edge-to-Edge / Predictive Back]
        ShelfScreen[ShelfScreen: Reactive Bookshelf & History]
        ViewerScreen[ViewerScreen: Continuous Waterfall / Pager & Gesture Arbiter]
        AnnotToolbar[AnnotationToolbar: Floating Annotation Control Bar]
        Organizer[PageOrganizerScreen: Page Reorder & Rotation Grid]
    end

    subgraph State Management (ViewModel + StateFlow)
        VM[ViewerViewModel: UDF Central Controller]
        UIState[ViewerUiState: Unified Viewer State]
        AnnotMgr[AnnotationCommandManager: Command History Stack]
    end

    subgraph Domain Core Layer (Domain Core)
        Engine[PdfEngine: Rendering Engine Contract]
        NativeEngine[AndroidPdfRendererEngine: Thread-Safe Native Renderer]
        Cropper[PageCropper2: Smart Auto-Crop 2.0 & Column Focus]
        Exporter[PdfDocumentExporter: Pure JVM PDFBox Serializer]
        Cache[BitmapLruCache: 25% Heap Memory OOM Protection]
    end

    subgraph Data & System Layer (Data)
        Repo[DocumentRepository: File Pipeline & Atomic Transaction Manager]
        SAF[Storage Access Framework: ParcelFileDescriptor Stream]
        HistoryDB[HistoryDatabase: Zero-KSP Native SQLite Store]
        ThemePref[ThemePreferences: Preferences Store]
    end

    MainActivity --> ShelfScreen
    MainActivity --> ViewerScreen
    ViewerScreen --> AnnotToolbar
    ViewerScreen --> Organizer
    ShelfScreen --> VM
    ViewerScreen --> VM
    VM --> UIState
    VM --> AnnotMgr
    VM --> Repo
    Repo --> Engine
    Engine --> NativeEngine
    NativeEngine --> Cropper
    NativeEngine --> Cache
    Repo --> Exporter
    Repo --> SAF
    Repo --> HistoryDB
    VM --> ThemePref
```

---

## 📚 Technical Documentation

- 🏛️ **System Design & Architecture Specification**: [English (doc/DESIGN_EN.md)](doc/DESIGN_EN.md) | [简体中文 (doc/DESIGN.md)](doc/DESIGN.md)  
  *Comprehensive coverage of design philosophy, functional architecture, lifecycle sequence, sub-systems deep-dive (rendering, auto-crop 2.0, annotations, ISO 32000-1 export, dual dark themes, gestures, and SAF) and complete directory responsibilities.*
- 🛠️ **Developer Guide & Contribution Norms**: [English (doc/DEVELOPMENT_GUIDE_EN.md)](doc/DEVELOPMENT_GUIDE_EN.md) | [简体中文 (doc/DEVELOPMENT_GUIDE.md)](doc/DEVELOPMENT_GUIDE.md)  
  *Environment setup, Gradle commands, unit testing suites, Material 3 coding standards, concurrency & memory rules, and Conventional Commits guidelines.*

---

## 🚀 Getting Started & Build Instructions

### Prerequisites
- **Android Studio**: Meerkat (2024.3+), Ladybug (2024.2+), or newer
- **JDK**: Java 17 or 21 (Eclipse Temurin / OpenJDK 21 recommended)
- **Compile / Target SDK**: `36` (Android 16 / Baklava)
- **Min SDK**: `26` (Android 8.0 Oreo)
- **NDK**: Not required (pure JVM + Android Framework native architecture)

### Build Commands

```bash
# 1. Clone repository
git clone https://github.com/your-username/lumina-pdf.git
cd lumina-pdf

# 2. Grant execution permissions
chmod +x gradlew

# 3. Run all unit tests
./gradlew test

# 4. Assemble Debug APK
./gradlew assembleDebug
```

> Output APK is located at: `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See the [LICENSE](LICENSE) file for details.
