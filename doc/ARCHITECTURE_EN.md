# Lumina Reader - System Architecture & Technical Specifications

> **Target Platform**: Next-Generation Minimalist High-Performance PDF Reader for Android 16+  
> **Core Philosophy**: Absolute Purity · Rapid Rendering · Smart Auto-Crop 2.0 · Zero-Permission SAF · 16KB Page Size Alignment · Edge-to-Edge Immersion  
> **Language Editions**: [简体中文](file:///home/eddy/myplace/project/lumina-pdf/doc/ARCHITECTURE.md) | **English**

---

## 1. Overall System Architecture

Lumina strictly adheres to Layered Architecture and Unidirectional Data Flow (UDF), guaranteeing high cohesion and minimal coupling:

```mermaid
graph TD
    subgraph Presentation Layer (Jetpack Compose + Material 3)
        MainActivity[MainActivity: Edge-to-Edge Host / Intent Dispatch / Predictive Back]
        ShelfScreen[ShelfScreen: Minimalist Reactive Shelf / Independent Action Menus]
        ViewerScreen[ViewerScreen: Waterfall & Horizontal Pager / Gestures / Fullscreen]
        ThemeSheet[ThemeSettingsBottomSheet: Appearance & Personalization Sheet]
    end

    subgraph ViewModel State Hub (MVI / StateFlow)
        VM[ViewerViewModel: UI State Flow / Cross-Screen Lifecycle Coordination]
        UIState[ViewerUiState: Page Index/Scale/Crop/Filters/Drawer/Fullscreen/Landscape/Layout]
        ThemeState[ThemeSettings: Theme Mode & Dark Palette Style]
    end

    subgraph Domain Core Layer (Core)
        Cropper[PageCropper2: Smart Auto-Crop 2.0 & Column Focusing]
        Engine[PdfEngine Abstract Interface]
        PdfRendererImpl[AndroidPdfRendererEngine Native Implementation]
        Cache[BitmapLruCache: Memory-Safe LRU Bitmap Recycling Pool]
    end

    subgraph Data & Storage Layer (Data)
        Repo[DocumentRepository: Document Lifecycle Hub]
        SAF[Storage Access Framework: ParcelFileDescriptor Streaming Pipeline]
        HistoryDB[HistoryDatabase: Zero-Dependency Native SQLite History]
        ThemePref[ThemePreferences: Lightweight Theme Persistence]
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

## 2. Core Subsystems

### 2.1 Smart Auto-Crop 2.0 (`core/crop/PageCropper2.kt`)
- **Algorithm Heritage**: Re-engineered and enhanced from the renowned EBookDroid C engine `PageCropper.c`;
- **Pure Kotlin Advantage**:
  - Eliminates native NDK ELF segment alignment crashes (`dlopen failed: 16KB segment alignment`) under Android 15 and 16's 16KB page size architectures;
  - Re-implemented with modern Kotlin bitwise operations and JIT optimization. Detection latency on modern SoCs is **under 1.5ms** on 400px downsampled page thumbnails;
- **Robust Boundary Normalization**:
  - Scanning stops cleanly at boundary origin (`0f`) or terminus (`1f`) when all pixels within the scan window are blank, preventing blank pages from collapsing;
- **Double-Column Academic Paper Tap-to-Focus (`calculateColumnBounds`)**:
  - Uses the global page background luminosity baseline (`max(200, calculateAvgLum)`), avoiding contrast miscalculations when the user taps directly on dark text blocks;
  - Scans laterally to detect gutter margins and locks the viewport onto the active column, eliminating horizontal panning fatigue during paper reading.

### 2.2 Modern Rendering Engine & Memory Protection (`core/engine` & `core/cache`)
- **Zero-Dependency Native Core**: Powered directly by `android.graphics.pdf.PdfRenderer`, avoiding massive 15MB~30MB third-party C++ binaries and keeping the APK size **under 5MB**;
- **Concurrency Locking & Thread Isolation**: Protects `PdfRenderer`'s single-thread requirement via a lightweight coroutine `Mutex` bound to `Dispatchers.IO`, ensuring thread safety during fast paging;
- **OOM Prevention (`BitmapLruCache`)**:
  - Dynamically calculates 25% of available heap memory as its cache ceiling;
  - Automatically recycles off-screen page bitmaps to prevent GC stuttering and out-of-memory errors during continuous rapid scrolling.

### 2.3 Gesture Hierarchy & Conflict Resolution (`ui/viewer/ViewerScreen.kt`)
- **Multi-Tier Pointer Processing Pipeline**:
  - **Unzoomed Single-Touch (`scale <= 1.05f`)**: Leaves touch events unconsumed, passing drag gestures directly to parent scroll containers (`LazyColumn` or `HorizontalPager`) for 120Hz scrolling;
  - **Multi-Touch Pinch-to-Zoom**: Captures two-finger centroid and scale deltas via `calculateZoom()`, enabling continuous 1.0x ~ 4.0x zoom while locking parent scrolling;
  - **Zoomed Single-Finger Pan**: Takes over single-finger drags when `scale > 1.05f`, translating the enlarged page within clamped boundaries to prevent drifting off-screen;
  - **Single Tap**: Sensitively toggles floating overlay controls and menus;
  - **Double Tap**: Instantly resets scale to 1.0x when enlarged; triggers two-column academic paper focus when at 1.0x scale.

### 2.4 Immersive Fullscreen Architecture (`ui/viewer` & `MainActivity`)
- **System Inset Management**:
  - Integrated with `WindowCompat.getInsetsController` to toggle immersive reading;
  - Uses `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, completely hiding the system status bar and gesture navigation bar until temporarily summoned by an inward edge swipe;
- **Multi-Level Predictive Back Hierarchy**:
  - Priority 1: If the outline drawer is open, back press dismisses the drawer;
  - Priority 2: If fullscreen mode is active, back press exits fullscreen and restores system bars;
  - Priority 3: Exits document viewer to the bookshelf, resetting screen orientation and system bars to default.

### 2.5 Landscape Orientation & Dual Reading Layout Modes
- **Adaptive Screen Orientation**:
  - One-tap orientation toggle between portrait and `ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE` (dual-direction gravity sensing landscape);
- **Dual Reading Layout Engines**:
  - `ReadingLayoutMode.CONTINUOUS_VERTICAL`: Continuous waterfall scrolling via `LazyColumn`;
  - `ReadingLayoutMode.SINGLE_PAGE_HORIZONTAL`: Simulated eBook-style single-page horizontal flipping via Compose `HorizontalPager`;
  - Seamless state synchronization ensures current page index is preserved precisely across layout switches;
- **Dynamic Resolution Re-rendering**:
  - Observes orientation and layout changes to automatically re-render bitmaps tailored to the active screen dimensions and aspect ratio.

### 2.6 Zero-Permission Storage & History Pipeline (`data`)
- **Modern SAF Architecture**: No dangerous permissions required (`READ_EXTERNAL_STORAGE` or `MANAGE_EXTERNAL_STORAGE`);
- Accesses PDFs via `ActivityResultContracts.OpenDocument` or system `ACTION_VIEW` Intents, streaming file descriptors via `ParcelFileDescriptor`.
- **KSP-Free Native SQLite Persistence (`HistoryDatabase`)**:
  - Built with native `SQLiteOpenHelper` and Kotlin `StateFlow`, eliminating Room/KSP compile toolchain discrepancies and accelerating build times.

### 2.7 Appearance & Theming Subsystem (`ui/theme` & `ui/settings`)
- Reference: [THEME_DESIGN.md](file:///home/eddy/myplace/project/lumina-pdf/doc/THEME_DESIGN.md).
- Provides System-following, Daylight, Slate Charcoal, and AMOLED Pure Black themes with translucent system insets and cold-start anti-flash resources.

---

## 3. Directory & Module Structure

```
app/src/main/
├── AndroidManifest.xml              # Manifest (Edge-to-Edge, uiMode, PDF Intent-Filters)
├── java/org/lumina/reader/
│   ├── MainActivity.kt              # Single-Activity host, predictive back & fullscreen controller
│   ├── core/
│   │   ├── crop/
│   │   │   └── PageCropper2.kt      # Smart auto-crop 2.0 & two-column boundary detection
│   │   ├── engine/
│   │   │   ├── PdfEngine.kt         # PDF rendering engine contract
│   │   │   └── AndroidPdfRendererEngine.kt # Native PdfRenderer implementation
│   │   ├── cache/
│   │   │   └── BitmapLruCache.kt    # Memory-safe LRU bitmap recycling pool
│   │   └── model/
│   │       └── PdfModels.kt         # Geometry, filters, and layout mode models
│   ├── data/
│   │   ├── db/
│   │   │   ├── HistoryDatabase.kt   # Reading history & pinning SQLite database
│   │   │   └── RecentDocument.kt    # Bookshelf document entity
│   │   ├── preferences/
│   │   │   └── ThemePreferences.kt  # Theme and dark mode persistence
│   │   └── repository/
│   │       └── DocumentRepository.kt# SAF pipeline & document repository hub
│   └── ui/
│       ├── settings/
│       │   └── ThemeSettingsBottomSheet.kt # Material 3 appearance settings panel
│       ├── shelf/
│       │   └── ShelfScreen.kt       # Minimalist reactive bookshelf with conflict-free menu
│       ├── viewer/
│       │   ├── ViewerScreen.kt      # Waterfall & horizontal reader with full controls & gestures
│       │   └── ViewerViewModel.kt   # Unified MVI ViewModel
│       └── theme/
│           ├── Color.kt             # Lumina color palette & reading filter matrix
│           ├── Theme.kt             # Material 3 theme dynamic assembly
│           └── Type.kt              # Modern typography tokens
└── res/
    ├── values/themes.xml            # Daylight launch theme
    └── values-night/themes.xml      # Night launch theme (cold-start flicker prevention)
```
