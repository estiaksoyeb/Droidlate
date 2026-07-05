import os
import re
import json
import hashlib

# Re-use the placeholder regex and logic
PLACEHOLDER_REGEX = re.compile(r'%([0-9]+\$)?[-#+ 0,\(<]*[0-9]*(\.[0-9]+)?([a-zA-Z%])')

def extract_placeholders(s: str) -> list[tuple[int | None, str]]:
    """Extracts Java/Android string format specifiers, skipping %% literals."""
    placeholders = []
    for match in PLACEHOLDER_REGEX.finditer(s):
        idx_str = match.group(1)
        ptype = match.group(3)
        if ptype == '%':
            continue
        idx = int(idx_str[:-1]) if idx_str else None
        placeholders.append((idx, ptype))
    return placeholders

def resolve_placeholders(placeholders: list[tuple[int | None, str]]) -> list[tuple[int, str]]:
    """Resolves unmarked format placeholders to explicit positional indices."""
    resolved = []
    implicit_idx = 1
    for pos, ptype in placeholders:
        if pos is None:
            resolved.append((implicit_idx, ptype))
            implicit_idx += 1
        else:
            resolved.append((pos, ptype))
    return resolved

def extract_html_tags(s: str) -> list[str]:
    tags = []
    # Match tags: opening, closing, or self-closing
    for m in re.finditer(r'</?([a-zA-Z0-9:-]+)(?:\s+[^>]*)?>', s):
        tag_text = m.group(0)
        tag_name = m.group(1).lower()
        is_closing = tag_text.startswith('</')
        if ' ' in tag_text:
            normalized = f"</{tag_name}>" if is_closing else f"<{tag_name}...>"
        else:
            normalized = tag_text.lower()
        tags.append(normalized)
    return tags

def validate_placeholders(source_val: str, target_val: str) -> list[str]:
    """
    Validates placeholder formatting, HTML tags, punctuation, and length ratio
    between source and target values. Returns a list of distinct validation warnings.
    """
    errors = []
    if not target_val:
        return errors

    # 1. Placeholder check
    src_pl = extract_placeholders(source_val)
    tgt_pl = extract_placeholders(target_val)
    
    src_res = resolve_placeholders(src_pl)
    tgt_res = resolve_placeholders(tgt_pl)
    
    src_map = {idx: t for idx, t in src_res}
    tgt_map = {idx: t for idx, t in tgt_res}
    
    for idx, src_type in src_map.items():
        if idx not in tgt_map:
            errors.append(f"Missing placeholder %{idx}${src_type}")
        elif tgt_map[idx] != src_type:
            errors.append(f"Type mismatch for placeholder %{idx}: expected '{src_type}', got '{tgt_map[idx]}'")
            
    for idx, tgt_type in tgt_map.items():
        if idx not in src_map:
            errors.append(f"Extra/unexpected placeholder %{idx}${tgt_type}")

    # 2. HTML Tags check
    src_tags = extract_html_tags(source_val)
    tgt_tags = extract_html_tags(target_val)
    
    # Check for missing tags in target
    for tag in set(src_tags):
        if tag not in tgt_tags:
            errors.append(f"Missing HTML tag '{tag}'")
        elif tgt_tags.count(tag) < src_tags.count(tag):
            errors.append(f"Missing HTML tag '{tag}' (count mismatch)")

    # Check for extra tags in target
    for tag in set(tgt_tags):
        if tag not in src_tags:
            errors.append(f"Extra/unexpected HTML tag '{tag}'")
        elif tgt_tags.count(tag) > src_tags.count(tag):
            errors.append(f"Extra/unexpected HTML tag '{tag}' (count mismatch)")
    return errors

def normalize_source_string(val: str) -> str:
    """Normalizes string value before hashing (collapsing whitespace/newlines)."""
    if not val:
        return ""
    # Collapse multiple consecutive whitespace characters to a single space
    normalized = re.sub(r'\s+', ' ', val)
    return normalized.strip()

def compute_source_hash(normalized_val: str) -> str:
    """Computes a SHA-256 hash of the normalized source string."""
    return hashlib.sha256(normalized_val.encode('utf-8')).hexdigest()

def get_project_root(path: str) -> str:
    """Finds the root directory of the Android project by searching upwards."""
    current = os.path.abspath(path)
    if os.path.isfile(current):
        current = os.path.dirname(current)
        
    while current and current != os.path.dirname(current):
        if any(os.path.exists(os.path.join(current, marker)) for marker in ("build.gradle", "build.gradle.kts", "settings.gradle", ".git")):
            return current
        current = os.path.dirname(current)
        
    # Fallback to parent of target directory
    return os.path.dirname(os.path.abspath(path))

def get_metadata_path(target_xml_path: str) -> str:
    """Gets the path to the centralized metadata file for a target XML."""
    target_abs = os.path.abspath(target_xml_path)
    project_root = get_project_root(target_abs)
    
    locale_folder = os.path.basename(os.path.dirname(target_abs))
    metadata_dir = os.path.join(project_root, ".translation_metadata")
    return os.path.join(metadata_dir, f"{locale_folder}.json")

def load_metadata(target_xml_path: str) -> dict:
    """Loads target sidecar metadata JSON file."""
    path = get_metadata_path(target_xml_path)
    if os.path.exists(path):
        try:
            with open(path, 'r', encoding='utf-8') as f:
                return json.load(f)
        except Exception:
            return {}
    return {}

def save_metadata(target_xml_path: str, metadata: dict) -> None:
    """Saves metadata dictionary to sidecar JSON file."""
    path = get_metadata_path(target_xml_path)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    try:
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(metadata, f, ensure_ascii=False, indent=2)
    except Exception:
        pass

