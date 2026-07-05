# Droidlate

**Droidlate** is a lightweight, local, web-based translation workspace for Android `strings.xml` resource files. It features a clean, responsive, Weblate-inspired Single Page Application (SPA) designed to make translating Android applications fast, accurate, and resilient.

---

## Core Usecases

* **Local Offline Workspace:** Translate and edit local localization files on your machine. Avoid uploading sensitive resource files to third-party servers or setting up heavy server-side infrastructure.
* **Preserving XML Formatting & Comments:** Standard XML parsers rewrite files, wiping out comments, custom spacing, ordering, and attributes. Droidlate's custom parsing engine performs in-place character index replacements to maintain exact XML styling, comments, structure, and formatting.
* **Plurals & String-Arrays Support:** Seamlessly edit standard `<string>` elements, `<plurals>` (supporting quantity qualifiers like `one`, `other`, etc.), and ordered `<string-array>` lists within the same unified workspace.
* **Tracking Outdated Translations:** When developers update a base string in `values/strings.xml`, translations can become outdated. Droidlate normalizes and hashes base strings into a lightweight local sidecar metadata file, highlighting which target translations need updates.
* **Placeholder Verification & QA:** Mismatched Java-style placeholders (e.g., `%s`, `%1$d` in the source but `% d` or missing in the target) cause runtime crashes on Android. Droidlate validates alignment on every keystroke and flags warnings.
* **Orphaned String Pruning:** Over time, strings are deleted from the main codebase but linger in translation files. Droidlate lists these "orphaned" strings and lets you prune them with a single click.

---

## Key Features

* **Interactive Language Creation:** Add and initialize new target locales (e.g., `es`, `fr`, `zh-rCN`) directly from the dashboard UI without manual directory setup.
* **Local Translation Memory (TM):** Indexes your existing translations locally to suggest exact and partial completions offline, facilitating consistent phrasing across your codebase.
* **Translatable Flags & Read-Only View:** Respects `translatable="false"` base flags. Non-translatable strings are isolated under a read-only filter tab and auto-pruned from target XML files.
* **Automatic Update Checker:** Periodically checks PyPI in the background (cached for 24 hours) and alerts you in the web UI and CLI if a new release is available.
* **Sleek UI with Dark Mode:** A modern HSL-tailored interface featuring layout toggles (especially optimized for mobile web layouts), status indicators, and translation statistics.
* **Keyboard Shortcuts:** Built for productivity. Use `Ctrl+S` to instantly Save & Next, and `Alt+1`/`Alt+2` to paste dynamic suggestions.
* **Auto-Translation Suggestions:** Integrates with translation suggestion services (like Google Translate and MyMemory) to suggest translations on the fly.
* **Atomicity & Crash Resilience:** Local updates are written to the target XML file first, then metadata sidecars second. If interrupted, the app automatically flags the key as `Outdated` on the next run, ensuring no state corruption.
* **Secure Server Binding:** The local Flask server strictly binds to the loopback interface (`127.0.0.1`), ensuring your filesystem is safe from external network access.

---

## Installation

The recommended way to install Droidlate is globally using [pipx](https://github.com/pypa/pipx):

```bash
pipx install droidlate
```

Alternatively, you can install it using standard `pip`:

```bash
pip install droidlate
```

### Local Development

If you want to run it from source or install in editable mode:

```bash
# Clone the repository and install dependencies
git clone https://github.com/estiaksoyeb/Droidlate.git
cd Droidlate
pip install -e .
```

---

## Usage & CLI Commands

Once installed, you can launch the Droidlate workspace using the `droidlate` CLI command:

### 1. Auto-Scan Mode (Default)
Run the command in your Android project root (or near your resources). Droidlate will auto-detect typical folders like `app/src/main/res/`, `src/main/res/`, or `res/`:
```bash
droidlate
```

### 2. Specify Resource Directory
You can explicitly define where the Android resource directory is located:
```bash
droidlate --res-dir /path/to/android/app/src/main/res
```

### 3. Single-File Mode
To translate or edit a single pair of strings files directly:
```bash
droidlate --source app/src/main/res/values/strings.xml --target app/src/main/res/values-es/strings.xml
```

### 4. Custom Port
Run the local web server on a different port:
```bash
droidlate --port 8080
```

---

## Repository Structure

```text
├── droidlate/                     # Package root
│   ├── parser/
│   │   ├── xml_parser.py          # Expat character-level round-trip parser
│   │   └── diff_engine.py         # Hashing, validation, and status engines
│   ├── translator/
│   │   └── engine.py              # Translation Orchestrator
│   ├── web/
│   │   ├── server.py              # Flask server and local REST APIs
│   │   └── static/                # Single Page Web App (HTML, CSS, JS)
│   ├── cli_wizard.py              # CLI console loop wizard
│   └── main.py                    # Command entry point
├── pyproject.toml                 # Package configuration and script registration
├── requirements.txt               # App dependencies
└── README.md                      # This documentation
```

---

## Credits

* Droidlate's auto-translation suggestions feature was inspired by [Android strings.xml Translator](https://github.com/Heitezy/android_xml_translator).

---

## License

This project is licensed under the GNU General Public License v3.0 (GPL-3.0). See [LICENSE](file:///root/Projects/android-translator/LICENSE) for details.
