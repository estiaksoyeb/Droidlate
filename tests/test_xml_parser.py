import os
import shutil
import tempfile
import unittest
from droidlate.parser.xml_parser import (
    escape_android_string,
    unescape_android_string,
    parse_strings_xml,
    write_string_translation,
    remove_string_translation,
    remove_string_translations,
    normalize_attrib_key,
    sanitize_target_attributes
)

class TestXmlParser(unittest.TestCase):

    def setUp(self):
        self.test_dir = tempfile.mkdtemp()
        self.xml_path = os.path.join(self.test_dir, "strings.xml")

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_escape_plain_text(self):
        self.assertEqual(escape_android_string("Hello World"), "Hello World")
        self.assertEqual(escape_android_string("Don't walk"), "Don\\'t walk")
        self.assertEqual(escape_android_string('He said "Hi"'), 'He said \\"Hi\\"')
        self.assertEqual(escape_android_string("Line1\nLine2"), "Line1\\nLine2")
        self.assertEqual(escape_android_string("@string/ref"), "\\@string/ref")
        self.assertEqual(escape_android_string("?attr/theme"), "\\?attr/theme")

    def test_escape_preserves_html_tags_and_entities(self):
        self.assertEqual(escape_android_string("<b>Hello</b> <i>World</i>"), "<b>Hello</b> <i>World</i>")
        self.assertEqual(escape_android_string("<b>Don't</b> touch"), "<b>Don\\'t</b> touch")
        self.assertEqual(escape_android_string("Price: %1$d < 100"), "Price: %1$d &lt; 100")
        self.assertEqual(escape_android_string("AT&T & More"), "AT&amp;T &amp; More")
        self.assertEqual(escape_android_string('<xliff:g id="name">%s</xliff:g>'), '<xliff:g id="name">%s</xliff:g>')

    def test_unescape_android_string(self):
        self.assertEqual(unescape_android_string("Don\\'t walk"), "Don't walk")
        self.assertEqual(unescape_android_string('He said \\"Hi\\"'), 'He said "Hi"')
        self.assertEqual(unescape_android_string("Line1\\nLine2"), "Line1\nLine2")
        self.assertEqual(unescape_android_string("AT&amp;T"), "AT&T")

    def test_parse_and_write_strings(self):
        xml_content = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- App title comment -->
    <string name="app_name">Droidlate</string>
    <plurals name="items">
        <item quantity="one">%d item</item>
        <item quantity="other">%d items</item>
    </plurals>
</resources>
"""
        with open(self.xml_path, "w", encoding="utf-8") as f:
            f.write(xml_content)

        entries = parse_strings_xml(self.xml_path)
        self.assertIn("app_name", entries)
        self.assertEqual(entries["app_name"].value, "Droidlate")
        self.assertEqual(entries["app_name"].comment, "App title comment")
        self.assertIn("items#plural#one", entries)

        # Write translation
        write_string_translation(self.xml_path, "app_name", "Droidlate Mod", {})
        entries_after = parse_strings_xml(self.xml_path)
        self.assertEqual(entries_after["app_name"].value, "Droidlate Mod")

        # Write new plural item
        write_string_translation(self.xml_path, "items#plural#few", "%d items few", {})
        entries_after_plural = parse_strings_xml(self.xml_path)
        self.assertIn("items#plural#few", entries_after_plural)

        # Remove string
        remove_string_translation(self.xml_path, "app_name")
        entries_final = parse_strings_xml(self.xml_path)
        self.assertNotIn("app_name", entries_final)

    def test_normalize_attrib_key(self):
        self.assertEqual(normalize_attrib_key('{http://schemas.android.com/tools}ignore'), 'tools:ignore')
        self.assertEqual(normalize_attrib_key('{http://schemas.android.com/apk/res/android}name'), 'android:name')
        self.assertEqual(normalize_attrib_key('{http://schemas.android.com/apk/res-auto}title'), 'app:title')
        self.assertEqual(normalize_attrib_key('{urn:oasis:names:tc:xliff:document:1.2}g'), 'xliff:g')
        self.assertEqual(normalize_attrib_key('formatted'), 'formatted')

    def test_sanitize_target_attributes(self):
        dirty = {
            'tools:ignore': 'MissingTranslation',
            '{http://schemas.android.com/tools}ignore': 'MissingTranslation',
            'ignore': 'MissingTranslation',
            'translatable': 'false',
            '__resource_type__': 'plurals',
            'formatted': 'false',
            'product': 'tablet'
        }
        clean = sanitize_target_attributes(dirty)
        self.assertEqual(clean, {'formatted': 'false', 'product': 'tablet'})

    def test_tools_ignore_not_written_to_target_xml(self):
        # Create base strings.xml with tools:ignore
        source_xml_content = """<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:tools="http://schemas.android.com/tools">
    <string name="ratio_key" tools:ignore="MissingTranslation">Default aspect ratio</string>