def update_metadata_entry(target_xml_path: str, key: str, source_value: str, translated_value: str) -> None:
    """Updates a single key's metadata entry and records it in Translation Memory."""
    metadata = load_metadata(target_xml_path)
    norm_src = normalize_source_string(source_value)
    src_hash = compute_source_hash(norm_src)
    metadata[key] = {
        "source_hash": src_hash,
        "translated_value": translated_value
    }
    save_metadata(target_xml_path, metadata)
    
    # Record mapping in Translation Memory
    update_tm_entry(target_xml_path, source_value, translated_value)

def categorize_key(
    key: str,
    source_val: str,
    target_val: str | None,
    metadata_entry: dict | None,
    attrib: dict | None = None
) -> str:
    """
    Categorizes a translation key as 'readonly', 'untranslated', 'warnings', 'outdated', or 'translated'.
    """
    # 0. Read-only check (translatable="false")
    if attrib and attrib.get('translatable') == 'false':
        return 'readonly'

    # 1. Untranslated check
    if target_val is None:
        return 'untranslated'

    # 2. Warnings/Errors check
    warnings = validate_placeholders(source_val, target_val)
    if warnings:
        return 'warnings'

    # 3. Outdated/Modified check
    if not metadata_entry:
        return 'outdated'

    saved_hash = metadata_entry.get("source_hash")
    current_norm = normalize_source_string(source_val)
    current_hash = compute_source_hash(current_norm)

    if saved_hash != current_hash:
        return 'outdated'

    return 'translated'

def get_global_tm_dir() -> str:
    """Returns the platform-specific global directory for storing TM caches."""
    import platform
    home = os.path.expanduser("~")
    
    if platform.system() == "Windows":
        local_appdata = os.environ.get("LOCALAPPDATA", os.path.join(home, "AppData", "Local"))
        return os.path.join(local_appdata, "droidlate", "tm_cache")
    elif platform.system() == "Darwin": # macOS
        return os.path.join(home, "Library", "Caches", "droidlate", "tm_cache")
    else: # Linux / Android / Termux
        xdg_cache = os.environ.get("XDG_CACHE_HOME", os.path.join(home, ".cache"))
        return os.path.join(xdg_cache, "droidlate", "tm_cache")

def get_project_hash(project_root: str) -> str:
    """Generates a short SHA-256 hash of the project root directory path."""
    import hashlib
    norm_path = os.path.normcase(os.path.abspath(project_root))
    return hashlib.sha256(norm_path.encode('utf-8')).hexdigest()[:16]

def get_tm_path(target_xml_path: str) -> str:
    """Gets the path to the global translation memory JSON file for a target XML."""
    target_abs = os.path.abspath(target_xml_path)
    project_root = get_project_root(target_abs)
    project_hash = get_project_hash(project_root)
    locale_folder = os.path.basename(os.path.dirname(target_abs))
    
    global_dir = get_global_tm_dir()
    os.makedirs(global_dir, exist_ok=True)
    return os.path.join(global_dir, f"tm_{project_hash}_{locale_folder}.json")

def load_tm(target_xml_path: str) -> dict:
    """Loads target Translation Memory JSON ledger."""
    path = get_tm_path(target_xml_path)
    if os.path.exists(path):
        try:
            with open(path, 'r', encoding='utf-8') as f:
                return json.load(f)
        except Exception:
            return {}
    return {}

def save_tm(target_xml_path: str, tm: dict) -> None:
    """Saves Translation Memory ledger to file."""
    path = get_tm_path(target_xml_path)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    try:
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(tm, f, ensure_ascii=False, indent=2)
    except Exception:
        pass

def update_tm_entry(target_xml_path: str, source_value: str, translated_value: str) -> None:
    """Adds or updates a translation memory mapping for a given source string."""
    if not source_value.strip() or not translated_value.strip():
        return
    tm = load_tm(target_xml_path)
    norm_src = normalize_source_string(source_value)
    tm[norm_src] = translated_value
    save_tm(target_xml_path, tm)

def get_tm_suggestion(target_xml_path: str, source_value: str) -> str | None:
    """Retrieves a translation suggestion from local TM if available."""
    tm = load_tm(target_xml_path)
    norm_src = normalize_source_string(source_value)
    return tm.get(norm_src)

def rebuild_tm_cache(target_xml_path: str, source_entries: dict, target_entries: dict) -> None:
    """Rebuilds/Syncs the translation memory cache from current file states."""
    tm = load_tm(target_xml_path)
    changed = False
    for key, entry in source_entries.items():
        if key in target_entries:
            norm_src = normalize_source_string(entry.value)
            tgt_val = target_entries[key].value
            if tgt_val and tm.get(norm_src) != tgt_val:
                tm[norm_src] = tgt_val
                changed = True
    if changed:
        save_tm(target_xml_path, tm)

def prune_nontranslatable_strings(target_path: str, source_entries: dict, target_entries: dict) -> bool:
    """
    Finds target entries that are marked translatable="false" in source_entries,
    and automatically prunes them from target XML and metadata sidecars.
    Returns True if any changes were made.
    """
    from .xml_parser import remove_string_translation
    
    changed = False
    metadata = None
    
    # We copy keys to avoid modifying dict while iterating
    source_keys = list(source_entries.keys())
    for key in source_keys:
        if key in source_entries:
            entry = source_entries[key]
            if entry.attrib.get('translatable') == 'false':
                if key in target_entries:
                    # Remove from target XML file
                    remove_string_translation(target_path, key)
                    # Remove from target_entries dict
                    del target_entries[key]
                    
                    # Remove from metadata sidecar
                    if metadata is None:
                        metadata = load_metadata(target_path)
                    if key in metadata:
                        del metadata[key]
                        
                    changed = True
                
    if changed and metadata is not None:
        save_metadata(target_path, metadata)
        
    return changed
