# Lumina Reader - Developer Guide & Contribution Norms

> This guide helps developers set up their local development environment, understand Lumina Reader's modern architecture, master build and test commands, and contribute effectively to the project.  
> **Language**: [简体中文](DEVELOPMENT_GUIDE.md) | **English Edition**

---

## 1. Project Overview & Technical Specifications

Lumina Reader is engineered specifically for modern Android 16+ experiences with top-tier tooling:

- **Target Platform**: Android 16+ (Baklava / API 36 Ready), backward compatible down to Android 8.0 (API 26)
- **Language**: Kotlin `2.2.20` + Compose Compiler plugin
- **Build Toolchain**: Gradle `9.6+` + Android Gradle Plugin (AGP) `9.4.1`
- **UI Framework**: Jetpack Compose + Material 3 (1.3.1)
- **Concurrency**: Kotlin Coroutines `1.9.0` + StateFlow reactive streams
- **PDF Engine System**:
  - **Fast Rendering Engine**: Android framework-native `android.graphics.pdf.PdfRenderer` with hardware acceleration;
  - **Standard Serialization Engine**: Pure JVM Apache PDFBox Android (`com.tom-roush:pdfbox-android:2.0.27.0`) for compliant annotation export, `/AP` appearance stream generation, and physical page reorganization;
- **16KB Page Size Alignment**: Fully **pure JVM + Android Framework native architecture** with **zero third-party C/C++ `.so` libraries**, naturally immune to 16KB memory page alignment crashes on Android 15/16;
- **Storage Model**: 100% compliant with Scoped Storage, requiring **zero dangerous storage permissions** and powered entirely by Storage Access Framework (SAF).

---

## 2. Development Environment Setup

### 2.1 Prerequisites
- **Operating System**: Linux (Ubuntu 22.04+/24.04+), macOS (Apple Silicon / Intel), or Windows (native or WSL2);
- **JDK**: Java 17 or Java 21 (Eclipse Temurin 21 or Android Studio bundled JBR recommended);
- **IDE**: Android Studio Meerkat (2024.3+), Ladybug (2024.2+), or newer;
- **Android SDK**:
  - Compile SDK: `36` (Android 16)
  - Target SDK: `36`
  - Min SDK: `26`
  - Install `Android SDK Platform 36` and `Android SDK Build-Tools 36.x` via Android Studio SDK Manager.

### 2.2 Environment Variables
Ensure `JAVA_HOME` and `ANDROID_HOME` are correctly configured in `~/.bashrc` or `~/.zshrc`:

```bash
# Example configuration
export JAVA_HOME="/path/to/jdk-21"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

---

## 3. Local Build, Test & Run

### 3.1 Clone the Repository
```bash
git clone https://github.com/your-username/lumina-pdf.git
cd lumina-pdf
```

### 3.2 Common Gradle Commands

```bash
# Grant execution permissions (Unix / macOS)
chmod +x gradlew

# Build Debug APK
./gradlew assembleDebug

# Run all unit tests
./gradlew test

# Run a specific unit test class
./gradlew testDebugUnitTest --tests "org.lumina.reader.core.export.PdfDocumentExporterTest"

# Run Android Lint static code analysis
./gradlew lintDebug

# Clean build outputs and caches
./gradlew clean
```

> **Artifact Location**: The built Debug APK is output to `app/build/outputs/apk/debug/app-debug.apk`.

### 3.3 Unit Test Coverage
The project includes automated unit test suites for critical core algorithms and serialization:
- **`PageCropper2Test`**: Validates smart margin auto-crop, four-direction luminance scanning, white-space tolerance, and double-column gutter detection on both light and dark backgrounds;
- **`PdfDocumentExporterTest`**: Validates standard ISO 32000-1 annotation export, highlighter constant opacity (`/CA`), stroke colors, line widths, and physical page reorganization;
- **`AnnotationTest`**: Validates stroke addition, removal, clearing, and the integrity of the Undo/Redo command history stack;
- **`PageEditSpecTest`**: Validates physical page rotation calculations, page index remapping, and page deletion logic.

---

## 4. Architecture Standards & Coding Guidelines

To preserve Lumina's minimalism, performance, and UI fluidness, follow these core principles:

### 4.1 Strict Material 3 Color System Compliance
- **No Hardcoded Colors**: Never write hardcoded `Color(0xFF...)` or invoke `Color.Black` / `Color.White` directly in Composable functions;
- **Always Use Semantic Color Slots**:
  - Screen Background: `MaterialTheme.colorScheme.background`
  - Card/Sheet Surface: `MaterialTheme.colorScheme.surface`
  - Secondary Container: `MaterialTheme.colorScheme.surfaceVariant`
  - Primary Brand Accent: `MaterialTheme.colorScheme.primary`
  - Outline/Border: `MaterialTheme.colorScheme.outlineVariant`
- Refer to [DESIGN_EN.md](DESIGN_EN.md) for full palette mapping specifications.

### 4.2 Unidirectional Data Flow (UDF)
- **Dumb UI Components**: Composables should only render state and trigger events. They must never directly invoke database queries or trigger disk I/O;
- **Centralized ViewModel**: `ViewerViewModel` emits state through `StateFlow<ViewerUiState>`;
- **Command Pattern for Annotations**: Any new annotation manipulation must implement `AnnotationCommand` to integrate seamlessly into `AnnotationCommandManager`.

### 4.3 Concurrency & Memory Safety
- **IO Thread Isolation**: All rendering, bitmap scaling, outline parsing, and file writes must run inside `withContext(Dispatchers.IO)`;
- **Concurrency Mutex**: `PdfRenderer` does not support concurrent multi-page calls. Access must be serialized via `AndroidPdfRendererEngine`'s internal `Mutex`;
- **Bitmap Recycling**: All decoded bitmaps must be routed through `BitmapLruCache` (25% max heap ceiling) to prevent memory fragmentation and OOM during fast scrolling.

### 4.4 Coordinate System Projection
- Canvas strokes are drawn in screen pixels ($px$). When storing and exporting, convert them to standard PDF 72 DPI points via `PageCoordinateTransformer`;
- Note the origin difference: Screen viewport has its origin at the top-left ($Y$ downwards), whereas PDF coordinate space has its origin at the bottom-left ($Y$ upwards).

### 4.5 Atomic File Transactions
- Never overwrite original SAF file descriptors directly without transactional staging;
- Always export to a temporary file in the sandbox cache, verify its integrity, and perform an atomic stream transfer into the target destination.

---

## 5. Git Commit & Contribution Guidelines

### 5.1 Conventional Commits
Commit messages must follow the [Conventional Commits](https://www.conventionalcommits.org/) convention:  
`<type>(<scope>): <subject>`

- `feat`: New user-facing features (e.g., `feat(export): add ISO 32000-1 annotation export`)
- `fix`: Bug fixes (e.g., `fix(crop): resolve column detection offset on dark papers`)
- `docs`: Documentation updates (e.g., `docs: update system design spec and developer guide`)
- `refactor`: Internal code reorganization without behavior change
- `perf`: Performance optimizations (e.g., `perf(cache): optimize bitmap pool eviction`)
- `test`: Unit tests or instrumentation test additions
- `chore`: Gradle configuration, dependency version updates, or environment maintenance

### 5.2 Pull Request (PR) Workflow
1. Fork the repository and create your feature branch based on `develop` (e.g., `feature/my-feature`);
2. Implement your code and write corresponding unit tests;
3. Run `./gradlew test` locally to ensure all tests pass cleanly;
4. Commit using Conventional Commits format and push your branch;
5. Open a Pull Request targeting `develop`, detailing changes and testing verification results.
