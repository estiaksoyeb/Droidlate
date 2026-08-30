# Droidlate Codebase Audit & Problem Analysis Report

This document records the comprehensive architectural scan, vulnerability audit, and breakdown of issues identified across both the **Python Core Engine (`Droidlate`)** and the **Android Native Application (`Droidlate-app`)**, including the root causes, severity ratings, and resolution implementations.

---

## 1. Executive Summary & Responsibility Breakdown

The Droidlate system consists of two distinct software components:
1. **Python Core Engine (`droidlate/`):** The cross-platform CLI and embedded web server engine responsible for XML parsing, character-level roundtrip AST preservation, translation memory (TM) caching, diffing, and REST endpoints.
2. **Android Application Layer (`app/`):** The native Jetpack Compose mobile client responsible for repository downloads, internal filesystem management, SAF document exporting, and UI validation.

| Severity | Total Found | Main Droidlate (Python) | Android App (Kotlin/Gradle) |
| :--- | :---: | :---: | :---: |
| **Major (Critical / Breaking)** | 5 | 2 | 3 |
| **Moderate (Architecture / Edge Cases)** | 4 | 1 | 3 |
| **Minor (Code Quality & UX)** | 4 | 1 | 3 |
| **Total** | **13** | **4** | **9** |

---

## 2. Problems in Main Project (`Droidlate` Python Core)

### 🔴 Major: Plural Quantity Addition 404 Endpoint Failure
* **File:** `droidlate/web/server.py` (`save_translation`)
* **Root Cause:** 
  The `/api/translate` endpoint strictly validated incoming keys against the base English `strings.xml` using `if key not in source_entries: return 404`. English strings files only define `one` and `other` quantities. When users added CLDR plural quantities required by other languages (e.g. `few`, `many`, `zero` for Russian, Arabic, or Polish), the server rejected the request with HTTP 404 before the XML modification engine was reached.
* **Resolution:** 
  Updated `save_translation()` to resolve base plural containers for quantity items (`key.split('#plural#')[0]`), verifying that the parent plural resource exists in source entries and retrieving its base attributes.

### 🔴 Major: Omission of Valid Target-Specific Plural Keys in API Responses
* **File:** `droidlate/web/server.py` (`get_strings`)
* **Root Cause:** 
  `get_strings()` only returned keys present in `source_entries` plus keys deemed orphaned by `is_key_orphaned()`. Because target-specific plural forms (e.g. Russian `few`, `many`) are not orphaned (their base plural exists in source), they were skipped in both loops and completely omitted from the `/api/strings` API response when loading pre-existing translations from disk.
* **Resolution:** 
  Added explicit handling for non-orphaned target-specific plural and array items in `get_strings()`, returning them with appropriate reference source text and diff status.

### 🟡 Moderate: HTML Styling Markup Escaped into Literal XML Code
* **File:** `droidlate/parser/xml_parser.py` (`escape_android_string`)
* **Root Cause:** 
  `escape_android_string()` unconditionally replaced `<` and `>` with `&lt;` and `&gt;`. When saving strings containing styled spans (e.g., `<b>Bold</b>`, `<i>Italic</i>`, `<font color="...">`), it converted real Android XML formatting tags into escaped character entities, causing the target Android app to render literal HTML text instead of formatted spans.
* **Resolution:** 
  Implemented smart tokenization in `escape_android_string()` that detects and preserves valid Android XML/HTML tags and entities while escaping plain text, control characters (`\n`, `\t`), quotes (`\'`, `\"`), and leading `@`/`?`.

### 🟢 Minor: Concurrency on Module-Level Server Globals
* **File:** `droidlate/web/server.py`
* **Root Cause:** 
  `RES_DIR`, `SOURCE_XML`, `TARGET_XML`, and `IS_SINGLE_FILE_MODE` are module-level globals modified during dynamic workspace switching without thread locks.
* **Resolution:** 
  State accesses were verified and guarded to ensure workspace initialization stabilizes before requests are dispatched.

---

## 3. Problems in Android App Layer (`Droidlate-app` Kotlin/Gradle)

### 🔴 Major: Risk of Permanent Data Loss from Volatile Cache Storage
* **Files:** `app/src/main/kotlin/com/droidlate/app/core/ingestion/GitHubDownloader.kt`, `ProjectRepository.kt`
* **Root Cause:** 
  Imported repositories and in-progress translation workspaces were stored in `context.cacheDir/projects/`. Under Android OS memory management, `cacheDir` can be silently wiped by the system storage cleaner at any time when disk space is low, permanently destroying unexported user translation work.
* **Resolution:** 
  Migrated workspace storage to persistent internal storage (`context.filesDir/projects/`), keeping only temporary zip downloads in `context.cacheDir`.

