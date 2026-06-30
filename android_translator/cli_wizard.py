import os
import sys
import re

from .parser.xml_parser import parse_strings_xml, write_string_translation
from .parser.diff_engine import load_metadata, update_metadata_entry, categorize_key, validate_placeholders
from .main import auto_detect_res_dir

def run_wizard():
    print("=== Android Translation Manager: CLI Wizard ===")
    
    # Auto-detect res dir
    res_dir = auto_detect_res_dir()
    print(f"Auto-detected resource directory: {res_dir}")
    
    source_path = os.path.join(res_dir, "values", "strings.xml")
    if not os.path.exists(source_path):
        print(f"Error: Could not find base strings file at '{source_path}'.")
        sys.exit(1)
        
    source_entries = parse_strings_xml(source_path)
    if not source_entries:
        print("Error: Base strings.xml is empty or invalid.")
        sys.exit(1)
        
    # Scan for target languages
    locales = []
    for folder in os.listdir(res_dir):
        folder_path = os.path.join(res_dir, folder)
        match = re.match(r"^values-([a-z]{2,3})(?:-r([a-zA-Z]{2,4}))?$", folder)
        if os.path.isdir(folder_path) and match:
            target_xml = os.path.join(folder_path, "strings.xml")
            locales.append((folder, target_xml))
            
    if not locales:
        print("No localized values-* directories found.")
        sys.exit(0)
        
    print("\nAvailable Locales:")
    for idx, (folder, _) in enumerate(sorted(locales)):
        print(f"[{idx + 1}] {folder}")
        
    try:
        selection = input("\nSelect a language number to translate: ").strip()
        sel_idx = int(selection) - 1
        if sel_idx < 0 or sel_idx >= len(locales):
            raise ValueError()
    except (ValueError, KeyboardInterrupt):
        print("\nExiting wizard.")
        sys.exit(0)
        
    target_folder, target_xml = sorted(locales)[sel_idx]
    print(f"\nLoading translations for: {target_folder}")
    
    # Load target entries and metadata
    target_entries = parse_strings_xml(target_xml) if os.path.exists(target_xml) else {}
    metadata = load_metadata(target_xml)
    
    metadata_changed = False
    for key, entry in source_entries.items():
        if key in target_entries and key not in metadata:
            from .parser.diff_engine import normalize_source_string, compute_source_hash
            norm_src = normalize_source_string(entry.value)
            metadata[key] = {
                "source_hash": compute_source_hash(norm_src),
                "translated_value": target_entries[key].value
            }
            metadata_changed = True
    if metadata_changed:
        from .parser.diff_engine import save_metadata
        save_metadata(target_xml, metadata)
    
    # Filter keys needing attention (untranslated, outdated, warnings)
    todo_keys = []
    for key, entry in source_entries.items():
        tgt_val = target_entries.get(key).value if key in target_entries else None
        meta_val = metadata.get(key)
        status = categorize_key(key, entry.value, tgt_val, meta_val)
        
        if status != "translated":
            todo_keys.append((key, entry, tgt_val, status))
            
    if not todo_keys:
        print("All strings are already translated and up-to-date!")
        sys.exit(0)
        
    print(f"Found {len(todo_keys)} strings requiring attention.")
    print("Commands: Type ':q' to quit, press Enter with empty text to skip.")
    print("-" * 50)
    
    for idx, (key, src_entry, tgt_val, status) in enumerate(todo_keys):
        print(f"\n[{idx + 1}/{len(todo_keys)}] Key: {key} [Status: {status.upper()}]")
        print(f"Source: {repr(src_entry.value)}")
        if src_entry.comment:
            print(f"Comment: {src_entry.comment}")
        if tgt_val is not None:
            print(f"Current Target: {repr(tgt_val)}")
            
        try:
            translation = input("Translation > ").strip()
        except (KeyboardInterrupt, EOFError):
            print("\nWizard interrupted. Exiting safely.")
            break
            
        if translation == ":q":
            print("Exiting wizard.")
            break
        elif not translation:
            print("Skipped.")
            continue
            
        # Check formatting warnings before saving
        warnings = validate_placeholders(src_entry.value, translation)
        if warnings:
            print("\nWARNING: Placeholder mismatches detected:")
            for warn in warnings:
                print(f"  - {warn}")
            confirm = input("Are you sure you want to save this translation? (y/N): ").strip().lower()
            if confirm != 'y':
                print("Skipped (not saved).")
                continue
                
        print("Saving...")
        
        # Order of Writes: target XML first, metadata sidecar second
        # If interrupted here, target exists but metadata does not, resolving to 'outdated' on next load.
        write_success = write_string_translation(target_xml, key, translation, src_entry.attrib)
        if write_success:
            update_metadata_entry(target_xml, key, src_entry.value, translation)
            print("Saved successfully.")
        else:
            print("Error saving to target XML.")
            
    print("\nWizard finished.")

if __name__ == "__main__":
    run_wizard()
