# Droidlate: Multi-Phase Implementation Plan

This document details the test-driven implementation strategy for Droidlate. To minimize risk, eliminate bugs at the foundation, and ensure high fidelity, the project is divided into four distinct phases, each with independent validation targets.

---

## Phase 1: `xml_parser.py` Round-Trip Fidelity

**Objective:** Parse an Android `strings.xml` file into key/value/comment representations, modify a value, and write it back, ensuring that the output byte structure matches the original perfectly (preserving comments, XML declarations, and formatting) except for the edited value.

### Key Logic & API
* **Data Model:**
  ```python
  class StringEntry:
      def __init__(self, key: str, value: str, comment: str = "", attrib: dict = None):
          self.key = key
          self.value = value       # Clean unescaped string for UI
          self.comment = comment   # Preceding XML comment string
          self.attrib = attrib or {} # Other XML attributes (e.g., translatable)
  ```
* **Parsing:** `parse_strings_xml(file_path: str) -> dict[str, StringEntry]`
* **Writing:** `write_string_translation(target_path: str, key: str, value: str, attrib: dict = None) -> bool`
  * **File Scan Contract:** On every write operation, the file is re-scanned from scratch to find character offsets rather than relying on stale pre-parsed handles or cached line offsets. This avoids stale-state bugs and handles external edits.
  * Relies on precise character index scanning via `xml.parsers.expat` to replace content in-place without destroying surrounding comments/spacing.
  * Correctly escapes and unescapes Android XML syntax:
    * Single quotes (`'`) -> `\'`
    * Double quotes (`"`) -> `\"`
    * Standard XML entities (`&`, `<`, `>`) -> `&amp;`, `&lt;`, `&gt;`
    * **Positional leading escapes:** Escapes `@` and `?` *only* if they occur at the very beginning of the string value (i.e. index 0). Any occurrences of `@` or `?` inside/middle of the string are left completely unescaped.
    * Retains newlines and tabs (`\n`, `\t`).

### Validation Target
Verify parsing and serializing of a fixture XML containing:
- Preceding developer comments.
- Attributes (like `translatable="false"`).
- Special characters needing escaping (e.g. `'`, `"`, `&`).
- Positional leading escapes (e.g. `@string/app_name` at start vs. `Hello @username` in the middle).
- Format specifiers (`%s`, `%1$d`).
Running a byte diff on output vs. input must show ONLY the exact changed translation string with no metadata, comment, or whitespace pollution.

---

## Phase 2: `diff_engine.py` & Hashing (Offline Validation)

**Objective:** Determine the translation status of any key (untranslated, outdated/modified, translated, placeholders warnings) using normalized source hashes and sidecar metadata, verified via fixture unit tests.

### Key Logic & API
* **Normalization:** `normalize_source_string(val: str) -> str`
  * Collapses multiple whitespaces/newlines and normalizes XML entities to prevent false positives.
* **Sidecar Metadata:** Updates `.strings.xml.metadata.json` mapping:
  ```json
  {
    "string_key": {
      "source_hash": "sha256...",
      "translated_value": "..."
    }
  }
  ```
* **Placeholder Validation:** `validate_placeholders(source_val: str, target_val: str) -> list[str]`
  * Validates sequence alignment and matching types for Java format strings (e.g. `%s`, `%1$d`).
  * **API Contract:** Returns a `list[str]` containing distinct validation errors/warnings (e.g. `["Missing placeholder %s", "Type mismatch on index 2"]`), or an empty list if valid.
* **Categorization:** `categorize_key(key, src_val, tgt_val, metadata_entry) -> str`
  * Returns: `'untranslated'`, `'outdated'`, `'warnings'`, or `'translated'`.
  * **Missing Sidecar Contract:** If the target value (`tgt_val`) is present in the target XML file but the metadata entry (`metadata_entry`) is missing/`None` (due to an interrupted save or legacy file), it must default to `'outdated'` (requires review/re-saving) rather than being blindly marked as `'translated'`.

### Validation Target
Unit tests using hardcoded fixtures representing all categories (including duplicate placeholders, shifted position numbers, modified source text, and missing translations) to ensure status logic is 100% accurate before any UI is built.

---

## Phase 3: Minimal CLI Loop Workflow

**Objective:** Create a lightweight, interactive console-based wizard that cycles through untranslated or outdated keys, prompts for input, and writes changes directly to disk. This validates the actual user workspace loop in isolation.

### Key Logic & API
* CLI scans source and target directories.
* Displays a list of locales.
* For the selected locale, filters keys needing attention.
* Prompts the user sequentially:
  ```text
  Key: welcome_msg [Untranslated]
  Source: Hello %1$s, welcome!
  Translation > _
  ```
* **Atomicity & Order of Writes:**
  1. Write translation to target XML first.
  2. Write metadata update to `.strings.xml.metadata.json` second.
  If the process terminates between writes (e.g. Ctrl+C), the target translation exists but the metadata entry is missing, causing the tool to safely categorize the key as `'outdated'` upon next launch (as defined in Phase 2's Missing Sidecar Contract).
* Supports commands: `:q` (quit), `:s` (skip), and displays validation warning logs.

### Validation Target
A working terminal session where translation files can be successfully updated key-by-key using only standard input/output. Verify status resilience by stopping the wizard mid-run.

---

## Phase 4: Local Web Server UI Layer

**Objective:** Build a Flask server hosting REST APIs that wire the tested engines of Phases 1-3 to a premium modern web application SPA (HTML/JS/CSS) served on localhost.

### REST APIs
* `GET /api/project`: Returns directories, language locales, progress bars, and statistics.
* `GET /api/strings?lang=<lang>`: Returns strings list, comment info, formats, and translation states.
* `POST /api/translate`: Writes translation to target XML, updates metadata, and triggers statistics updates.
  * **Stale-State Re-check:** On request arrival, `/api/translate` re-checks the source hash against the current source file to prevent stale/overwritten updates from concurrent edits.
* `GET /api/suggest`: Queries translation providers (Google Translate, MyMemory, DeepL) asynchronously.

### Security & Race Guards
* **Server Binding Security:** The Flask backend is strictly bound to the localhost interface: `app.run(host="127.0.0.1", port=5000)`. It must not listen on `0.0.0.0` to protect local filesystems from arbitrary remote writes.
* **Suggestion Race Guard:** `app.js` checks current active editing key at suggestion load time. If the user has changed keys, the late-arriving suggest request payload is silently discarded.

### UI Experience
* Sleek dark mode SPA matching the Weblate layout.
* Responsive sidebar keys list with status badges.
* Validation checks run instantly in the browser on character keypress.
* Auto-suggestions display as selectable cards.
