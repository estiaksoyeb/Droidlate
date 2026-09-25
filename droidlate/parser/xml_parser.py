import xml.etree.ElementTree as ET
import xml.parsers.expat
import os
import re

class StringEntry:
    """Represents a single parsed Android string resource."""
    def __init__(self, key, value, comment="", attrib=None):
        self.key = key
        self.value = value  # Unescaped value for UI
        self.comment = comment  # Associated developer comments
        self.attrib = attrib or {}

def normalize_attrib_key(key: str) -> str:
    """
    Normalizes XML attribute keys parsed by ElementTree (Clark notation)
    into standard namespace prefixes.
    e.g. '{http://schemas.android.com/tools}ignore' -> 'tools:ignore'
    """
    if not key:
        return ""
    if key.startswith('{http://schemas.android.com/tools}'):
        return 'tools:' + key[len('{http://schemas.android.com/tools}'):]
    elif key.startswith('{http://schemas.android.com/apk/res/android}'):
        return 'android:' + key[len('{http://schemas.android.com/apk/res/android}'):]
    elif key.startswith('{http://schemas.android.com/apk/res-auto}'):
        return 'app:' + key[len('{http://schemas.android.com/apk/res-auto}'):]
    elif key.startswith('{urn:oasis:names:tc:xliff:document:1.2}'):
        return 'xliff:' + key[len('{urn:oasis:names:tc:xliff:document:1.2}'):]
    return key

def sanitize_target_attributes(attrib: dict | None) -> dict:
    """
    Sanitizes source entry attributes before copying/writing them to a target localized XML file.
    - Excludes internal metadata (keys starting with '__').
    - Excludes tooling/lint attributes (e.g. tools:* or {http://schemas.android.com/tools}* or ignore).
    - Excludes 'translatable' attributes.
    - Excludes unresolved Clark notation keys containing curly braces.
    """
    if not attrib:
        return {}
    clean_attrib = {}
    for k, v in attrib.items():
        if k.startswith('__'):
            continue
        # Ignore tools namespace attributes (tools:ignore, tools:targetApi, tools:locale, etc.)
        if 'http://schemas.android.com/tools' in k or k.startswith('tools:') or k == 'ignore':
            continue
        # translatable="false" should never be copied to translated locale files
        if k == 'translatable':
            continue
        # Discard any raw Clark notation {uri} keys that cannot be written safely as XML attributes
        if '{' in k or '}' in k:
            continue
        clean_attrib[k] = v
    return clean_attrib


def unescape_android_string(raw_val: str) -> str:
    """
    Unescapes an Android strings.xml raw value into a plain string for the UI.
    - Strips outer double quotes if they wrap the entire string.
    - Unescapes XML entities: &amp; -> &, &lt; -> <, &gt; -> >, &quot; -> ", &apos; -> '
    - Unescapes Android escapes: \\' -> ', \\" -> \", \\n -> newline, \\t -> tab, \\\\ -> \\
    - Unescapes leading \\@ and \\? -> @ and ?
    """
    if not raw_val:
        return ""

    raw_val = raw_val.replace('\r\n', '\n').replace('\r', '\n')

    # 1. Check if the string is wrapped in double quotes
    # (Android allows quotes to wrap the whole string to avoid escaping apostrophes)
    is_wrapped = len(raw_val) >= 2 and raw_val.startswith('"') and raw_val.endswith('"')
    if is_wrapped:
        val = raw_val[1:-1]
    else:
        val = raw_val

    # 2. Unescape Android backslash escapes
    # We do a character-by-character scan or regex replacement to handle escapes safely.
    result = []
    i = 0
    n = len(val)
    while i < n:
        if val[i] == '\\' and i + 1 < n:
            next_char = val[i+1]
            if next_char == 'n':
                result.append('\n')
            elif next_char == 't':
                result.append('\t')
            elif next_char in ("'", '"', '\\', '@', '?'):
                result.append(next_char)
            else:
                # Keep the backslash if it escapes something else
                result.append('\\')
                result.append(next_char)
            i += 2
        else:
            result.append(val[i])
            i += 1
    val = "".join(result)

    # 3. Unescape standard XML entities if they are still present
    # (Standard XML parsing handles this, but since we might fetch raw text, we do it here)
    val = val.replace('&amp;', '&')
    val = val.replace('&lt;', '<')
    val = val.replace('&gt;', '>')
    val = val.replace('&quot;', '"')
    val = val.replace('&apos;', "'")

    return val

