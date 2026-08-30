# Roadmap & UI Specifications

## 1. Implementation Roadmap

### Phase 1: Gradle & Chaquopy Configuration
- [ ] Add Chaquopy plugin in `build.gradle.kts` and `settings.gradle.kts`.
- [ ] Configure `chaquopy { defaultConfig { sourceSets { srcDir("submodules/Droidlate") } } }`.
- [ ] Add dependencies: Jetpack Compose Material 3, Navigation, ViewModel, OkHttp/Retrofit, Gson/Kotlinx Serialization.

### Phase 2: Ingestion & Export Engine (Kotlin)
- [ ] `GitHubDownloader.kt`: Download repository zipball (`GET https://api.github.com/repos/{owner}/{repo}/zipball/{branch}`).
- [ ] `ZipExtractor.kt`: Unpack into `context.cacheDir/projects/{id}/` and locate `res/values/strings.xml`.
- [ ] `ZipExporter.kt`: Compress modified `res/values-*/` directories and `.translation_metadata/` into a single shareable `.zip`.

### Phase 3: Python Engine Bridge (Kotlin)
- [ ] `PythonEngineManager.kt`: Initialize `Python.start(AndroidPlatform(context))` and run Flask server in a daemon thread.
- [ ] `DroidlateApiClient.kt`: Retrofit client for `/api/project`, `/api/strings`, `/api/translate`, `/api/suggest`, `/api/languages`, `/api/prune`.

### Phase 4: Jetpack Compose UI Screens
- [ ] **Home Screen:**
  - GitHub URL input with paste button.
  - "Recent Projects" card list.
  - Download progress indicator dialog.
- [ ] **Language Dashboard:**
  - Locale list with progress bars (% translated, untranslated, outdated, warnings).
  - Floating Action Button (+ Add Language) with ISO locale picker.
  - "Export ZIP" action button on TopAppBar.
- [ ] **Translation Editor:**
  - Filter chips row (`All`, `Untranslated`, `Outdated`, `Warnings`, `Orphaned`).
  - Search bar.
  - Translation card: Key name, type (`<string>`, `<plurals>`, `<string-array>`), source string, comment.
  - Live placeholder & HTML tags validation warnings.
  - Suggestions row (Local TM, Google, MyMemory) with tap-to-apply.
  - Save & Next keyboard action.

### Phase 5: Testing & Release
- [ ] Test with real open-source Android projects (e.g. standard Android apps from GitHub).
- [ ] Edge cases: plurals with quantities (`one`, `other`, `few`, `many`), string arrays, apostrophes, and escaping.
- [ ] Build release APK.

---

## 2. Key Data Models

```kotlin
data class ProjectSummary(
    val mode: String,
    val resDir: String?,
    val sourceFile: String,
    val languages: List<LanguageProgress>
)

data class LanguageProgress(
    val folder: String,       // e.g. "values-es"
    val locale: String,       // e.g. "es"
    val progress: Int,        // 0..100
    val translated: Int,
    val outdated: Int,
    val untranslated: Int,
    val orphaned: Int,
    val total: Int,
    val targetPath: String
)

data class StringResourceEntry(
    val key: String,
    val source: String,
    val sourceHash: String,
    val translation: String,
    val comment: String,
    val status: String,       // "translated", "untranslated", "outdated", "warnings", "orphaned", "readonly"
    val attrib: Map<String, String>
)

data class TranslationSuggestion(
    val provider: String,     // "Local TM", "Google", "MyMemory"
    val text: String
)
```
