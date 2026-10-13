"""Offline tests only: fake byte streams, temporary directories, no live requests."""
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("fetch_official_mods", Path(__file__).with_name("fetch-official-mods.py"))
fetch = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fetch)
DATA = b"fake jar bytes for installer tests only"


def entry(ident="epicfight"):
    return {"id": ident, "version": "test", "file": "test.jar", "sha256": hashlib.sha256(DATA).hexdigest(),
            "bytes": len(DATA), "bundled": False, "role": "required",
            "official_page": "https://www.curseforge.com/minecraft/mc-mods/epic-fight-mod/files/8175609"}


def manifest(mods=None):
    return {"minecraft": "1.21.1", "neoforge": "21.1.219", "java": "21", "mods": [entry()] if mods is None else mods}


class FetchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def load(self, data):
        path = self.directory / "manifest.json"
        path.write_text(json.dumps(data))
        return fetch.read_manifest(path)

    def assert_clean(self):
        self.assertEqual([], list(self.directory.glob("*.jar")))
        self.assertEqual([], list(self.directory.glob("*.part")))

    def test_missing_required_dependency_rejected(self):
        worldgen = {**entry("terralith"), "role": "experimental_profile", "official_page": "https://modrinth.com/datapack/terralith/version/AbCd12"}
        with self.assertRaises(fetch.FetchError):
            self.load(manifest([worldgen]))

    def test_malformed_json_cli_fails_without_creating_directory(self):
        path = self.directory / "bad.json"
        path.write_text("{broken")
        absent = self.directory / "missing"
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, fetch.main(["--manifest", str(path), "--mods-dir", str(absent)]))
        self.assertFalse(absent.exists())

    def test_symlink_directory_rejected(self):
        path = self.directory / "manifest.json"
        path.write_text(json.dumps(manifest()))
        link = self.directory / "linked-mods"
        link.symlink_to(self.directory, target_is_directory=True)
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, fetch.main(["--manifest", str(path), "--mods-dir", str(link), "--verify-only"]))

    def test_real_manifest_valid(self):
        mods = fetch.read_manifest(Path(__file__).resolve().parent.parent / "DEPENDENCIES.json")
        self.assertTrue(any(m["id"] == "epicfight" for m in mods))

    def test_download_verified_and_no_parts(self):
        self.assertEqual("downloaded and verified", fetch.fetch_one(entry(), self.directory, opener=lambda _: io.BytesIO(DATA)))
        self.assertTrue(fetch.valid_file(self.directory / "test.jar", entry()))
        self.assertEqual([], list(self.directory.glob("*.part")))

    def test_wrong_hash_and_short_or_long_bytes_fail_closed(self):
        for data in [b"x" * len(DATA), DATA[:-1], DATA + b"extra", b"<html>blocked</html>"]:
            with self.subTest(data=data), self.assertRaises(fetch.FetchError):
                fetch.fetch_one(entry(), self.directory, opener=lambda _: io.BytesIO(data))
            self.assert_clean()

    def test_interrupted_download_removes_partial(self):
        class Interrupted(io.BytesIO):
            def read(self, _):
                if self.tell():
                    raise OSError("connection interrupted")
                return super().read(2)
        with self.assertRaises(OSError):
            fetch.fetch_one(entry(), self.directory, opener=lambda _: Interrupted(DATA))
        self.assert_clean()

    def test_valid_existing_never_uses_network(self):
        target = self.directory / "test.jar"
        target.write_bytes(DATA)
        before = target.stat().st_mtime_ns
        with patch.object(fetch, "download_url", side_effect=AssertionError("network")):
            self.assertEqual("verified (already present)", fetch.fetch_one(entry(), self.directory))
        self.assertEqual(before, target.stat().st_mtime_ns)

    def test_wrong_existing_is_not_overwritten(self):
        target = self.directory / "test.jar"
        target.write_bytes(b"keep me")
        with self.assertRaises(fetch.FetchError):
            fetch.fetch_one(entry(), self.directory, opener=lambda _: io.BytesIO(DATA))
        self.assertEqual(b"keep me", target.read_bytes())

    def test_missing_verify_only_never_uses_network(self):
        with self.assertRaises(fetch.FetchError), patch.object(fetch, "download_url", side_effect=AssertionError("network")):
            fetch.fetch_one(entry(), self.directory, verify_only=True)
        self.assert_clean()

    def test_symlink_target_rejected(self):
        outside = self.directory / "outside"
        outside.write_bytes(DATA)
        (self.directory / "test.jar").symlink_to(outside)
        with self.assertRaises(fetch.FetchError):
            fetch.fetch_one(entry(), self.directory)
        self.assertEqual(DATA, outside.read_bytes())

    def test_traversal_and_invalid_manifest(self):
        for filename in ["../escape.jar", "/tmp/escape.jar", "C:\\escape.jar", "a/b.jar", "a\\b.jar", "..jar", "test.jar:ads", "test\n.jar"]:
            bad = entry()
            bad["file"] = filename
            with self.subTest(filename=filename), self.assertRaises(fetch.FetchError):
                self.load(manifest([bad]))
        for mods in [[], [entry(), copy.deepcopy(entry())], [{**entry(), "sha256": "bad"}], [{**entry(), "bytes": -1}], [{**entry(), "bytes": True}], [{**entry(), "role": "unknown"}], [{**entry(), "bundled": True}]]:
            with self.subTest(mods=mods), self.assertRaises(fetch.FetchError):
                self.load(manifest(mods))
        with self.assertRaises(fetch.FetchError):
            self.load({**manifest(), "minecraft": "1.21.11"})

    def test_official_host_and_tls_only(self):
        for url in ["http://cdn.modrinth.com/file", "https://cdn.modrinth.com.evil.test/f", "https://evil.test/f", "file:///tmp/mod.jar", "https://user:pass@cdn.modrinth.com/f", "https://cdn.modrinth.com:444/f", "https://cdn.modrinth.com/f#fragment"]:
            with self.subTest(url=url), self.assertRaises(fetch.FetchError):
                fetch.checked_url(url)
        self.assertEqual("https://cdn.modrinth.com/f", fetch.checked_url("https://cdn.modrinth.com/f"))

    def test_redirect_to_unapproved_host_rejected_before_request(self):
        with self.assertRaises(fetch.FetchError):
            fetch.OfficialRedirects().redirect_request(None, None, 302, "redirect", {}, "https://evil.test/mod.jar")

    def test_modrinth_pinned_metadata(self):
        mod = {**entry("terralith"), "official_page": "https://modrinth.com/datapack/terralith/version/AbCd12"}
        metadata = {"id": "AbCd12", "files": [{"filename": "test.jar", "size": len(DATA), "url": "https://cdn.modrinth.com/data/file.jar"}]}
        calls = []
        def opener(url):
            calls.append(url)
            return io.BytesIO(json.dumps(metadata).encode())
        self.assertEqual("https://cdn.modrinth.com/data/file.jar", fetch.download_url(mod, opener))
        self.assertEqual(["https://api.modrinth.com/v2/version/AbCd12"], calls)
        metadata["id"] = "wrong"
        with self.assertRaises(fetch.FetchError):
            fetch.download_url(mod, opener)
        metadata["id"] = "AbCd12"
        metadata["files"][0]["url"] = "https://evil.test/mod.jar"
        with self.assertRaises(fetch.FetchError):
            fetch.download_url(mod, opener)
        metadata["files"] = []
        with self.assertRaises(fetch.FetchError):
            fetch.download_url(mod, opener)

    def test_concurrent_target_is_not_overwritten(self):
        target = self.directory / "test.jar"
        def opener(_):
            target.write_bytes(b"concurrent")
            return io.BytesIO(DATA)
        with self.assertRaises(FileExistsError):
            fetch.fetch_one(entry(), self.directory, opener=opener)
        self.assertEqual(b"concurrent", target.read_bytes())
        self.assertEqual([], list(self.directory.glob("*.part")))

    def test_cli_verify_missing_has_nonzero_exit_and_no_directory_creation(self):
        path = self.directory / "manifest.json"
        path.write_text(json.dumps(manifest()))
        absent = self.directory / "not-created"
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, fetch.main(["--manifest", str(path), "--mods-dir", str(absent), "--verify-only"]))
        self.assertFalse(absent.exists())

    def test_cli_default_excludes_worldgen_and_opt_in_checks_it(self):
        worldgen = {**entry("terralith"), "file": "worldgen.jar", "role": "experimental_profile", "official_page": "https://modrinth.com/datapack/terralith/version/AbCd12"}
        path = self.directory / "manifest.json"
        path.write_text(json.dumps(manifest([entry(), worldgen])))
        (self.directory / "test.jar").write_bytes(DATA)
        args = ["--manifest", str(path), "--mods-dir", str(self.directory), "--verify-only"]
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(0, fetch.main(args))
            self.assertEqual(1, fetch.main(args + ["--include-worldgen"]))


if __name__ == "__main__":
    unittest.main()