def escape_android_string(val: str) -> str:
    """
    Escapes a plain string from the UI into a valid Android strings.xml value.
    - Preserves valid XML/HTML markup tags (e.g. <b>, <i>, <u>, <font ...>, <xliff:g ...>) and entities (&amp;, &lt;, &gt;, &quot;, &apos;).
    - Escapes stray/unmatched XML characters: & -> &amp;, < -> &lt;, > -> &gt;
    - Escapes Android characters: ' -> \\', " -> \\"
    - Escapes control characters: newlines -> \\n, tabs -> \\t
    - Escapes leading @ and ? -> \\@ and \\?
    """
    if not val:
        return ""

    tokens = []
    last_end = 0
    
    # Combined pattern for XML entity or tag
    combined_pattern = re.compile(r"(&(?:amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);|</?[a-zA-Z0-9_:-]+(?:\s+[^>]*)?/?>)")
    
    for match in combined_pattern.finditer(val):
        start, end = match.span()
        if start > last_end:
            tokens.append(("text", val[last_end:start]))
        matched_str = match.group(0)
        tokens.append(("tag_or_entity", matched_str))
        last_end = end
    if last_end < len(val):
        tokens.append(("text", val[last_end:]))
        
    result_parts = []
    is_start = True
    
    for token_type, token_val in tokens:
        if token_type == "tag_or_entity":
            result_parts.append(token_val)
            is_start = False
        else:
            escaped_text = token_val.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            escaped_chars = []
            for idx, char in enumerate(escaped_text):
                if char == "'":
                    escaped_chars.append("\\'")
                elif char == '"':
                    escaped_chars.append('\\"')
                elif char == '\n':
                    escaped_chars.append('\\n')
                elif char == '\t':
                    escaped_chars.append('\\t')
                elif char == '\\':
                    escaped_chars.append('\\\\')
                elif (char == '@' or char == '?') and is_start and idx == 0:
                    escaped_chars.append('\\' + char)
                else:
                    escaped_chars.append(char)
            result_parts.append("".join(escaped_chars))
            if token_val:
                is_start = False
                
    return "".join(result_parts)

def parse_strings_xml(file_path: str) -> dict[str, StringEntry]:
    """
    Parses an Android strings.xml file, extracting all standard <string> elements,
    as well as <plurals> and <string-array> elements.
    Associates preceding comments with each string key.
    Returns a dictionary of key -> StringEntry.
    """
    if not os.path.exists(file_path):
        return {}

    try:
        # Standard parser with comment insertion
        parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
        tree = ET.parse(file_path, parser=parser)
        root = tree.getroot()
    except Exception:
        # Return empty if XML is malformed
        return {}

    entries = {}
    current_comments = []

    for child in root:
        # Check if the child is a comment node (tag is callable in Python's ElementTree when custom target is used)
        if callable(child.tag):
            comment_text = child.text.strip() if child.text else ""
            if comment_text:
                current_comments.append(comment_text)
        elif child.tag == 'string':
            key = child.attrib.get('name')
            if key:
                # Extract inner raw XML content to handle tags like <b> or <i>
                raw = ET.tostring(child, encoding='utf-8').decode('utf-8')
                start_tag_end = raw.find('>') + 1
                end_tag_start = raw.rfind('<')
                raw_value = raw[start_tag_end:end_tag_start] if start_tag_end > 0 and end_tag_start > start_tag_end else (child.text or "")
                
                # Unescape for UI representation
                value = unescape_android_string(raw_value)
                comment = "\n".join(current_comments)
                
                # Exclude internal/reserved attributes from standard attributes list
                attrib = {normalize_attrib_key(k): v for k, v in child.attrib.items() if k != 'name'}
                
                entries[key] = StringEntry(key, value, comment, attrib)
            current_comments = []
        elif child.tag == 'plurals':
            key = child.attrib.get('name')
            if key:
                comment = "\n".join(current_comments)
                attrib = {normalize_attrib_key(k): v for k, v in child.attrib.items() if k != 'name'}
                attrib['__resource_type__'] = 'plurals'
                # Find all <item> children
                for item in child.findall('item'):
                    quantity = item.attrib.get('quantity')
                    if quantity:
                        item_key = f"{key}#plural#{quantity}"
                        raw = ET.tostring(item, encoding='utf-8').decode('utf-8')
                        start_tag_end = raw.find('>') + 1
                        end_tag_start = raw.rfind('<')
                        raw_value = raw[start_tag_end:end_tag_start] if start_tag_end > 0 and end_tag_start > start_tag_end else (item.text or "")
                        value = unescape_android_string(raw_value)
                        
                        item_attrib = attrib.copy()
                        item_attrib['__quantity__'] = quantity
                        entries[item_key] = StringEntry(item_key, value, comment, item_attrib)
            current_comments = []
        elif child.tag == 'string-array':
            key = child.attrib.get('name')
            if key:
                comment = "\n".join(current_comments)
                attrib = {normalize_attrib_key(k): v for k, v in child.attrib.items() if k != 'name'}
                attrib['__resource_type__'] = 'string-array'
                # Find all <item> children in order
                for index, item in enumerate(child.findall('item')):
                    item_key = f"{key}#array#{index}"
                    raw = ET.tostring(item, encoding='utf-8').decode('utf-8')
                    start_tag_end = raw.find('>') + 1
                    end_tag_start = raw.rfind('<')
                    raw_value = raw[start_tag_end:end_tag_start] if start_tag_end > 0 and end_tag_start > start_tag_end else (item.text or "")
                    value = unescape_android_string(raw_value)
                    
                    item_attrib = attrib.copy()
                    item_attrib['__index__'] = str(index)
                    entries[item_key] = StringEntry(item_key, value, comment, item_attrib)
            current_comments = []
        else:
            # Skip non-string elements but clear collected comments so they don't leak
            current_comments = []

    return entries

