import os
import shutil
import tempfile
import unittest
from droidlate.web import server

class TestServerEndpoints(unittest.TestCase):

    def setUp(self):
        self.test_dir = tempfile.mkdtemp()
        os.makedirs(os.path.join(self.test_dir, "values"), exist_ok=True)
        os.makedirs(os.path.join(self.test_dir, "values-es"), exist_ok=True)

        self.src_xml = os.path.join(self.test_dir, "values", "strings.xml")
        with open(self.src_xml, "w", encoding="utf-8") as f:
            f.write("""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="title">My App</string>
    <plurals name="items">
        <item quantity="one">%d item</item>
        <item quantity="other">%d items</item>
    </plurals>
</resources>
""")

        self.tgt_xml = os.path.join(self.test_dir, "values-es", "strings.xml")
        with open(self.tgt_xml, "w", encoding="utf-8") as f:
            f.write("""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="title">Mi App</string>
</resources>
""")

        server.RES_DIR = self.test_dir
        server.SOURCE_XML = None
        server.TARGET_XML = None
        server.IS_SINGLE_FILE_MODE = False
        self.client = server.app.test_client()

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_api_project(self):
        resp = self.client.get("/api/project")
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertEqual(data["mode"], "directory")
        self.assertEqual(len(data["languages"]), 1)
        self.assertEqual(data["languages"][0]["folder"], "values-es")

    def test_api_strings(self):
        resp = self.client.get("/api/strings?lang=values-es")
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertIn("strings", data)
        keys = [s["key"] for s in data["strings"]]
        self.assertIn("title", keys)
        self.assertIn("items#plural#one", keys)

    def test_api_translate_and_prune(self):
        # Save translation
        resp = self.client.post("/api/translate", json={
            "lang": "values-es",
            "key": "items#plural#other",
            "value": "%d elementos"
        })
        self.assertEqual(resp.status_code, 200)
        self.assertTrue(resp.get_json()["success"])

        # Save non-English quantity (e.g. few)
        resp_few = self.client.post("/api/translate", json={
            "lang": "values-es",
            "key": "items#plural#few",
            "value": "%d elementos pocos"
        })
        self.assertEqual(resp_few.status_code, 200)

        # Empty translation (removal)
        resp_del = self.client.post("/api/translate", json={
            "lang": "values-es",
            "key": "title",
            "value": ""
        })
        self.assertEqual(resp_del.status_code, 200)

    def test_suppress_warning(self):
        # Save translation with missing placeholder
        self.client.post("/api/translate", json={
            "lang": "values-es",
            "key": "items#plural#other",
            "value": "articulos en general"
        })
        
        # Verify status is warnings initially
        resp = self.client.get("/api/strings?lang=values-es")
        strings = {s["key"]: s for s in resp.json["strings"]}
        self.assertEqual(strings["items#plural#other"]["status"], "warnings")
        self.assertFalse(strings["items#plural#other"]["ignore_warnings"])

        # Ignore the warning
        ignore_resp = self.client.post("/api/warnings/ignore", json={
            "lang": "values-es",
            "key": "items#plural#other",
            "ignore": True
        })
        self.assertEqual(ignore_resp.status_code, 200)
        self.assertTrue(ignore_resp.json["ignore_warnings"])

        # Verify status resolves to translated
        resp2 = self.client.get("/api/strings?lang=values-es")
        strings2 = {s["key"]: s for s in resp2.json["strings"]}
        self.assertEqual(strings2["items#plural#other"]["status"], "translated")
        self.assertTrue(strings2["items#plural#other"]["ignore_warnings"])

    def test_static_files(self):
        with self.client.get("/") as resp_index:
            self.assertEqual(resp_index.status_code, 200)
            self.assertIn(b"plural-modal", resp_index.data)

        with self.client.get("/app.js") as resp_js:
            self.assertEqual(resp_js.status_code, 200)
            self.assertIn(b"openPluralEditor", resp_js.data)

        with self.client.get("/style.css") as resp_css:
            self.assertEqual(resp_css.status_code, 200)
            self.assertIn(b"plural-ref-card", resp_css.data)

    def test_duplicates_api(self):
        # Inject duplicates into target strings.xml
        dup_tgt_xml = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="title">Mi App 1</string>
    <string name="title">Mi App 2</string>
    <string name="other">Otro</string>
    <string name="other">Otro 2</string>
</resources>
"""
        with open(self.tgt_xml, "w", encoding="utf-8") as f:
            f.write(dup_tgt_xml)

        # Test /api/project reports duplicates
        resp_proj = self.client.get("/api/project")
        self.assertEqual(resp_proj.status_code, 200)
        proj_data = resp_proj.get_json()
        lang_card = proj_data["languages"][0]
        self.assertEqual(lang_card["duplicates"], 2)

        # Test /api/strings reports duplicate details
        resp_str = self.client.get("/api/strings?lang=values-es")
        self.assertEqual(resp_str.status_code, 200)
        strings_dict = {s["key"]: s for s in resp_str.get_json()["strings"]}
        self.assertTrue(strings_dict["title"]["is_duplicate"])
        self.assertEqual(strings_dict["title"]["duplicate_count"], 2)
        self.assertEqual(len(strings_dict["title"]["duplicate_occurrences"]), 2)

        # Test deduplicate single key
        resp_dedup_single = self.client.post("/api/deduplicate", json={
            "lang": "values-es",
            "key": "title",
            "keep": "last"
        })
        self.assertEqual(resp_dedup_single.status_code, 200)
        self.assertEqual(resp_dedup_single.get_json()["removed_count"], 1)

        # Verify title is deduplicated but other is still duplicated
        with open(self.tgt_xml, "r", encoding="utf-8") as f:
            content = f.read()
        self.assertEqual(content.count('<string name="title">'), 1)
        self.assertEqual(content.count('<string name="other">'), 2)
        self.assertIn('<string name="title">Mi App 2</string>', content)

        # Test deduplicate all keys in file
        resp_dedup_all = self.client.post("/api/deduplicate", json={
            "lang": "values-es",
            "keep": "last"
        })
        self.assertEqual(resp_dedup_all.status_code, 200)
        self.assertEqual(resp_dedup_all.get_json()["removed_count"], 1)

        with open(self.tgt_xml, "r", encoding="utf-8") as f:
            final_content = f.read()
        self.assertEqual(final_content.count('<string name="other">'), 1)

if __name__ == "__main__":
    unittest.main()
