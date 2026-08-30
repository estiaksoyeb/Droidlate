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

if __name__ == "__main__":
    unittest.main()