def find_tag_end(content: str, start_idx: int) -> int:
    """Finds the closing '>' of an XML tag starting at start_idx, ignoring '>' inside attribute quotes."""
    in_quote = None
    for i in range(start_idx, len(content)):
        char = content[i]
        if in_quote:
            if char == in_quote:
                in_quote = None
        else:
            if char in ('"', "'"):
                in_quote = char
            elif char == '>':
                return i
    return -1

def parse_xml_positions(content: str, include_all: bool = False):
    """
    Parses XML content and returns:
    1. A dictionary of element positions: key -> { 'start_idx': int, 'end_idx': int, 'is_self_closing': bool, 'line': int, 'attrib': dict }
       Keys can be standard (e.g. 'my_key'), plurals (e.g. 'my_plural#plural#one'), or arrays (e.g. 'my_array#array#0').
    2. A dictionary of parent container positions: container_key -> { 'type': str, 'start_idx': int, 'end_idx': int, 'inner_start': int, 'inner_end': int, 'is_self_closing': bool, 'line': int, 'attrib': dict }
       Container keys are e.g. 'my_plural#plural' or 'my_array#array'.
    If include_all is True, also returns:
    3. all_item_positions: dict[str, list[dict]] (all occurrences of each item key)
    4. all_parent_positions: dict[str, list[dict]] (all occurrences of each parent container key)
    """
    lines = content.splitlines(keepends=True)
    line_starts = [0]
    for line in lines:
        line_starts.append(line_starts[-1] + len(line))
    
    def get_index(line_num, col_num):
        if 1 <= line_num <= len(lines):
            return line_starts[line_num - 1] + col_num
        return line_starts[-1] + col_num

    item_positions = {}
    parent_positions = {}
    all_item_positions = {}
    all_parent_positions = {}

    parser = xml.parsers.expat.ParserCreate()

    cur_parent_type = None  # 'plurals' or 'string-array'
    cur_parent_name = None
    cur_parent_start = None
    cur_parent_inner_start = None
    cur_parent_self_closing = False
    cur_item_index = 0
    cur_item_quantity = None
    cur_item_start = None
    cur_item_self_closing = False

    open_strings = []
    open_items = []
    open_parents = []

    def start_element(name, attrs):
        nonlocal cur_parent_type, cur_parent_name, cur_parent_start, cur_parent_inner_start, cur_parent_self_closing
        nonlocal cur_item_index, cur_item_quantity, cur_item_start, cur_item_self_closing
        
        idx = get_index(parser.CurrentLineNumber, parser.CurrentColumnNumber)
        tag_end = find_tag_end(content, idx)
        self_closing = tag_end != -1 and content[idx:tag_end].rstrip().endswith('/')
        
        if name in ('plurals', 'string-array'):
            cur_parent_type = name
            cur_parent_name = attrs.get('name')
            cur_parent_start = idx
            cur_parent_self_closing = self_closing
            cur_parent_inner_start = tag_end + 1 if tag_end != -1 else idx
            cur_item_index = 0
            
            p_data = {
                'type': name,
                'name': cur_parent_name,
                'start_idx': idx,
                'inner_start': cur_parent_inner_start,
                'is_self_closing': self_closing,
                'line': parser.CurrentLineNumber,
                'attrib': attrs
            }
            open_parents.append(p_data)
        elif name == 'string':
            string_name = attrs.get('name')
            if string_name:
                pos = {
                    'key': string_name,
                    'start_idx': idx,
                    'is_self_closing': self_closing,
                    'line': parser.CurrentLineNumber,
                    'attrib': attrs
                }
                open_strings.append(pos)
                if string_name not in all_item_positions:
                    all_item_positions[string_name] = []
                all_item_positions[string_name].append(pos)
                item_positions[string_name] = pos
        elif name == 'item' and cur_parent_type:
            cur_item_start = idx
            cur_item_self_closing = self_closing
            if cur_parent_type == 'plurals':
                cur_item_quantity = attrs.get('quantity')
            else:
                cur_item_quantity = None
            pos = {
                'parent_type': cur_parent_type,
                'parent_name': cur_parent_name,
                'quantity': cur_item_quantity,
                'index': cur_item_index,
                'start_idx': idx,
                'is_self_closing': self_closing,
                'line': parser.CurrentLineNumber,
                'attrib': attrs
            }
            open_items.append(pos)

    def end_element(name):
        nonlocal cur_parent_type, cur_parent_name, cur_parent_start, cur_parent_inner_start, cur_parent_self_closing
        nonlocal cur_item_index, cur_item_quantity, cur_item_start, cur_item_self_closing
        
        idx = get_index(parser.CurrentLineNumber, parser.CurrentColumnNumber)
        
        if name in ('plurals', 'string-array'):
            if open_parents:
                p_data = open_parents.pop()
                p_name = p_data['name']
                p_type = p_data['type']
                if p_name:
                    parent_key = f"{p_name}#{'plural' if p_type == 'plurals' else 'array'}"
                    p_pos = {
                        'type': p_type,
                        'name': p_name,
                        'start_idx': p_data['start_idx'],
                        'end_idx': idx,
                        'inner_start': p_data['inner_start'],
                        'inner_end': idx,
                        'is_self_closing': p_data['is_self_closing'],
                        'line': p_data['line'],
                        'attrib': p_data['attrib']
                    }
                    parent_positions[parent_key] = p_pos
                    if parent_key not in all_parent_positions:
                        all_parent_positions[parent_key] = []
                    all_parent_positions[parent_key].append(p_pos)
            cur_parent_type = None
            cur_parent_name = None
            cur_parent_start = None
            cur_parent_inner_start = None
            cur_parent_self_closing = False
        elif name == 'string':
            if open_strings:
                pos = open_strings.pop()
                pos['end_idx'] = idx
        elif name == 'item' and cur_parent_type:
            if open_items:
                pos = open_items.pop()
                pos['end_idx'] = idx
                p_type = pos['parent_type']
                p_name = pos['parent_name']
                if p_type == 'plurals' and p_name and pos['quantity']:
                    key = f"{p_name}#plural#{pos['quantity']}"
                    pos['key'] = key
                    item_positions[key] = pos
                    if key not in all_item_positions:
                        all_item_positions[key] = []
                    all_item_positions[key].append(pos)
                elif p_type == 'string-array' and p_name:
                    key = f"{p_name}#array#{pos['index']}"
                    pos['key'] = key
                    item_positions[key] = pos
                    if key not in all_item_positions:
                        all_item_positions[key] = []
                    all_item_positions[key].append(pos)
                    cur_item_index += 1
            cur_item_start = None
            cur_item_quantity = None
            cur_item_self_closing = False

    parser.StartElementHandler = start_element
    parser.EndElementHandler = end_element

    try:
        parser.Parse(content)
    except Exception:
        pass

    if include_all:
        return item_positions, parent_positions, all_item_positions, all_parent_positions
    return item_positions, parent_positions

