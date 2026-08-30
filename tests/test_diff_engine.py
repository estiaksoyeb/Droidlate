import unittest
from droidlate.parser.diff_engine import (
    validate_placeholders,
    normalize_source_string,
    compute_source_hash,
    categorize_key,
    is_key_orphaned
)

class TestDiffEngine(unittest.TestCase):

    def test_validate_placeholders_exact(self):
        warnings = validate_placeholders("Hello %s", "Hola %s")
        self.assertEqual(warnings, [])

    def test_validate_placeholders_missing(self):
        warnings = validate_placeholders("Hello %s", "Hola")
        self.assertTrue(any("Missing placeholder" in w for w in warnings))

    def test_validate_placeholders_extra(self):
        warnings = validate_placeholders("Hello", "Hola %s")
        self.assertTrue(any("Extra/unexpected placeholder" in w for w in warnings))

    def test_validate_placeholders_html_tags(self):
        warnings = validate_placeholders("<b>Hello</b>", "<b>Hola</b>")
        self.assertEqual(warnings, [])

        warnings_missing = validate_placeholders("<b>Hello</b>", "Hola")
        self.assertTrue(any("Missing HTML tag" in w for w in warnings_missing))

    def test_normalize_and_hash(self):
        h1 = compute_source_hash(normalize_source_string("  Hello   World \n"))
        h2 = compute_source_hash(normalize_source_string("Hello World"))
        self.assertEqual(h1, h2)

    def test_categorize_key(self):
        # Readonly
        self.assertEqual(categorize_key("key", "val", "val", None, {"translatable": "false"}), "readonly")
        # Untranslated
        self.assertEqual(categorize_key("key", "val", None, None, {}), "untranslated")
        # Translated
        src_hash = compute_source_hash(normalize_source_string("val"))
        meta = {"source_hash": src_hash}
        self.assertEqual(categorize_key("key", "val", "trad", meta, {}), "translated")
        # Outdated
        meta_old = {"source_hash": "old_hash_123"}
        self.assertEqual(categorize_key("key", "val", "trad", meta_old, {}), "outdated")

    def test_is_key_orphaned(self):
        sources = {"app_name": None, "song_count#plural#one": None}
        self.assertFalse(is_key_orphaned("app_name", sources))
        self.assertFalse(is_key_orphaned("song_count#plural#few", sources))
        self.assertTrue(is_key_orphaned("removed_key", sources))

if __name__ == "__main__":
    unittest.main()
