import os
import re
from flask import Flask, request, jsonify, send_from_directory
from typing import Optional

from ..parser.xml_parser import parse_strings_xml, write_string_translation
from ..parser.diff_engine import load_metadata, update_metadata_entry, categorize_key
from ..translator.engine import TranslationOrchestrator

# Initialize Flask app
# Static files will be served from the 'static' directory
app = Flask(__name__, static_folder='static', static_url_path='')

# Global configuration variables populated by main.py
RES_DIR: Optional[str] = None
SOURCE_XML: Optional[str] = None
TARGET_XML: Optional[str] = None
IS_SINGLE_FILE_MODE: bool = False

orchestrator = TranslationOrchestrator()

@app.route('/')
def index():
    """Serves the single-page application interface."""
    return app.send_static_file('index.html')

@app.route('/api/project', methods=['GET'])
def get_project():
    """Scans project resources or loads single-file translation config."""
    global RES_DIR, SOURCE_XML, TARGET_XML, IS_SINGLE_FILE_MODE
    
    if IS_SINGLE_FILE_MODE:
        # Single-file translation mode
        source_entries = parse_strings_xml(SOURCE_XML)
        target_entries = parse_strings_xml(TARGET_XML) if os.path.exists(TARGET_XML) else {}
        metadata = load_metadata(TARGET_XML)
        
        metadata_changed = False
        for key, entry in source_entries.items():
            if key in target_entries and key not in metadata:
                from ..parser.diff_engine import normalize_source_string, compute_source_hash
                norm_src = normalize_source_string(entry.value)
                metadata[key] = {
                    "source_hash": compute_source_hash(norm_src),
                    "translated_value": target_entries[key].value
                }
                metadata_changed = True
        if metadata_changed:
            from ..parser.diff_engine import save_metadata
            save_metadata(TARGET_XML, metadata)
            
        total = len(source_entries)
        untranslated = 0
        outdated = 0
        translated = 0
        orphaned = len([k for k in target_entries.keys() if k not in source_entries])
        
        for key, entry in source_entries.items():
            tgt_val = target_entries.get(key).value if key in target_entries else None
            meta_val = metadata.get(key)
            status = categorize_key(key, entry.value, tgt_val, meta_val)
            
            if status == "untranslated":
                untranslated += 1
            elif status in ("outdated", "warnings"):
                outdated += 1
            else:
                translated += 1
                
        progress = int((translated / total) * 100) if total > 0 else 0
        folder = os.path.basename(os.path.dirname(TARGET_XML))
        locale = folder.replace("values-", "") or "default"
        
        return jsonify({
            "mode": "single",
            "res_dir": None,
            "source_file": SOURCE_XML,
            "target_file": TARGET_XML,
            "languages": [{
                "folder": folder,
                "locale": locale,
                "progress": progress,
                "translated": translated,
                "outdated": outdated,
                "untranslated": untranslated,
                "orphaned": orphaned,
                "total": total,
                "target_path": TARGET_XML
            }]
        })
        
    else:
        # Directory scanning mode
        source_path = os.path.join(RES_DIR, "values", "strings.xml")
        if not os.path.exists(source_path):
            return jsonify({"error": "Base strings.xml not found."}), 404
            
        source_entries = parse_strings_xml(source_path)
        total_keys = len(source_entries)
        
        languages_list = []
        
        for folder in os.listdir(RES_DIR):
            folder_path = os.path.join(RES_DIR, folder)
            match = re.match(r"^values-([a-z]{2,3})(?:-r([a-zA-Z]{2,4}))?$", folder)
            if os.path.isdir(folder_path) and match:
                target_xml = os.path.join(folder_path, "strings.xml")
                target_entries = parse_strings_xml(target_xml) if os.path.exists(target_xml) else {}
                metadata = load_metadata(target_xml)
                
                metadata_changed = False
                for key, entry in source_entries.items():
                    if key in target_entries and key not in metadata:
                        from ..parser.diff_engine import normalize_source_string, compute_source_hash
                        norm_src = normalize_source_string(entry.value)
                        metadata[key] = {
                            "source_hash": compute_source_hash(norm_src),
                            "translated_value": target_entries[key].value
                        }
                        metadata_changed = True
                if metadata_changed:
                    from ..parser.diff_engine import save_metadata
                    save_metadata(target_xml, metadata)
                
                untranslated = 0
                outdated = 0
                translated = 0
                orphaned = len([k for k in target_entries.keys() if k not in source_entries])
                
                for key, entry in source_entries.items():
                    tgt_val = target_entries.get(key).value if key in target_entries else None
                    meta_val = metadata.get(key)
                    status = categorize_key(key, entry.value, tgt_val, meta_val)
                    
                    if status == "untranslated":
                        untranslated += 1
                    elif status in ("outdated", "warnings"):
                        outdated += 1
                    else:
                        translated += 1
                        
                progress = int((translated / total_keys) * 100) if total_keys > 0 else 0
                locale = folder.replace("values-", "")
                
                languages_list.append({
                    "folder": folder,
                    "locale": locale,
                    "progress": progress,
                    "translated": translated,
                    "outdated": outdated,
                    "untranslated": untranslated,
                    "orphaned": orphaned,
                    "total": total_keys,
                    "target_path": target_xml
                })
                
        return jsonify({
            "mode": "directory",
            "res_dir": RES_DIR,
            "source_file": source_path,
            "languages": sorted(languages_list, key=lambda x: x["folder"])
        })