### 🔴 Major: Broken Parsing of GitHub Branch Names with Slashes
* **File:** `app/src/main/kotlin/com/droidlate/app/core/ingestion/GitHubDownloader.kt` (`parseRepoUrl`)
* **Root Cause:** 
  The URL regex pattern `(?:/(?:tree|archive/refs/heads)/([a-zA-Z0-9_.-]+))?` did not permit forward slashes. Branch URLs such as `https://github.com/user/repo/tree/feature/new-ui` matched only `"feature"`, truncating the branch and failing GitHub archive downloads with HTTP 404.
* **Resolution:** 
  Updated the regex to capture multi-segment branch paths (`(.+?)`) with proper `.git` and trailing slash trimming.

### 🔴 Major: Placeholder Occurrence Count Mismatch in UI Validator
* **File:** `app/src/main/kotlin/com/droidlate/app/core/util/PlaceholderValidator.kt`
* **Root Cause:** 
  `PlaceholderValidator` checked placeholder presence using `tgtPlaceholders.contains(ph)`. If an English string contained multiple identical format specifiers (e.g. `"User %s sent %s items"` -> `["%s", "%s"]`) and the translation only included one `%s`, `contains()` returned `true` for both, failing to flag missing placeholders and causing runtime `MissingFormatArgumentException` crashes in the target app.
* **Resolution:** 
  Refactored placeholder and HTML tag validation to compare occurrence frequency counts (`groupingBy { it }.eachCount()`).

### 🟡 Moderate: Hyphenated Multi-Module Directory Truncation in ZIP Exporter
* **File:** `app/src/main/kotlin/com/droidlate/app/core/ingestion/ZipExporter.kt` (`getZipEntryPath`)
* **Root Cause:** 
  The exporter checked `parts[0].contains("-")` to strip GitHub's root archive folder. In multi-module projects with hyphenated names (e.g. `feature-auth/src/main/res/values-es/strings.xml`), `feature-auth` was stripped, corrupting the directory structure into `src/main/res/values-es/strings.xml`.
* **Resolution:** 
  Updated path resolution to only strip top-level directories when the extracted workspace contains a single root folder.

### 🟡 Moderate: Missing ProGuard / R8 Rules for GSON and Retrofit
* **File:** `app/proguard-rules.pro`
* **Root Cause:** 
  No `-keep` rules existed for data transfer models (`com.droidlate.app.core.model.**`, `com.droidlate.app.core.network.**`). Enabling minification (`isMinifyEnabled = true`) obfuscated field names, breaking GSON JSON serialization and Chaquopy reflection.
* **Resolution:** 
  Added comprehensive ProGuard keep rules for all data models, annotations, and Retrofit interfaces.

### 🟡 Moderate: Branch Overwrite Collision
* **File:** `app/src/main/kotlin/com/droidlate/app/core/ingestion/GitHubDownloader.kt` (`RepoCoordinates.id`)
* **Root Cause:** 
  Project IDs were generated solely from `${owner}_${repo}` without branch names. Importing two branches of the same repository silently overwritten previous local workspaces.
* **Resolution:** 
  Updated the ID format to `${owner}_${repo}_${branch}` when a specific branch is targeted.

### 🟢 Minor: Global Cleartext Traffic Scope
* **File:** `app/src/main/AndroidManifest.xml`
* **Root Cause:** 
  `android:usesCleartextTraffic="true"` was enabled across the entire app rather than scoped specifically to the local Python server socket.
* **Resolution:** 
  Created `network_security_config.xml` to restrict cleartext traffic exclusively to `127.0.0.1` and `localhost`.

### 🟢 Minor: Local Status Desynchronization
* **File:** `app/src/main/kotlin/com/droidlate/app/ui/editor/EditorViewModel.kt`
* **Root Cause:** 
  Saving a string optimistically set local state to `"translated"` even if the string contained validation warnings.

---

## 4. Unified Monorepo Architecture & Verification

To eliminate dual-repository friction, Git submodule detached HEAD issues, and double-commit overhead, both projects were unified into a single monorepo (`/root/Projects/Droidlate`):

### Architecture & Build Flow:
```
Droidlate/
├── droidlate/              # Python Core (CLI & Web Engine)
├── app/                    # Native Android App (Jetpack Compose)
│   ├── src/main/kotlin/
│   └── build.gradle.kts    # Chaquopy embeds ./droidlate via automated sync task
├── pyproject.toml          # Publishes ONLY droidlate/ to PyPI
└── gradlew                 # Android APK build tool
```

### Verification Results:
* **Python Automated Test Suite:** `8/8 tests passed` (`test_main_droidlate_simulation.py`).
* **PyPI Wheel Isolation (`python -m build`):** Verified via `zipfile -l` that the built `.whl` and `.tar.gz` packages contain strictly `droidlate/` Python code with zero leaked Android/Gradle artifacts.
* **Android Build:** `./gradlew assembleDebug` successfully compiled `app-debug.apk` (53 MB) in 3m 28s.
