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
    Parses an Android strings.xml file, extracting all standard <string> elements.
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
        else:
            # Skip non-string elements (like string-array, plurals for v1)
            # but clear collected comments so they don't leak
            current_comments = []

    return entries

def write_string_translation(target_path: str, key: str, value: str, attrib: dict = None) -> bool:
    """
    Writes or updates a translation for a specific key in target_path.
    If the key exists, its value is replaced in place, preserving comments and formatting.
    If it doesn't exist, it is appended to the bottom before </resources>.
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
    lines = content.splitlines(keepends=True)
    
    def get_index(line_num, col_num):
        idx = 0
        for i in range(line_num - 1):
            if i < len(lines):
                idx += len(lines[i])
        idx += col_num
        return idx

    parser = xml.parsers.expat.ParserCreate()
    current_string = None
    string_positions = {}

    def start_element(name, attrs):
        nonlocal current_string
        if name == 'string':
            name_attr = attrs.get('name')
            if name_attr:
                current_string = name_attr
                string_positions[current_string] = {
                    'start_idx': get_index(parser.CurrentLineNumber, parser.CurrentColumnNumber)
                }

    def end_element(name):
        nonlocal current_string
        if name == 'string' and current_string:
            string_positions[current_string]['end_idx'] = get_index(parser.CurrentLineNumber, parser.CurrentColumnNumber)
            current_string = None

    parser.StartElementHandler = start_element
    parser.EndElementHandler = end_element

    try:
        parser.Parse(content)
        has_key = key in string_positions
    except Exception:
        has_key = False

    # 3. Perform modification
    if has_key:
        pos = string_positions[key]
        start_tag_idx = pos['start_idx']
        # Find end of start tag '>'
        tag_end_idx = content.find('>', start_tag_idx)
        if tag_end_idx != -1:
            value_start = tag_end_idx + 1
            value_end = pos['end_idx']
            
            # Replace the slice
            new_content = content[:value_start] + escaped_value + content[value_end:]
            with open(target_path, 'w', encoding='utf-8') as f:
                f.write(new_content)
            return True
    else:
        # Append before </resources>
        # Find </resources> from the end of the file
        r_resources = content.rfind('</resources>')
        if r_resources != -1:
            # Detect indentation from preceding elements if possible
            indent = "    " # default
            # Look at the line before </resources> to see if there is whitespace
            slice_before = content[:r_resources]
            last_line = slice_before.splitlines()[-1] if slice_before.splitlines() else ""
            if last_line.isspace():
                # The line before is just indentation for </resources>
                indent_match = re.match(r'^(\s+)', last_line)
                if indent_match:
                    indent = indent_match.group(1) + "    "
            
            # Build attributes string if any attributes are defined
            attrib_str = ""
            if attrib:
                for k, v in attrib.items():
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

    # Parse boundaries using Expat parser
    lines = content.splitlines(keepends=True)
    
    def get_index(line_num, col_num):
        idx = 0
        for i in range(line_num - 1):
            if i < len(lines):
                idx += len(lines[i])
        idx += col_num
        return idx

    parser = xml.parsers.expat.ParserCreate()
    current_string = None
    string_positions = {}

    def start_element(name, attrs):
        nonlocal current_string
        if name == 'string':
            name_attr = attrs.get('name')
            if name_attr:
                current_string = name_attr
                string_positions[current_string] = {
                    'start_idx': get_index(parser.CurrentLineNumber, parser.CurrentColumnNumber)
                }

    def end_element(name):
        nonlocal current_string
        if name == 'string' and current_string:
            string_positions[current_string]['end_idx'] = get_index(parser.CurrentLineNumber, parser.CurrentColumnNumber)
            current_string = None

    parser.StartElementHandler = start_element
    parser.EndElementHandler = end_element

    try:
        parser.Parse(content)
        has_key = key in string_positions
    except Exception:
        has_key = False

    if has_key:
        pos = string_positions[key]
        del_start = pos['start_idx']
        del_end = pos['end_idx'] + len("</string>")
        
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
        with open(target_path, 'w', encoding='utf-8') as f:
            f.write(new_content)
        return True

    return False

