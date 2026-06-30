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

def validate_placeholders(source_val: str, target_val: str) -> list[str]:
    """
    Validates placeholder formatting between source and target values.
    Returns a list of distinct validation warning messages (empty if valid).
    """
    src_pl = extract_placeholders(source_val)
    tgt_pl = extract_placeholders(target_val)
    
    src_res = resolve_placeholders(src_pl)
    tgt_res = resolve_placeholders(tgt_pl)
    
    src_map = {idx: t for idx, t in src_res}
    tgt_map = {idx: t for idx, t in tgt_res}
    
    errors = []
    
    # 1. Check for missing or type mismatches
    for idx, src_type in src_map.items():
        if idx not in tgt_map:
            errors.append(f"Missing placeholder %{idx}${src_type}")
        elif tgt_map[idx] != src_type:
            errors.append(f"Type mismatch for placeholder %{idx}: expected '{src_type}', got '{tgt_map[idx]}'")
            
    # 2. Check for unexpected/extra placeholders
    for idx, tgt_type in tgt_map.items():
        if idx not in src_map:
            errors.append(f"Extra/unexpected placeholder %{idx}${tgt_type}")
            
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

def get_metadata_path(target_xml_path: str) -> str:
    """Gets the path to the sidecar metadata file for a target XML."""
    dir_name = os.path.dirname(target_xml_path)
    base_name = os.path.basename(target_xml_path)
    metadata_name = f".{base_name}.metadata.json"
    return os.path.join(dir_name, metadata_name)

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
    """Updates a single key's metadata entry."""
    metadata = load_metadata(target_xml_path)
    norm_src = normalize_source_string(source_value)
    src_hash = compute_source_hash(norm_src)
    metadata[key] = {
        "source_hash": src_hash,
        "translated_value": translated_value
    }
    save_metadata(target_xml_path, metadata)

def categorize_key(
    key: str,
    source_val: str,
    target_val: str | None,
    metadata_entry: dict | None
) -> str:
    """
    Categorizes a translation key as 'untranslated', 'warnings', 'outdated', or 'translated'.
    """
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