@app.route('/api/strings', methods=['GET'])
def get_strings():
    """Returns the list of all keys and values for a target language folder."""
    global RES_DIR, SOURCE_XML, TARGET_XML, IS_SINGLE_FILE_MODE
    
    lang_folder = request.args.get('lang')
    if not lang_folder and not IS_SINGLE_FILE_MODE:
        return jsonify({"error": "Missing lang query parameter."}), 400
        
    if IS_SINGLE_FILE_MODE:
        src_path = SOURCE_XML
        tgt_path = TARGET_XML
    else:
        src_path = os.path.join(RES_DIR, "values", "strings.xml")
        tgt_path = os.path.join(RES_DIR, lang_folder, "strings.xml")
        
    if not os.path.exists(src_path):
        return jsonify({"error": "Source XML path does not exist."}), 404
        
    source_entries = parse_strings_xml(src_path)
    target_entries = parse_strings_xml(tgt_path) if os.path.exists(tgt_path) else {}
    metadata = load_metadata(tgt_path)
    
    metadata_changed = False
    
    # Auto-initialize legacy translations
    for key, entry in source_entries.items():
        if key in target_entries and key not in metadata:
            from ..parser.diff_engine import normalize_source_string, compute_source_hash
            norm_src = normalize_source_string(entry.value)
            metadata[key] = {
                "source_hash": compute_source_hash(norm_src),
                "translated_value": target_entries[key].value
            }
            metadata_changed = True
    if metadata_changed:
        from ..parser.diff_engine import save_metadata
        save_metadata(tgt_path, metadata)
    
    strings_list = []
    
    from ..parser.diff_engine import normalize_source_string, compute_source_hash
    
    # 1. Add current source entries
    for key, entry in source_entries.items():
        tgt_val = target_entries.get(key).value if key in target_entries else None
        meta_val = metadata.get(key)
        status = categorize_key(key, entry.value, tgt_val, meta_val)
        
        current_norm = normalize_source_string(entry.value)
        src_hash = compute_source_hash(current_norm)
        
        strings_list.append({
            "key": key,
            "source": entry.value,
            "source_hash": src_hash,
            "translation": tgt_val or "",
            "comment": entry.comment,
            "status": status,
            "attrib": entry.attrib
        })
        
    # 2. Add orphaned entries (exists in target but no longer in source)
    for key in target_entries.keys():
        if key not in source_entries:
            strings_list.append({
                "key": key,
                "source": "(Removed from English source XML file)",
                "source_hash": "",
                "translation": target_entries[key].value,
                "comment": "Orphaned key (no longer exists in source strings.xml)",
                "status": "orphaned",
                "attrib": target_entries[key].attrib
            })
        
    return jsonify({
        "locale": lang_folder or os.path.basename(os.path.dirname(tgt_path)),
        "strings": strings_list
    })

