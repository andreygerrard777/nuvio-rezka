import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from package_repo import package, ENTRY_POINT


def make_cs3(path: Path, manifest: dict):
    with zipfile.ZipFile(path, "w") as archive:
        # Synthetic ZIP tests packaging only; its DEX marker is not executable code.
        archive.writestr("classes.dex", b"dex\n035\0synthetic")
        archive.writestr("manifest.json", json.dumps(manifest))


class PackagingTests(unittest.TestCase):
    def test_links_hash_size_and_version_match_bundle(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            cs3 = root / "input.cs3"
            make_cs3(cs3, {"pluginClassName": ENTRY_POINT, "version": 3})
            out = root / "dist"
            with contextlib.redirect_stdout(io.StringIO()):
                package(cs3, "example/test", "builds", out)
            repo = json.loads((out / "repo.json").read_text(encoding="utf-8"))
            plugin = json.loads((out / "plugins.json").read_text(encoding="utf-8"))[0]
            self.assertEqual(repo["pluginLists"], ["https://github.com/example/test/releases/download/builds/plugins.json"])
            self.assertEqual(plugin["url"], "https://github.com/example/test/releases/download/builds/Rezka.cs3")
            self.assertEqual(plugin["version"], 3)
            self.assertEqual(plugin["internalName"], "Rezka")
            self.assertEqual(plugin["fileSize"], cs3.stat().st_size)
            self.assertTrue(plugin["fileHash"].startswith("sha256-"))
            self.assertEqual((out / "Rezka.cs3").read_bytes(), cs3.read_bytes())

    def test_rejects_malformed_repository_before_reading_binary(self):
        with self.assertRaises(ValueError):
            package(Path("missing.cs3"), "https://github.com/example/test", "v1", Path("unused"))

    def test_rejects_wrong_entrypoint_or_missing_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for manifest, message in [({"pluginClassName": "wrong.Plugin", "version": 1}, "entry point"),
                                      ({"pluginClassName": ENTRY_POINT}, "version")]:
                cs3 = root / "bad.cs3"
                make_cs3(cs3, manifest)
                with self.assertRaisesRegex(ValueError, message):
                    package(cs3, "example/test", "v1", root / "dist")
                self.assertFalse((root / "dist").exists())


if __name__ == "__main__":
    unittest.main()
