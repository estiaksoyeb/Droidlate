import os
import shutil
import tempfile
import unittest
from droidlate.parser.xml_parser import (
    escape_android_string,
    unescape_android_string,
    parse_strings_xml,
    write_string_translation,
    remove_string_translation
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

if __name__ == "__main__":
    unittest.main()