def find_duplicate_keys(file_path: str = None, content: str = None) -> dict[str, list[dict]]:
    """
    Finds duplicate resource keys in an Android strings XML file or XML string content.
    Returns a dictionary mapping:
        key -> list of occurrence dicts:
            [
                {
                    "line": int,            # 1-indexed line number where the tag starts
                    "tag": str,             # 'string', 'plurals', 'string-array', or 'item'
                    "value": str,           # Unescaped string value
                    "attrib": dict,         # Attributes
                    "start_idx": int,
                    "end_idx": int,
                    "is_self_closing": bool
                },
                ...
            ]
    Only keys that appear more than once are included.
    """
    if content is None:
        if not file_path or not os.path.exists(file_path):
            return {}
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                content = f.read()
        except Exception:
            return {}

    if not content.strip():
        return {}

    item_pos, parent_pos, all_item_pos, all_parent_pos = parse_xml_positions(content, include_all=True)

    duplicates = {}

    for key, occs in all_item_pos.items():
        if len(occs) > 1:
            occ_list = []
            for occ in occs:
                start_tag_idx = occ['start_idx']
                tag_end_idx = find_tag_end(content, start_tag_idx)
                
                is_self = occ.get('is_self_closing', False)
                if is_self or tag_end_idx == -1 or 'end_idx' not in occ:
                    raw_val = ""
                else:
                    end_idx = occ['end_idx']
                    raw_val = content[tag_end_idx + 1:end_idx] if end_idx > tag_end_idx else ""
                
                val = unescape_android_string(raw_val)
                occ_list.append({
                    "line": occ.get('line', 1),
                    "tag": "item" if ('#' in key) else "string",
                    "value": val,
                    "attrib": occ.get('attrib', {}),
                    "start_idx": occ['start_idx'],
                    "end_idx": occ.get('end_idx', tag_end_idx + 1 if tag_end_idx != -1 else occ['start_idx']),
                    "is_self_closing": is_self
                })
            duplicates[key] = occ_list

    # Also check duplicate parent containers (<plurals> or <string-array>)
    for key, occs in all_parent_pos.items():
        if len(occs) > 1:
            base_key = key.split('#')[0]
            if base_key not in duplicates:
                occ_list = []
                for occ in occs:
                    occ_list.append({
                        "line": occ.get('line', 1),
                        "tag": occ.get('type', 'plurals'),
                        "value": f"<{occ.get('type')}> container",
                        "attrib": occ.get('attrib', {}),
                        "start_idx": occ['start_idx'],
                        "end_idx": occ.get('end_idx', occ['start_idx']),
                        "is_self_closing": occ.get('is_self_closing', False)
                    })
                duplicates[base_key] = occ_list

    return duplicates

