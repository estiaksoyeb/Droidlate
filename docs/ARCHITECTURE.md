# Droidlate Mobile App - Architecture

## 1. High-Level Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      Jetpack Compose UI (Material 3)                     │
│  ┌───────────────────┐  ┌───────────────────────┐  ┌──────────────────┐ │
│  │   HomeScreen      │  │    DashboardScreen    │  │   EditorScreen   │ │
│  │(GitHub Repo Input)│  │ (Locale List/Progress)│  │(String Translator│ │
│  └───────────────────┘  └───────────────────────┘  └──────────────────┘ │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ StateFlow / ViewModels
┌────────────────────────────────────▼────────────────────────────────────┐
│                             Domain & Managers                            │
│  ┌──────────────────────┐  ┌───────────────────┐  ┌───────────────────┐ │
│  │  GitHubDownloader    │  │   ZipExporter     │  │   ProjectManager  │ │
│  │ (Downloads Zipball)  │  │(Packages Changes) │  │  (Tracks Recents) │ │
│  └──────────────────────┘  └───────────────────┘  └───────────────────┘ │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ OkHttp / Retrofit (or PyObject)
┌────────────────────────────────────▼────────────────────────────────────┐
│                    Python Engine Bridge (Chaquopy)                      │
│                Localhost `http://127.0.0.1:5000/api/...`                │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ In-Process Loopback
┌────────────────────────────────────▼────────────────────────────────────┐
│            Embedded Droidlate Python Engine (Git Submodule)             │
│  ┌──────────────────────┐  ┌───────────────────┐  ┌───────────────────┐ │
│  │   xml_parser.py      │  │   diff_engine.py  │  │     apis.py       │ │
│  │ (Round-trip XML &    │  │ (Hashing, Outdated│  │ (Google/MyMemory  │ │
│  │  Comment Retention)  │  │  Placeholder QA)  │  │   Suggestions)    │ │
│  └──────────────────────┘  └───────────────────┘  └───────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Layer Responsibilities

### A. UI Layer (`com.droidlate.app.ui`)
* **HomeScreen:** Accepts a GitHub repository URL or selects a local ZIP archive. Displays download progress bar and recent projects.
* **DashboardScreen:** Lists all detected locale folders (`values-es`, `values-fr`, etc.) with translation completion percentages, outdated counts, and "+ Add New Language" dialog.
* **EditorScreen:** Detailed translation workspace. Contains:
  * Filter chips: `All`, `Untranslated`, `Outdated`, `Warnings`, `Orphaned`, `Read-only`.
  * Search bar for filtering by key name or source text.
  * Source card with copy action and formatted placeholder highlights.
  * Translation text area with auto-suggestions row (Local TM, Google, MyMemory).
  * Real-time placeholder/HTML mismatch warning chips.

### B. Ingestion & Storage Layer (`com.droidlate.app.core.ingestion`)
* **GitHubDownloader:** Fetches archive zipball from `https://api.github.com/repos/{owner}/{repo}/zipball/{branch}`. Extracts the archive into `context.cacheDir/projects/{owner}_{repo}/` and discovers `res/` directories.
* **ZipExporter:** Gathers modified target XML files (`res/values-*/strings.xml`) and `.translation_metadata/` and streams them into a single `.zip` file in cache, invoking Android's `Intent.ACTION_SEND` (ShareSheet).

### C. Python Engine Bridge (`com.droidlate.app.core.python`)
* **PythonEngineManager:**
  * Initializes the Chaquopy Python runtime.
  * Starts the embedded Flask server on `127.0.0.1:5000` in a background daemon thread.
  * Supports dynamic workspace switching (`/api/workspace` or `set_res_dir`).

---

## 3. Communication Contract (API Endpoints)

| Endpoint | Method | Payload / Params | Description |
| :--- | :--- | :--- | :--- |
| `/api/project` | `GET` | — | Returns project summary, list of locales, and progress % |
| `/api/strings` | `GET` | `?lang=values-es` | Returns all string entries, status, comments, and hashes |
| `/api/translate`| `POST`| `{ lang, key, value, source_hash }` | Saves translation to XML and updates metadata sidecar |
| `/api/suggest`  | `GET` | `?text=...&src=values&tgt=values-es` | Returns translation suggestions from TM and Web APIs |
| `/api/languages`| `POST`| `{ locale: "es" }` | Creates new locale directory and empty `strings.xml` |
| `/api/prune`    | `POST`| `{ lang, key }` | Removes orphaned string from target XML and metadata |
