"""Offline fixture-only packaging tests; never make or approve a real release."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("package_epic_release", HERE / "package-epic-release.py")
pack = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pack)
VERSION = "1.2.0-epic.1"
JAR_NAME = "bekhilitt-" + VERSION + ".jar"


def raw_zip(entries):
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w") as archive:
        for name, content in entries.items():
            archive.writestr(name, content)
    return result.getvalue()


class PackageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="bekhilitt-package-fixture-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "fixture-repo"
        self.repo.mkdir()
        self.output = self.root / "fixture-only.zip"
        self.fixture_manifest = {
            "minecraft": "1.21.1", "neoforge": "21.1.219", "java": "21", "mods": [{
                "id": "epicfight", "version": "21.17.3.1", "file": "fixture-epicfight.jar",
                "sha256": pack.digest(b"offline dependency fixture"), "bytes": len(b"offline dependency fixture"),
                "bundled": False, "role": "required",
                "official_page": "https://www.curseforge.com/minecraft/mc-mods/epic-fight-mod/files/8175609"}]}
        for name in pack.ROOT_FILES + pack.SCRIPT_FILES:
            self.put(name, "# Offline packaging test fixture, not release content.\n")
        self.put("gradle/wrapper/gradle-wrapper.jar", (HERE.parent / "gradle/wrapper/gradle-wrapper.jar").read_bytes())
        self.put("gradle/wrapper/gradle-wrapper.properties", (HERE.parent / "gradle/wrapper/gradle-wrapper.properties").read_bytes())
        self.put("scripts/fetch-official-mods.py", (HERE / "fetch-official-mods.py").read_bytes())
        self.put("scripts/test_fetch_official_mods.py", (HERE / "test_fetch_official_mods.py").read_bytes())
        self.put("scripts/package-epic-release.py", (HERE / "package-epic-release.py").read_bytes())
        self.put("gradle.properties", "mod_version=" + VERSION + "\n")
        self.put("DEPENDENCIES.json", pack.json_bytes(self.fixture_manifest))
        self.put("src/main/java/dev/bekhilitt/BekhilittMod.java", "package dev.bekhilitt; class BekhilittMod {}\n")
        self.put("src/test/java/dev/bekhilitt/FixtureTest.java", "package dev.bekhilitt; class FixtureTest {}\n")
        self.put("src/main/resources/META-INF/neoforge.mods.toml", 'modId="bekhilitt"\nversion="${version}"\n')
        self.put("src/main/resources/assets/bekhilitt/lang/ru_ru.json", '{"fixture":"Проверка"}\n')
        self.jar_entries = {"dev/bekhilitt/BekhilittMod.class": b"fixture-only fake bytecode",
                            "META-INF/neoforge.mods.toml": ('modId="bekhilitt"\nversion="' + VERSION + '"\n').encode(),
                            "assets/bekhilitt/lang/ru_ru.json": '{"fixture":"Проверка"}\n'.encode()}
        self.jar_path = self.repo / "build/libs" / JAR_NAME
        self.reset_jar()
        self.put("docs/INSTALL_FIXTURE.md", "# Тестовая инструкция. Не настоящий документ релиза.\n")
        self.put("docs/RELEASE_FIXTURE.md", "# Тестовое описание. Не настоящий документ релиза.\n")

    def put(self, name, content):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content.encode("utf-8") if isinstance(content, str) else content)
        return path

    def reset_jar(self):
        data = raw_zip(self.jar_entries)
        self.jar_hash = pack.digest(data)
        self.put("build/libs/" + JAR_NAME, data)
        self.put("docs/QA_FIXTURE.md", "# Тестовый отчёт; реальный QA не заявляется.\n" + self.jar_hash + "\n")

    def arguments(self, command="build", extra=()):
        args = [command, "--repo", str(self.repo), "--jar", str(self.jar_path), "--jar-sha256", self.jar_hash,
                "--install-doc", "docs/INSTALL_FIXTURE.md", "--qa-doc", "docs/QA_FIXTURE.md",
                "--release-doc", "docs/RELEASE_FIXTURE.md"]
        if command == "build":
            args += ["--output", str(self.output)]
        return args + list(extra)

    def run_main(self, args=None):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = pack.main(self.arguments() if args is None else args)
        return code, out.getvalue(), err.getvalue()

    def built(self):
        code, out, err = self.run_main()
        self.assertEqual(0, code, err)
        data = self.output.read_bytes()
        self.assertEqual(pack.digest(data), json.loads(out)["zip_sha256"])
        root, outer = pack.unroot(data)
        self.assertEqual("Bekhilitt-" + VERSION, root)
        return outer

    def failure(self, expected, args=None):
        code, _, err = self.run_main(args)
        self.assertEqual(1, code, err)
        self.assertIn(expected, err)
        self.assertFalse(self.output.exists())
        self.assertEqual([], list(self.root.glob("*.part")))

    def test_complete_inventory_and_source_layout(self):
        outer = self.built()
        info = pack.verify_release(self.output.read_bytes(), self.jar_hash)
        root, source = pack.unroot(outer[info["source_zip"]])
        self.assertEqual("Bekhilitt-source", root)
        self.assertTrue(all(name in source for name in pack.ROOT_FILES + pack.SCRIPT_FILES))
        self.assertIn("src/test/java/dev/bekhilitt/FixtureTest.java", source)
        self.assertIn("scripts/generate-griffith-assets.py", source)
        self.assertEqual(outer["DEPENDENCIES.json"], source["DEPENDENCIES.json"])
        self.assertEqual({"mods/" + JAR_NAME}, {n for n in outer if n.endswith(".jar")})
        self.assertEqual({"gradle/wrapper/gradle-wrapper.jar"}, {n for n in source if n.endswith(".jar")})

    def test_check_writes_no_release(self):
        code, out, err = self.run_main(self.arguments("check"))
        self.assertEqual(0, code, err)
        self.assertFalse(json.loads(out)["archive_written"])
        self.assertFalse(self.output.exists())

    def test_required_hash_is_not_inferred(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            pack.main(["build", "--repo", str(self.repo), "--jar", str(self.jar_path)])

    def test_wrong_hash_rejected(self):
        self.jar_hash = "a" * 64
        self.failure("SHA-256 mismatch")

    def test_wrong_runtime_filename_rejected(self):
        self.jar_path = self.put("build/libs/not-bekhilitt.jar", self.jar_path.read_bytes())
        self.failure("exact runtime filename")

    def test_runtime_version_rejected(self):
        self.jar_entries["META-INF/neoforge.mods.toml"] = b'version="1.1.0"\n'
        self.reset_jar()
        self.failure("Runtime version differs")

    def test_exact_qa_hash_required(self):
        self.put("docs/QA_FIXTURE.md", "# Тестовый старый отчёт\n")
        self.failure("exact approved runtime SHA-256")

    def test_missing_document_never_synthesized(self):
        (self.repo / "docs/INSTALL_FIXTURE.md").unlink()
        self.failure("existing docs/*.md")

    def test_russian_document_required(self):
        self.put("docs/RELEASE_FIXTURE.md", "Fixture-only English text\n")
        self.failure("Russian document")

    def test_documents_must_be_distinct(self):
        self.failure("must be distinct", self.arguments(extra=["--release-doc", "docs/INSTALL_FIXTURE.md"]))

    def test_excluded_runtime_folders_and_cloud_scripts(self):
        for name in ("libs/epicfight.jar", "run/saves/level.dat", ".git/config", ".gradle/cache.jar",
                     "verification/save-fixtures/private.dat", "scripts/cloud-client-qa.sh",
                     "docs/legacy-1.1.0/Play-Bekhilitt.command"):
            self.put(name, "MUST NOT SHIP")
        outer = self.built()
        _, source = pack.unroot(outer["Bekhilitt-" + VERSION + "-source.zip"])
        self.assertFalse(any(content == b"MUST NOT SHIP" for content in source.values()))
        self.assertFalse(any(content == b"MUST NOT SHIP" for content in outer.values()))

    def test_foreign_source_namespace_rejected(self):
        self.put("src/main/java/yesman/epicfight/Copied.java", "class Copied {}")
        self.failure("Unreviewed file in source tree")

    def test_third_party_runtime_entries_rejected(self):
        for name in ("yesman/epicfight/Main.class", "assets/epicfight/model.json", "META-INF/jarjar/dependency.jar"):
            with self.subTest(name=name):
                self.jar_entries[name] = b"third-party fixture"
                self.reset_jar()
                self.failure("Non-owned or unreviewed runtime entry")
                del self.jar_entries[name]

    def test_stale_resource_rejected(self):
        self.put("src/main/resources/assets/bekhilitt/lang/ru_ru.json", "{}")
        self.failure("Runtime/source resource mismatch")

    def test_source_missing_for_class_rejected(self):
        self.jar_entries["dev/bekhilitt/Removed.class"] = b"stale fake bytecode"
        self.reset_jar()
        self.failure("Runtime class absent from source")

    def test_official_wrapper_hash_required(self):
        self.put("gradle/wrapper/gradle-wrapper.jar", b"wrong wrapper")
        self.failure("official 9.2.1 hash")

    def test_wrapper_distribution_checksum_required(self):
        self.put("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=changed\n")
        self.failure("distribution/version/hash changed")

    def test_wrapper_properties_duplicate_effective_values_rejected(self):
        path = self.repo / "gradle/wrapper/gradle-wrapper.properties"
        original = path.read_text()
        for extra in ("distributionUrl=https\\://example.invalid/unreviewed.zip\n",
                      "distributionSha256Sum=" + "0" * 64 + "\n",
                      "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.2.1-bin.zip\n"):
            with self.subTest(extra=extra):
                path.write_text(original + "\n" + extra)
                self.failure("distribution/version/hash changed")

    def test_dependency_bundling_rejected(self):
        self.fixture_manifest["mods"][0]["bundled"] = True
        self.put("DEPENDENCIES.json", pack.json_bytes(self.fixture_manifest))
        self.failure("Invalid or bundled dependency")

    def test_input_file_and_directory_symlinks_rejected(self):
        for target in ("README.md", "src/main/java"):
            with self.subTest(target=target):
                original = self.repo / target
                moved = original.with_name(original.name + ".real")
                original.rename(moved)
                original.symlink_to(moved, target_is_directory=moved.is_dir())
                self.failure("Symlink")
                original.unlink()
                moved.rename(original)

    def test_jar_symlink_rejected(self):
        original = self.jar_path
        moved = original.with_suffix(".real")
        original.rename(moved)
        original.symlink_to(moved)
        self.failure("Symlink")

    def test_output_parent_symlink_rejected(self):
        directory = self.root / "link"
        directory.symlink_to(self.root, target_is_directory=True)
        self.output = directory / "output.zip"
        self.failure("Symlink")

    def test_absolute_internal_paths_rejected(self):
        self.put("docs/DETAIL.md", "Испытания: " + "/" + "workspace/private/data" + "\n")
        self.failure("Internal absolute temporary path")

    def test_selected_screenshot_only(self):
        self.put("verification/screenshots/selected.png", b"\x89PNG\r\n\x1a\nfixture-only")
        self.put("verification/screenshots/omitted.png", b"\x89PNG\r\n\x1a\nfixture-only")
        code, _, err = self.run_main(self.arguments(extra=["--screenshot", "verification/screenshots/selected.png"]))
        self.assertEqual(0, code, err)
        _, outer = pack.unroot(self.output.read_bytes())
        self.assertIn("screenshots/selected.png", outer)
        self.assertNotIn("screenshots/omitted.png", outer)
        self.assertEqual(["screenshots/selected.png"], json.loads(outer["FILES.json"])["screenshots"])

    def test_non_png_screenshot_rejected(self):
        self.put("verification/selected.png", b"fake image, not PNG")
        self.failure("native PNG", self.arguments(extra=["--screenshot", "verification/selected.png"]))

    def test_byte_deterministic_independent_of_input_mtimes_and_modes(self):
        self.built()
        first = self.output.read_bytes()
        self.output.unlink()
        for path in self.repo.rglob("*"):
            if path.is_file():
                os.utime(path, (1700000001, 1700000001))
                path.chmod(0o600)
        self.built()
        self.assertEqual(first, self.output.read_bytes())
        with zipfile.ZipFile(io.BytesIO(first)) as archive:
            self.assertTrue(all(i.date_time == pack.STAMP for i in archive.infolist()))
            self.assertTrue(all(i.compress_type == zipfile.ZIP_STORED for i in archive.infolist()))

    def test_final_archive_size_limit_is_checked_before_publication(self):
        self.built()
        limit = self.output.stat().st_size - 1
        self.output.unlink()
        with patch.object(pack, "MAX_FILE", limit):
            self.failure("nothing was published")

    def test_no_clobber_existing_output(self):
        self.output.write_bytes(b"keep existing artifact")
        code, _, err = self.run_main()
        self.assertEqual(1, code)
        self.assertIn("must not exist", err)
        self.assertEqual(b"keep existing artifact", self.output.read_bytes())
        self.assertEqual([], list(self.root.glob("*.part")))

    def test_verifier_detects_modified_member(self):
        outer = self.built()
        outer["README.md"] += b"tampered"
        with self.assertRaisesRegex(pack.PackageError, "Inventory content mismatch"):
            pack.verify_release(pack.zip_bytes(outer, "Bekhilitt-" + VERSION), self.jar_hash)

    def test_verifier_rejects_unlisted_extra(self):
        outer = self.built()
        outer["unexpected.txt"] = b"unlisted"
        with self.assertRaisesRegex(pack.PackageError, "coverage mismatch"):
            pack.verify_release(pack.zip_bytes(outer, "Bekhilitt-" + VERSION), self.jar_hash)

    def test_verifier_detects_nested_source_change_even_with_updated_outer_inventory(self):
        outer = self.built()
        index = json.loads(outer.pop("FILES.json"))
        _, source = pack.unroot(outer[index["source_zip"]])
        source["README.md"] += b"tampered"
        outer[index["source_zip"]] = pack.zip_bytes(source, "Bekhilitt-source")
        meta = {k: v for k, v in index.items() if k not in {"schema", "kind", "hash_algorithm", "files"}}
        outer["FILES.json"] = pack.inventory(outer, "bekhilitt-release", meta)
        with self.assertRaisesRegex(pack.PackageError, "Inventory content mismatch"):
            pack.verify_release(pack.zip_bytes(outer, "Bekhilitt-" + VERSION), self.jar_hash)

    def test_recomputed_inventory_cannot_smuggle_binary_as_screenshot(self):
        outer = self.built()
        index = json.loads(outer.pop("FILES.json"))
        outer["libs/third-party.jar"] = b"foreign JAR fixture"
        meta = {k: v for k, v in index.items() if k not in {"schema", "kind", "hash_algorithm", "files"}}
        meta["screenshots"] = ["libs/third-party.jar"]
        outer["FILES.json"] = pack.inventory(outer, "bekhilitt-release", meta)
        with self.assertRaisesRegex(pack.PackageError, "Invalid screenshot entry"):
            pack.verify_release(pack.zip_bytes(outer, "Bekhilitt-" + VERSION), self.jar_hash)

    def test_recomputed_inventory_cannot_omit_external_helper(self):
        outer = self.built()
        index = json.loads(outer.pop("FILES.json"))
        del outer["scripts/fetch-official-mods.py"]
        meta = {k: v for k, v in index.items() if k not in {"schema", "kind", "hash_algorithm", "files"}}
        outer["FILES.json"] = pack.inventory(outer, "bekhilitt-release", meta)
        with self.assertRaisesRegex(pack.PackageError, "missing release entries"):
            pack.verify_release(pack.zip_bytes(outer, "Bekhilitt-" + VERSION), self.jar_hash)

    def test_recomputed_inventory_cannot_relabel_runtime(self):
        outer = self.built()
        index = json.loads(outer.pop("FILES.json"))
        meta = {k: v for k, v in index.items() if k not in {"schema", "kind", "hash_algorithm", "files"}}
        meta["runtime_jar"] = "renamed.jar"
        outer["renamed.jar"] = outer.pop("mods/" + JAR_NAME)
        outer["FILES.json"] = pack.inventory(outer, "bekhilitt-release", meta)
        with self.assertRaisesRegex(pack.PackageError, "Invalid release layout"):
            pack.verify_release(pack.zip_bytes(outer, "Bekhilitt-" + VERSION), self.jar_hash)

    def test_recomputed_inventory_cannot_remove_java_tests(self):
        outer = self.built()
        index = json.loads(outer.pop("FILES.json"))
        _, source = pack.unroot(outer[index["source_zip"]])
        source = {n: b for n, b in source.items() if not n.startswith("src/test/") and n != "SOURCE_FILES.json"}
        source["SOURCE_FILES.json"] = pack.inventory(source, "bekhilitt-source")
        outer[index["source_zip"]] = pack.zip_bytes(source, "Bekhilitt-source")
        meta = {k: v for k, v in index.items() if k not in {"schema", "kind", "hash_algorithm", "files"}}
        outer["FILES.json"] = pack.inventory(outer, "bekhilitt-release", meta)
        with self.assertRaisesRegex(pack.PackageError, "lacks Java tests"):
            pack.verify_release(pack.zip_bytes(outer, "Bekhilitt-" + VERSION), self.jar_hash)

    def test_verify_cli_requires_independent_hash(self):
        self.built()
        code, _, err = self.run_main(["verify", "--archive", str(self.output), "--jar-sha256", "a" * 64])
        self.assertEqual(1, code)
        self.assertIn("supplied approval", err)
        code, _, err = self.run_main(["verify", "--archive", str(self.output), "--jar-sha256", self.jar_hash])
        self.assertEqual(0, code, err)

    def test_zip_traversal_duplicate_and_symlink_rejected(self):
        for name in ("../escape", "/absolute", "folder/../escape", "C:/drive", "folder\\escape"):
            with self.subTest(name=name), self.assertRaises(pack.PackageError):
                pack.zip_members(raw_zip({name: b"unsafe"}))
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            entry = zipfile.ZipInfo("link")
            entry.create_system = 3
            entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(entry, b"elsewhere")
        with self.assertRaisesRegex(pack.PackageError, "Symlink"):
            pack.zip_members(data.getvalue())
        with self.assertRaisesRegex(pack.PackageError, "Duplicate"):
            pack.zip_members(raw_zip({"File": b"1", "file": b"2"}))

    def test_helper_finds_manifest_from_unrelated_working_directory_in_both_archives(self):
        outer = self.built()
        _, source = pack.unroot(outer["Bekhilitt-" + VERSION + "-source.zip"])
        for label, files in (("outer", outer), ("source", source)):
            with self.subTest(layout=label):
                destination = self.root / label
                (destination / "scripts").mkdir(parents=True)
                (destination / "scripts/fetch-official-mods.py").write_bytes(files["scripts/fetch-official-mods.py"])
                (destination / "DEPENDENCIES.json").write_bytes(files["DEPENDENCIES.json"])
                absent = destination / "libs"
                result = subprocess.run([sys.executable, "-I", str(destination / "scripts/fetch-official-mods.py"),
                                         "--mods-dir", str(absent), "--verify-only"], cwd=self.root,
                                        capture_output=True, text=True, timeout=15)
                self.assertEqual(1, result.returncode)
                self.assertIn("epicfight: FAILED:", result.stderr)
                self.assertIn("Profile incomplete", result.stderr)
                self.assertNotIn("DEPENDENCIES.json", result.stderr)
                self.assertFalse(absent.exists())
                absent.mkdir()
                (absent / "fixture-epicfight.jar").write_bytes(b"offline dependency fixture")
                result = subprocess.run([sys.executable, "-I", str(destination / "scripts/fetch-official-mods.py"),
                                         "--mods-dir", str(absent), "--verify-only"], cwd=self.root,
                                        capture_output=True, text=True, timeout=15)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn("Selected dependencies verified", result.stdout)


if __name__ == "__main__":
    unittest.main()