def deduplicate_strings(target_path: str, keys: list[str] | set[str] | None = None, keep: str = 'last') -> int:
    """
    Removes duplicate string entries in target_path.
    If keys is provided, only duplicates of those specific keys are removed; otherwise all duplicates are removed.
    keep: 'last' (default) preserves the last occurrence and deletes earlier duplicates.
          'first' preserves the first occurrence and deletes subsequent duplicates.
    Returns the total number of duplicate entries removed.
    """
    if not os.path.exists(target_path):
        return 0

    with open(target_path, 'r', encoding='utf-8') as f:
        content = f.read()

    dups = find_duplicate_keys(content=content)
    if not dups:
        return 0

    if keys is not None:
        target_keys = set(keys)
        dups = {k: v for k, v in dups.items() if k in target_keys}

    if not dups:
        return 0

    slices = []
    affected_parents = set()

    for key, occs in dups.items():
        if len(occs) <= 1:
            continue
        
        # Decide which occurrences to delete
        if keep == 'first':
            to_delete = occs[1:]
        else: # default 'last'
            to_delete = occs[:-1]

        for occ in to_delete:
            del_start = occ['start_idx']
            is_self = occ.get('is_self_closing', False)
            tag_type = occ.get('tag', 'string')

            if is_self:
                tag_end = find_tag_end(content, del_start)
                del_end = tag_end + 1 if tag_end != -1 else occ['end_idx']
            else:
                if tag_type == 'item':
                    end_tag = "</item>"
                elif tag_type == 'plurals':
                    end_tag = "</plurals>"
                elif tag_type == 'string-array':
                    end_tag = "</string-array>"
                else:
                    end_tag = "</string>"
                del_end = occ['end_idx'] + len(end_tag)

            # Grab leading whitespace (indentation)
            while del_start > 0 and content[del_start - 1] in (' ', '\t'):
                del_start -= 1

            # Grab trailing newline
            if del_end < len(content) and content[del_end] == '\n':
                del_end += 1
            elif del_end < len(content) and content[del_end] == '\r':
                del_end += 1
                if del_end < len(content) and content[del_end] == '\n':
                    del_end += 1

            slices.append((del_start, del_end))
            if '#plural#' in key or '#array#' in key:
                parts = key.split('#')
                affected_parents.add((parts[0], 'plural' if '#plural#' in key else 'array'))

    if not slices:
        return 0

    # Sort descending by start index to delete from back to front without shifting offsets
    slices.sort(key=lambda s: s[0], reverse=True)
    new_content = content
    for d_start, d_end in slices:
        new_content = new_content[:d_start] + new_content[d_end:]

    # Clean up any empty parent containers if plurals or arrays were modified
    if affected_parents:
        new_item_pos, new_parent_pos = parse_xml_positions(new_content)
        parent_slices = []
        for base_key, container_type in affected_parents:
            parent_key = f"{base_key}#{container_type}"
            has_remaining = any(k.startswith(f"{base_key}#{container_type}#") for k in new_item_pos.keys())
            if not has_remaining and parent_key in new_parent_pos:
                p_pos = new_parent_pos[parent_key]
                p_del_start = p_pos['start_idx']
                p_self_closing = p_pos.get('is_self_closing', False)
                if p_self_closing:
                    p_del_end = p_pos['end_idx']
                else:
                    p_end_tag = "</plurals>" if container_type == 'plural' else "</string-array>"
                    p_del_end = p_pos['end_idx'] + len(p_end_tag)

                while p_del_start > 0 and new_content[p_del_start - 1] in (' ', '\t'):
                    p_del_start -= 1
                if p_del_end < len(new_content) and new_content[p_del_end] == '\n':
                    p_del_end += 1
                elif p_del_end < len(new_content) and new_content[p_del_end] == '\r':
                    p_del_end += 1
                    if p_del_end < len(new_content) and new_content[p_del_end] == '\n':
                        p_del_end += 1
                parent_slices.append((p_del_start, p_del_end))

        parent_slices.sort(key=lambda s: s[0], reverse=True)
        for p_start, p_end in parent_slices:
            new_content = new_content[:p_start] + new_content[p_end:]

    with open(target_path, 'w', encoding='utf-8') as f:
        f.write(new_content)

    return len(slices)