</resources>
"""
        src_path = os.path.join(self.test_dir, "source_strings.xml")
        with open(src_path, "w", encoding="utf-8") as f:
            f.write(source_xml_content)

        src_entries = parse_strings_xml(src_path)
        self.assertIn("ratio_key", src_entries)
        self.assertEqual(src_entries["ratio_key"].attrib.get("tools:ignore"), "MissingTranslation")

        # Write translation to a new target file
        target_path = os.path.join(self.test_dir, "target_strings.xml")
        write_string_translation(target_path, "ratio_key", "Translated ratio", src_entries["ratio_key"].attrib)

        with open(target_path, "r", encoding="utf-8") as f:
            content = f.read()

        # Target XML must NOT contain tools:ignore, ignore, or Clark notation
        self.assertNotIn("tools:ignore", content)
        self.assertNotIn("ignore=", content)
        self.assertNotIn("{http://schemas.android.com/tools}", content)
        self.assertIn('<string name="ratio_key">Translated ratio</string>', content)

    def test_update_cleans_corrupted_start_tag(self):
        corrupted_xml = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="ratio_key" ignore="MissingTranslation">Old Arabic</string>
</resources>
"""
        target_path = os.path.join(self.test_dir, "corrupted_strings.xml")
        with open(target_path, "w", encoding="utf-8") as f:
            f.write(corrupted_xml)

        write_string_translation(target_path, "ratio_key", "New Arabic", {})

        with open(target_path, "r", encoding="utf-8") as f:
            content = f.read()

        self.assertNotIn("ignore=", content)
        self.assertIn('<string name="ratio_key">New Arabic</string>', content)

    def test_literal_line_breaks_equality(self):
        multiline_xml = '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="multiline_literal">Line 1

Line 2
Line 3</string>
    <string name="multiline_escaped">Line 1\\n\\nLine 2\\nLine 3</string>
</resources>'''
        with open(self.xml_path, "w", encoding="utf-8") as f:
            f.write(multiline_xml)

        entries = parse_strings_xml(self.xml_path)
        self.assertIn("multiline_literal", entries)
        self.assertIn("multiline_escaped", entries)
        self.assertEqual(entries["multiline_literal"].value, "Line 1\n\nLine 2\nLine 3")
        self.assertEqual(entries["multiline_escaped"].value, "Line 1\n\nLine 2\nLine 3")
        self.assertEqual(entries["multiline_literal"].value, entries["multiline_escaped"].value)

    def test_multiline_write_and_replace(self):
        multiline_xml = '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="before">Before</string>
    <string name="multiline_literal">Line 1

Line 2
Line 3</string>
    <string name="after">After</string>
</resources>'''
        with open(self.xml_path, "w", encoding="utf-8") as f:
            f.write(multiline_xml)

        write_string_translation(self.xml_path, "multiline_literal", "New 1\nNew 2", {})
        entries = parse_strings_xml(self.xml_path)
        self.assertEqual(entries["multiline_literal"].value, "New 1\nNew 2")
        self.assertEqual(entries["before"].value, "Before")
        self.assertEqual(entries["after"].value, "After")

        with open(self.xml_path, "r", encoding="utf-8") as f:
            content = f.read()
        self.assertIn('<string name="multiline_literal">New 1\\nNew 2</string>', content)
        self.assertIn('<string name="before">Before</string>', content)
        self.assertIn('<string name="after">After</string>', content)

    def test_multiline_remove(self):
        multiline_xml = '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="before">Before</string>
    <string name="multiline_literal">Line 1

Line 2
Line 3</string>
    <string name="after">After</string>