@app.route('/api/translate', methods=['POST'])
def save_translation():
    """Saves a string translation and updates metadata hashes."""
    global RES_DIR, SOURCE_XML, TARGET_XML, IS_SINGLE_FILE_MODE
    
    data = request.json or {}
    lang_folder = data.get('lang')
    key = data.get('key')
    value = data.get('value')
    client_source_hash = data.get('source_hash')
    
    if not key:
        return jsonify({"error": "Missing key."}), 400
        
    if IS_SINGLE_FILE_MODE:
        src_path = SOURCE_XML
        tgt_path = TARGET_XML
    else:
        if not lang_folder:
            return jsonify({"error": "Missing lang."}), 400
        src_path = os.path.join(RES_DIR, "values", "strings.xml")
        tgt_path = os.path.join(RES_DIR, lang_folder, "strings.xml")

    # Fetch original attributes
    source_entries = parse_strings_xml(src_path)
    if key not in source_entries:
        return jsonify({"error": f"Key {key} does not exist in source."}), 404
        
    src_entry = source_entries[key]
    
    # Stale-state check: verify client hash matches current source hash
    if client_source_hash:
        from ..parser.diff_engine import normalize_source_string, compute_source_hash
        current_norm = normalize_source_string(src_entry.value)
        current_hash = compute_source_hash(current_norm)
        if client_source_hash != current_hash:
            return jsonify({
                "error": "stale_source",
                "message": "The source string has been modified by another process. Please refresh the page.",
                "current_source": src_entry.value
            }), 409
    
    # Write translation to XML
    success = write_string_translation(tgt_path, key, value, src_entry.attrib)
    
    # Update sidecar metadata hash
    update_metadata_entry(tgt_path, key, src_entry.value, value)
    
    return jsonify({"success": success})

@app.route('/api/suggest', methods=['GET'])
def get_suggestion():
    """Fetches suggestions from translation providers."""
    global RES_DIR, SOURCE_XML, TARGET_XML, IS_SINGLE_MODE
    
    text = request.args.get('text', '')
    src = request.args.get('src', 'values')
    tgt = request.args.get('tgt', '')
    
    if not text or not tgt:
        return jsonify({"suggestions": []})
        
    suggestions = orchestrator.get_suggestions(text, src, tgt)
    
    res = [{"provider": name, "text": val} for name, val in suggestions]
    return jsonify({"suggestions": res})

@app.route('/api/prune', methods=['POST'])
def prune_string():
    """Removes an orphaned translation from target XML and metadata sidecar."""
    global RES_DIR, SOURCE_XML, TARGET_XML, IS_SINGLE_FILE_MODE
    data = request.get_json() or {}
    lang = data.get('lang')
    key = data.get('key')
    
    if not key:
        return jsonify({"error": "Missing key."}), 400
        
    if IS_SINGLE_FILE_MODE:
        tgt_path = TARGET_XML
    else:
        if not lang:
            return jsonify({"error": "Missing lang."}), 400
        tgt_path = os.path.join(RES_DIR, lang, "strings.xml")
        
    if not os.path.exists(tgt_path):
        return jsonify({"error": "Target file does not exist."}), 404
        
    # Remove translation from target XML
    from ..parser.xml_parser import remove_string_translation
    success = remove_string_translation(tgt_path, key)
    
    # Remove entry from metadata JSON ledger
    from ..parser.diff_engine import load_metadata, save_metadata
    metadata = load_metadata(tgt_path)
    if key in metadata:
        del metadata[key]
        save_metadata(tgt_path, metadata)
        
    return jsonify({"success": success})

def start_web_server(res_dir=None, source_xml=None, target_xml=None, port=5000):
    """Initializes server context and launches the Flask web service."""
    global RES_DIR, SOURCE_XML, TARGET_XML, IS_SINGLE_FILE_MODE
    
    RES_DIR = res_dir
    SOURCE_XML = source_xml
    TARGET_XML = target_xml
    IS_SINGLE_FILE_MODE = bool(source_xml and target_xml)
    
    # Bind to localhost standard local interfaces
    app.run(host='127.0.0.1', port=port, debug=False)