def write_string_translation(target_path: str, key: str, value: str, attrib: dict = None) -> bool:
    """
    Writes or updates a translation for a specific key in target_path.
    If the key exists, its value is replaced in place, preserving comments and formatting.
    If the key has multiple duplicate occurrences in target_path, duplicate occurrences are cleaned up.
    If it doesn't exist, it is appended to the parent container or directly to the bottom.
    If the file does not exist, a new one is initialized.
    """
    escaped_value = escape_android_string(value)

    # 1. Initialize file if it doesn't exist
    if not os.path.exists(target_path):
        os.makedirs(os.path.dirname(target_path), exist_ok=True)
        with open(target_path, 'w', encoding='utf-8') as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n')

    with open(target_path, 'r', encoding='utf-8') as f:
        content = f.read()

    # If the file is empty, contains only whitespace, or doesn't have a valid resources closing tag, reinitialize content
    if not content.strip() or '</resources>' not in content:
        content = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'

    # If key has duplicates, deduplicate it first (keep last) so duplicates do not persist
    dups = find_duplicate_keys(content=content)
    if key in dups:
        deduplicate_strings(target_path, keys=[key], keep='last')
        with open(target_path, 'r', encoding='utf-8') as f:
            content = f.read()

    # 2. Check if the key exists using a line/column-accurate parser
    item_positions, parent_positions = parse_xml_positions(content)
    
    is_plural = '#plural#' in key
    is_array = '#array#' in key

    # 3. Perform modification
    if key in item_positions:
        pos = item_positions[key]
        start_tag_idx = pos['start_idx']
        tag_end_idx = find_tag_end(content, start_tag_idx)
        if tag_end_idx != -1:
            start_tag = content[start_tag_idx:tag_end_idx + 1]
            # Clean up corrupted namespace artifacts or bogus ignore attributes from existing target start tags
            clean_tag = re.sub(r'\s+\{[^}]+\}[a-zA-Z0-9_:-]+="[^"]*"', '', start_tag)
            clean_tag = re.sub(r'\s+(?:tools:)?ignore="[^"]*"', '', clean_tag)
            
            is_self_closing = pos.get('is_self_closing', False)
            if is_self_closing:
                clean_tag = re.sub(r'\s*/\s*>$', '>', clean_tag)
                close_tag = "</item>" if (is_plural or is_array) else "</string>"
                new_content = content[:start_tag_idx] + clean_tag + escaped_value + close_tag + content[tag_end_idx + 1:]
            else:
                value_end = pos['end_idx']
                new_content = content[:start_tag_idx] + clean_tag + escaped_value + content[value_end:]
            with open(target_path, 'w', encoding='utf-8') as f:
                f.write(new_content)
            return True
    elif is_plural or is_array:
        # Check if parent container exists
        parts = key.split('#')
        base_key = parts[0]
        container_type = 'plural' if is_plural else 'array'
        parent_key = f"{base_key}#{container_type}"
        
        if parent_key in parent_positions:
            parent_pos = parent_positions[parent_key]
            insert_idx = parent_pos['inner_end']
            
            # Detect indentation
            indent = "        "
            parent_start_idx = parent_pos['start_idx']
            slice_before = content[:parent_start_idx]
            last_line = slice_before.splitlines()[-1] if slice_before.splitlines() else ""
            if last_line.isspace():
                indent = last_line + "    "
            
            # Backtrack to place the new item on its own line
            while insert_idx > parent_pos['inner_start'] and content[insert_idx - 1] in (' ', '\t'):
                insert_idx -= 1
                
            if is_plural:
                quantity = parts[2]
                new_item = f"{indent}<item quantity=\"{quantity}\">{escaped_value}</item>\n"
            else:
                new_item = f"{indent}<item>{escaped_value}</item>\n"
                
            new_content = content[:insert_idx] + new_item + content[insert_idx:]
            with open(target_path, 'w', encoding='utf-8') as f:
                f.write(new_content)
            return True
        else:
            # Parent container does not exist. Create parent and the item.
            r_resources = content.rfind('</resources>')
            if r_resources != -1:
                indent = "    "
                slice_before = content[:r_resources]
                last_line = slice_before.splitlines()[-1] if slice_before.splitlines() else ""
                if last_line.isspace():
                    indent = last_line
                
                # Build attributes string if any are defined
                clean_attrib = sanitize_target_attributes(attrib)
                attrib_str = "".join(f' {k}="{v}"' for k, v in clean_attrib.items())
                
                if is_plural:
                    quantity = parts[2]
                    new_element = (
                        f"{indent}<plurals name=\"{base_key}\"{attrib_str}>\n"
                        f"{indent}    <item quantity=\"{quantity}\">{escaped_value}</item>\n"
                        f"{indent}</plurals>\n"
                    )
                else:
                    index = int(parts[2])
                    items_str = ""
                    for _ in range(index):
                        items_str += f"{indent}    <item></item>\n"
                    items_str += f"{indent}    <item>{escaped_value}</item>\n"
                    new_element = (
                        f"{indent}<string-array name=\"{base_key}\"{attrib_str}>\n"
                        f"{items_str}"
                        f"{indent}</string-array>\n"
                    )
                
                new_content = content[:r_resources] + new_element + content[r_resources:]
                with open(target_path, 'w', encoding='utf-8') as f:
                    f.write(new_content)
                return True
    else:
        # Standard string and doesn't exist. Append it.
        r_resources = content.rfind('</resources>')
        if r_resources != -1:
            indent = "    "
            slice_before = content[:r_resources]
            last_line = slice_before.splitlines()[-1] if slice_before.splitlines() else ""
            if last_line.isspace():
                indent = last_line
            
            clean_attrib = sanitize_target_attributes(attrib)
            attrib_str = "".join(f' {k}="{v}"' for k, v in clean_attrib.items())
            
            new_element = f'{indent}<string name="{key}"{attrib_str}>{escaped_value}</string>\n'
            new_content = content[:r_resources] + new_element + content[r_resources:]
            with open(target_path, 'w', encoding='utf-8') as f:
                f.write(new_content)
            return True

    return False

