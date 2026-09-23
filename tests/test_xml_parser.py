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

if __name__ == "__main__":
    unittest.main()
