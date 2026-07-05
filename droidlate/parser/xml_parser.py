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
    - Escapes XML entities: & -> &amp;, < -> &lt;, > -> &gt;
    - Escapes Android characters: ' -> \\', " -> \\"
    - Escapes control characters: newlines -> \\n, tabs -> \\t
    - Escapes leading @ and ? -> \\@ and \\?
    """
    if not val:
        return ""

    # 1. Escape XML characters
    escaped = val.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')

    # 2. Escape quotes and control characters
    result = []
    for idx, char in enumerate(escaped):
        if char == "'":
            result.append("\\'")
        elif char == '"':
            result.append('\\"')
        elif char == '\n':
            result.append('\\n')
        elif char == '\t':
            result.append('\\t')
        elif char == '\\':
            result.append('\\\\')
        elif (char == '@' or char == '?') and idx == 0:
            result.append('\\' + char)
        else:
            result.append(char)
            
    return "".join(result)

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
                attrib = {k: v for k, v in child.attrib.items() if k != 'name'}
                
                entries[key] = StringEntry(key, value, comment, attrib)
            current_comments = []
        elif child.tag == 'plurals':
            key = child.attrib.get('name')
            if key:
                comment = "\n".join(current_comments)
                attrib = {k: v for k, v in child.attrib.items() if k != 'name'}
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
                attrib = {k: v for k, v in child.attrib.items() if k != 'name'}
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

def parse_xml_positions(content: str):
    """
    Parses XML content and returns:
    1. A dictionary of element positions: key -> { 'start_idx': int, 'end_idx': int }
       Keys can be standard (e.g. 'my_key'), plurals (e.g. 'my_plural#plural#one'), or arrays (e.g. 'my_array#array#0').
    2. A dictionary of parent container positions: container_key -> { 'type': str, 'start_idx': int, 'end_idx': int, 'inner_start': int, 'inner_end': int }
       Container keys are e.g. 'my_plural#plural' or 'my_array#array'.
    """
    lines = content.splitlines(keepends=True)
    
    def get_index(line_num, col_num):
        idx = 0
        for i in range(line_num - 1):
            if i < len(lines):
                idx += len(lines[i])
        idx += col_num
        return idx

    item_positions = {}
    parent_positions = {}

    parser = xml.parsers.expat.ParserCreate()

    cur_parent_type = None  # 'plurals' or 'string-array'
    cur_parent_name = None
    cur_parent_start = None
    cur_parent_inner_start = None
    cur_item_index = 0
    cur_item_quantity = None
    cur_item_start = None

    def start_element(name, attrs):
        nonlocal cur_parent_type, cur_parent_name, cur_parent_start, cur_parent_inner_start
        nonlocal cur_item_index, cur_item_quantity, cur_item_start
        
        idx = get_index(parser.CurrentLineNumber, parser.CurrentColumnNumber)
        
        if name in ('plurals', 'string-array'):
            cur_parent_type = name
            cur_parent_name = attrs.get('name')
            cur_parent_start = idx
            tag_end = content.find('>', idx)
            cur_parent_inner_start = tag_end + 1 if tag_end != -1 else idx
            cur_item_index = 0
        elif name == 'string':
            string_name = attrs.get('name')
            if string_name:
                item_positions[string_name] = {
                    'start_idx': idx
                }
        elif name == 'item' and cur_parent_type:
            cur_item_start = idx
            if cur_parent_type == 'plurals':
                cur_item_quantity = attrs.get('quantity')
            else:
                cur_item_quantity = None

    def end_element(name):
        nonlocal cur_parent_type, cur_parent_name, cur_parent_start, cur_parent_inner_start
        nonlocal cur_item_index, cur_item_quantity, cur_item_start
        
        idx = get_index(parser.CurrentLineNumber, parser.CurrentColumnNumber)
        
        if name in ('plurals', 'string-array'):
            if cur_parent_name:
                parent_key = f"{cur_parent_name}#{'plural' if cur_parent_type == 'plurals' else 'array'}"
                parent_positions[parent_key] = {
                    'type': cur_parent_type,
                    'start_idx': cur_parent_start,
                    'end_idx': idx,
                    'inner_start': cur_parent_inner_start,
                    'inner_end': idx
                }
            cur_parent_type = None
            cur_parent_name = None
            cur_parent_start = None
            cur_parent_inner_start = None
        elif name == 'string':
            for k, pos in reversed(list(item_positions.items())):
                if '#' not in k and 'end_idx' not in pos:
                    pos['end_idx'] = idx
                    break
        elif name == 'item' and cur_parent_type:
            if cur_parent_type == 'plurals':
                if cur_parent_name and cur_item_quantity:
                    key = f"{cur_parent_name}#plural#{cur_item_quantity}"
                    item_positions[key] = {
                        'start_idx': cur_item_start,
                        'end_idx': idx
                    }
            elif cur_parent_type == 'string-array':
                if cur_parent_name:
                    key = f"{cur_parent_name}#array#{cur_item_index}"
                    item_positions[key] = {
                        'start_idx': cur_item_start,
                        'end_idx': idx
                    }
                    cur_item_index += 1
            cur_item_start = None
            cur_item_quantity = None

    parser.StartElementHandler = start_element
    parser.EndElementHandler = end_element

    try:
        parser.Parse(content)
    except Exception:
        pass

    return item_positions, parent_positions

def write_string_translation(target_path: str, key: str, value: str, attrib: dict = None) -> bool:
    """
    Writes or updates a translation for a specific key in target_path.
    If the key exists, its value is replaced in place, preserving comments and formatting.
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

    # 2. Check if the key exists using a line/column-accurate parser
    item_positions, parent_positions = parse_xml_positions(content)
    
    is_plural = '#plural#' in key
    is_array = '#array#' in key

    # 3. Perform modification
    if key in item_positions:
        pos = item_positions[key]
        start_tag_idx = pos['start_idx']
        tag_end_idx = content.find('>', start_tag_idx)
        if tag_end_idx != -1:
            value_start = tag_end_idx + 1
            value_end = pos['end_idx']
            
            # Replace the slice
            new_content = content[:value_start] + escaped_value + content[value_end:]
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
                attrib_str = ""
                if attrib:
                    for k, v in attrib.items():
                        if not k.startswith('__'):
                            attrib_str += f' {k}="{v}"'
                
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
            
            attrib_str = ""
            if attrib:
                for k, v in attrib.items():
                    if not k.startswith('__'):
                        attrib_str += f' {k}="{v}"'
            
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
    if not os.path.exists(target_path):
        return False

    with open(target_path, 'r', encoding='utf-8') as f:
        content = f.read()

    item_positions, parent_positions = parse_xml_positions(content)

    if key in item_positions:
        pos = item_positions[key]
        del_start = pos['start_idx']
        
        is_plural = '#plural#' in key
        is_array = '#array#' in key
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
                
        # Slice it out
        new_content = content[:del_start] + content[del_end:]
        
        # If it was a plural or array item, check if the parent container is now empty
        if is_plural or is_array:
            parts = key.split('#')
            base_key = parts[0]
            container_type = 'plural' if is_plural else 'array'
            parent_key = f"{base_key}#{container_type}"
            
            new_item_positions, new_parent_positions = parse_xml_positions(new_content)
            has_remaining_items = any(k.startswith(f"{base_key}#{container_type}#") for k in new_item_positions.keys())
            
            if not has_remaining_items and parent_key in new_parent_positions:
                parent_pos = new_parent_positions[parent_key]
                p_del_start = parent_pos['start_idx']
                p_end_tag = "</plurals>" if is_plural else "</string-array>"
                p_del_end = parent_pos['end_idx'] + len(p_end_tag)
                
                while p_del_start > 0 and new_content[p_del_start - 1] in (' ', '\t'):
                    p_del_start -= 1
                if p_del_end < len(new_content) and new_content[p_del_end] == '\n':
                    p_del_end += 1
                elif p_del_end < len(new_content) and new_content[p_del_end] == '\r':
                    p_del_end += 1
                    if p_del_end < len(new_content) and new_content[p_del_end] == '\n':
                        p_del_end += 1
                new_content = new_content[:p_del_start] + new_content[p_del_end:]
                
        with open(target_path, 'w', encoding='utf-8') as f:
            f.write(new_content)
        return True

    return False