</resources>'''
        with open(self.xml_path, "w", encoding="utf-8") as f:
            f.write(multiline_xml)

        remove_string_translation(self.xml_path, "multiline_literal")
        entries = parse_strings_xml(self.xml_path)
        self.assertNotIn("multiline_literal", entries)
        self.assertIn("before", entries)
        self.assertIn("after", entries)

        with open(self.xml_path, "r", encoding="utf-8") as f:
            content = f.read()
        self.assertNotIn("multiline_literal", content)
        self.assertNotIn("Line 1", content)
        self.assertIn('<string name="before">Before</string>', content)
        self.assertIn('<string name="after">After</string>', content)

    def test_self_closing_write_and_remove(self):
        xml = '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="empty" />
    <string name="other">Other</string>
</resources>'''
        with open(self.xml_path, "w", encoding="utf-8") as f:
            f.write(xml)

        write_string_translation(self.xml_path, "empty", "Now Filled", {})
        entries = parse_strings_xml(self.xml_path)
        self.assertEqual(entries["empty"].value, "Now Filled")
        self.assertEqual(entries["other"].value, "Other")

        with open(self.xml_path, "r", encoding="utf-8") as f:
            content = f.read()
        self.assertIn('<string name="empty">Now Filled</string>', content)

        # Reset and test remove self closing tag
        with open(self.xml_path, "w", encoding="utf-8") as f:
            f.write(xml)
        remove_string_translation(self.xml_path, "empty")
        entries_after = parse_strings_xml(self.xml_path)
        self.assertNotIn("empty", entries_after)
        self.assertIn("other", entries_after)

        with open(self.xml_path, "r", encoding="utf-8") as f:
            content_after = f.read()
        self.assertNotIn("empty", content_after)
        self.assertIn('<string name="other">Other</string>', content_after)

    def test_batch_remove_string_translations(self):
        xml = '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="s1">1</string>
    <string name="s2">2</string>
    <string name="s3">3</string>
    <plurals name="p1">
        <item quantity="one">one</item>
        <item quantity="other">other</item>
    </plurals>
</resources>'''
        with open(self.xml_path, "w", encoding="utf-8") as f:
            f.write(xml)

        remove_string_translations(self.xml_path, ["s1", "s3", "p1#plural#one"])
        entries = parse_strings_xml(self.xml_path)
        self.assertNotIn("s1", entries)
        self.assertIn("s2", entries)
        self.assertNotIn("s3", entries)
        self.assertNotIn("p1#plural#one", entries)
        self.assertIn("p1#plural#other", entries)

        # Batch remove remaining plural item -> should remove empty parent container
        remove_string_translations(self.xml_path, ["p1#plural#other"])
        entries2 = parse_strings_xml(self.xml_path)
        self.assertNotIn("p1#plural#other", entries2)
        with open(self.xml_path, "r", encoding="utf-8") as f:
            content = f.read()
        self.assertNotIn("<plurals", content)
        self.assertIn('<string name="s2">2</string>', content)

    def test_prune_nontranslatable_strings_batch(self):
        from droidlate.parser.diff_engine import prune_nontranslatable_strings, load_metadata, save_metadata

        src_xml = '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="translatable_key">Source 1</string>
    <string name="non_translatable_key" translatable="false">Source 2</string>
    <string name="another_non_trans" translatable="false">Source 3</string>
</resources>'''
        tgt_xml = '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="translatable_key">Target 1</string>
    <string name="non_translatable_key">Target 2</string>
    <string name="another_non_trans">Target 3</string>
</resources>'''
        src_path = os.path.join(self.test_dir, "src.xml")
        tgt_path = os.path.join(self.test_dir, "tgt.xml")
        with open(src_path, "w", encoding="utf-8") as f:
            f.write(src_xml)
        with open(tgt_path, "w", encoding="utf-8") as f:
            f.write(tgt_xml)

        src_entries = parse_strings_xml(src_path)
        tgt_entries = parse_strings_xml(tgt_path)

        metadata = {
            "translatable_key": {"source_hash": "abc", "translated_value": "Target 1"},
            "non_translatable_key": {"source_hash": "def", "translated_value": "Target 2"},
            "another_non_trans": {"source_hash": "ghi", "translated_value": "Target 3"}
        }
        save_metadata(tgt_path, metadata)

        changed = prune_nontranslatable_strings(tgt_path, src_entries, tgt_entries)
        self.assertTrue(changed)

        self.assertIn("translatable_key", tgt_entries)
        self.assertNotIn("non_translatable_key", tgt_entries)
        self.assertNotIn("another_non_trans", tgt_entries)

        tgt_entries_file = parse_strings_xml(tgt_path)
        self.assertIn("translatable_key", tgt_entries_file)
        self.assertNotIn("non_translatable_key", tgt_entries_file)
        self.assertNotIn("another_non_trans", tgt_entries_file)

        updated_meta = load_metadata(tgt_path)
        self.assertIn("translatable_key", updated_meta)
        self.assertNotIn("non_translatable_key", updated_meta)
        self.assertNotIn("another_non_trans", updated_meta)

if __name__ == "__main__":
    unittest.main()