def remove_string_translation(target_path: str, key: str) -> bool:
    """
    Removes a translation for a specific key in target_path if it exists.
    Preserves comments and formatting of all other tags.
    """
    return remove_string_translations(target_path, [key])

def remove_string_translations(target_path: str, keys: list[str] | set[str]) -> bool:
    """
    Removes translations for multiple keys in target_path in a single pass.
    Deletes all duplicate occurrences of any specified key.
    Preserves comments and formatting of all other tags.
    """
    if not os.path.exists(target_path) or not keys:
        return False

    with open(target_path, 'r', encoding='utf-8') as f:
        content = f.read()

    item_positions, parent_positions, all_item_positions, all_parent_positions = parse_xml_positions(content, include_all=True)

    keys_to_del = [k for k in keys if k in all_item_positions or k in item_positions]
    if not keys_to_del:
        return False

    slices = []
    affected_parents = set()

    for key in keys_to_del:
        positions_for_key = all_item_positions.get(key, [item_positions[key]] if key in item_positions else [])
        for pos in positions_for_key:
            del_start = pos['start_idx']

            is_plural = '#plural#' in key
            is_array = '#array#' in key
            is_self_closing = pos.get('is_self_closing', False)
            if is_self_closing:
                del_end = pos['end_idx']
            else:
                end_tag = "</item>" if (is_plural or is_array) else "</string>"
                del_end = pos['end_idx'] + len(end_tag)

            # Grab leading whitespace (indentation)
            while del_start > 0 and content[del_start - 1] in (' ', '\t'):
                del_start -= 1

            # Grab trailing newline
            if del_end < len(content) and content[del_end] == '\n':
                del_end += 1
            elif del_end < len(content) and content[del_end] == '\r':
                del_end += 1
                if del_end < len(content) and content[del_end] == '\n':
                    del_end += 1

            slices.append((del_start, del_end))
            if is_plural or is_array:
                parts = key.split('#')
                container_type = 'plural' if is_plural else 'array'
                affected_parents.add((parts[0], container_type))

    # Sort descending by start index to delete from back to front without shifting offsets
    slices.sort(key=lambda s: s[0], reverse=True)
    new_content = content
    for d_start, d_end in slices:
        new_content = new_content[:d_start] + new_content[d_end:]

    # Clean up empty parent containers if any plural or array items were removed
    if affected_parents:
        new_item_pos, new_parent_pos = parse_xml_positions(new_content)
        parent_slices = []
        for base_key, container_type in affected_parents:
            parent_key = f"{base_key}#{container_type}"
            has_remaining = any(k.startswith(f"{base_key}#{container_type}#") for k in new_item_pos.keys())
            if not has_remaining and parent_key in new_parent_pos:
                p_pos = new_parent_pos[parent_key]
                p_del_start = p_pos['start_idx']
                p_self_closing = p_pos.get('is_self_closing', False)
                if p_self_closing:
                    p_del_end = p_pos['end_idx']
                else:
                    p_end_tag = "</plurals>" if container_type == 'plural' else "</string-array>"
                    p_del_end = p_pos['end_idx'] + len(p_end_tag)

                while p_del_start > 0 and new_content[p_del_start - 1] in (' ', '\t'):
                    p_del_start -= 1
                if p_del_end < len(new_content) and new_content[p_del_end] == '\n':
                    p_del_end += 1
                elif p_del_end < len(new_content) and new_content[p_del_end] == '\r':
                    p_del_end += 1
                    if p_del_end < len(new_content) and new_content[p_del_end] == '\n':
                        p_del_end += 1
                parent_slices.append((p_del_start, p_del_end))

        parent_slices.sort(key=lambda s: s[0], reverse=True)
        for p_start, p_end in parent_slices:
            new_content = new_content[:p_start] + new_content[p_end:]

    with open(target_path, 'w', encoding='utf-8') as f:
        f.write(new_content)
    return True

