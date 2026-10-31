import hashlib
import json
import unittest
from pathlib import Path
from zipfile import ZipFile

import jsonschema

ROOT = Path(__file__).resolve().parents[1]
DEMO_DIR = ROOT / "app/src/main/assets/addons/demo"


class AddonArtifactTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest_schema = json.loads((ROOT / "addon-manifest.schema.json").read_text())
        cls.repository_schema = json.loads((ROOT / "repository-index.schema.json").read_text())
        cls.manifest = json.loads((DEMO_DIR / "manifest.json").read_text())
        cls.repository = json.loads((ROOT / "examples/ruivo-repository.example.json").read_text())

    def test_demo_manifest_matches_schema(self):
        jsonschema.validate(self.manifest, self.manifest_schema)
        self.assertEqual(self.manifest["catalog"]["path"], "package:catalog.json")

    def test_manifest_rejects_missing_required_api_version(self):
        invalid = json.loads(json.dumps(self.manifest))
        invalid.pop("apiVersion")
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate(invalid, self.manifest_schema)

    def test_manifest_rejects_http_and_unmapped_package_path(self):
        invalid = json.loads(json.dumps(self.manifest))
        invalid["catalog"]["path"] = "http://example.org/catalog.json"
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate(invalid, self.manifest_schema)

    def test_manifest_icon_requires_images_permission(self):
        invalid = json.loads(json.dumps(self.manifest))
        invalid["iconUrl"] = "https://example.org/icon.png"
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate(invalid, self.manifest_schema)

    def test_repository_example_matches_schema(self):
        jsonschema.validate(self.repository, self.repository_schema)

    def test_repository_rejects_package_without_sha256(self):
        invalid = json.loads(json.dumps(self.repository))
        invalid["items"][0].pop("sha256")
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate(invalid, self.repository_schema)

    def test_repository_rejects_manifest_and_package_at_once(self):
        invalid = json.loads(json.dumps(self.repository))
        invalid["items"][0]["manifestUrl"] = "https://example.org/addon.json"
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate(invalid, self.repository_schema)

    def test_demo_zip_is_small_allowlisted_and_hash_matches(self):
        archive = DEMO_DIR / "demo-addon.zip"
        with ZipFile(archive) as zf:
            names = zf.namelist()
            self.assertEqual(set(names), {"manifest.json", "catalog.json", "README.md"})
            self.assertEqual(len(names), len(set(names)))
            for name in names:
                self.assertNotIn("/", name)
                self.assertNotIn("\\", name)
                self.assertNotIn("..", name)
            self.assertLess(archive.stat().st_size, 5 * 1024 * 1024)
            self.assertLess(sum(info.file_size for info in zf.infolist()), 768 * 1024)
        actual = hashlib.sha256(archive.read_bytes()).hexdigest()
        self.assertEqual(actual, self.repository["items"][0]["sha256"])

    def test_demo_catalog_is_explicitly_fictional_and_uses_example_domain(self):
        catalog = json.loads((DEMO_DIR / "catalog.json").read_text())
        self.assertGreaterEqual(len(catalog["items"]), 3)
        for item in catalog["items"]:
            self.assertIn("DEMO", item["name"])
            self.assertTrue(item["url"].startswith("https://example.org/ruivotv-demo/"))


if __name__ == "__main__":
    unittest.main()
