#!/usr/bin/env python3
"""Deterministic, offline Bekhilitt release packaging (Python 3.9+, stdlib).

Use check before approval, build only for an approved runtime hash, and verify
on the finished ZIP. No dependency download, build, upload or game launch occurs.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import tempfile
import zipfile

STAMP = (1980, 1, 1, 0, 0, 0)
MAX_FILE = 64 * 1024 * 1024
MAX_TOTAL = 256 * 1024 * 1024
WRAPPER_SHA256 = "423cb469ccc0ecc31f0e4e1c309976198ccb734cdcbb7029d4bda0f18f57e8d9"
# Gradle's official 9.2.1 checksums: https://gradle.org/release-checksums/
DISTRIBUTION_SHA256 = "72f44c9f8ebcb1af43838f45ee5c4aa9c5444898b3468ab3f4af7b6076c5bc3f"
ROOT_FILES = (".gitignore", "AGENTS.md", "README.md", "CHANGELOG.md", "DEPENDENCIES.json",
              "build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat",
              "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")
SCRIPT_FILES = ("scripts/dev.sh", "scripts/fetch-official-mods.py", "scripts/test_fetch_official_mods.py",
                "scripts/generate-casca-texture.py", "scripts/generate-griffith-assets.py",
                "scripts/generate-guts-assets.py", "scripts/package-epic-release.py",
                "scripts/test_package_epic_release.py")
TEXT_SUFFIXES = {".java", ".json", ".toml", ".md", ".txt", ".gradle", ".properties", ".py", ".sh", ".bat"}
RESOURCE_SUFFIXES = {".json", ".png", ".toml", ".mcmeta", ".ogg", ".txt"}
BLOCKED_PARTS = {".git", ".gradle", ".gradle-home", "__pycache__", "libs", "run", "build",
                 "save-fixtures", "logs", ".tools", "workspace-tools", "node_modules"}
EXECUTABLES = {"gradlew", "scripts/dev.sh"}
SOURCE_INDEX = "SOURCE_FILES.json"
RELEASE_INDEX = "FILES.json"


class PackageError(Exception):
    pass


def digest(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def safe_name(name):
    if (not isinstance(name, str) or not name or "\\" in name or ":" in name
            or any(ord(c) < 32 for c in name) or name.startswith("/")
            or any(p in {"", ".", ".."} or p.endswith((" ", ".")) for p in name.split("/"))):
        raise PackageError("Unsafe archive path: " + repr(name))
    return name


def checked_path(path, root=None):
    path = Path(os.path.abspath(path))
    for part in (path, *path.parents):
        if part.is_symlink():
            raise PackageError("Symlink input/output refused: " + str(part))
    if root is not None:
        try:
            path.relative_to(root)
        except ValueError as exc:
            raise PackageError("Input is outside repository: " + str(path)) from exc
    return path


def read_file(path):
    checked_path(path)
    if not path.is_file() or not stat.S_ISREG(path.stat().st_mode):
        raise PackageError("Missing or non-regular input: " + str(path))
    if path.stat().st_size > MAX_FILE:
        raise PackageError("Oversized input: " + str(path))
    # Read each accepted file once: the manifest and ZIP use these same bytes.
    data = path.read_bytes()
    if len(data) > MAX_FILE:
        raise PackageError("Input grew past size limit: " + str(path))
    return data


def check_text(name, data):
    if PurePosixPath(name).suffix in TEXT_SUFFIXES or name in {"gradlew", ".gitignore"}:
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise PackageError("Non-UTF-8 text: " + name) from exc
        # Construct patterns separately so the packager and its tests can be shipped.
        private_roots = ("/" + "workspace/", "/home/" + "agent/")
        if not name.startswith(("scripts/test_", "src/test/")):
            private_roots += ("/" + "tmp/",)
        if any(p in text for p in private_roots):
            raise PackageError("Internal absolute temporary path in " + name)


def add_file(files, name, data):
    safe_name(name)
    if any(key.casefold() == name.casefold() for key in files):
        raise PackageError("Duplicate/case-colliding archive path: " + name)
    if len(data) > MAX_FILE or sum(map(len, files.values())) + len(data) > MAX_TOTAL:
        raise PackageError("Package exceeds size limit")
    check_text(name, data)
    files[name] = data


def source_allowed(name):
    parts = PurePosixPath(name).parts
    if any(p in BLOCKED_PARTS or p.startswith(".gradle-") for p in parts):
        return False
    if name in ROOT_FILES or name in SCRIPT_FILES:
        return True
    if name.startswith("docs/"):
        return PurePosixPath(name).suffix in {".md", ".json", ".txt"}
    if re.fullmatch(r"src/(main|client|test)/java/dev/bekhilitt/.+\.java", name):
        return True
    if name == "src/main/resources/META-INF/neoforge.mods.toml":
        return True
    return bool(re.fullmatch(r"src/(main|client|test)/resources/(assets|data)/bekhilitt/.+", name)
                and PurePosixPath(name).suffix in RESOURCE_SUFFIXES)


def collect_source(repo):
    files = {}
    for name in ROOT_FILES + SCRIPT_FILES:
        add_file(files, name, read_file(repo / name))
    for directory in ("src", "docs"):
        base = checked_path(repo / directory, repo)
        if not base.is_dir():
            raise PackageError("Missing source directory: " + directory)
        for current, dirs, names in os.walk(base, followlinks=False):
            # Never silently traverse a symlink, including one disguised as a directory.
            for child in sorted(dirs + names):
                checked_path(Path(current) / child, repo)
            for filename in sorted(names):
                path = Path(current) / filename
                name = path.relative_to(repo).as_posix()
                if source_allowed(name):
                    add_file(files, name, read_file(path))
                elif directory == "src":
                    raise PackageError("Unreviewed file in source tree: " + name)
    if not any(n.startswith("src/test/java/") for n in files):
        raise PackageError("Source archive would lack Java tests")
    validate_wrapper(files)
    return files


def validate_wrapper(files):
    if digest(files["gradle/wrapper/gradle-wrapper.jar"]) != WRAPPER_SHA256:
        raise PackageError("Gradle wrapper JAR does not match official 9.2.1 hash")
    properties = files["gradle/wrapper/gradle-wrapper.properties"].decode("utf-8")
    expected = {
        "distributionBase": "GRADLE_USER_HOME", "distributionPath": "wrapper/dists",
        "distributionUrl": r"https\://services.gradle.org/distributions/gradle-9.2.1-bin.zip",
        "networkTimeout": "60000", "validateDistributionUrl": "true",
        "zipStoreBase": "GRADLE_USER_HOME", "zipStorePath": "wrapper/dists",
        "distributionSha256Sum": DISTRIBUTION_SHA256,
    }
    parsed = {}
    # Deliberately accept only this release's canonical simple Properties form.
    # Java Properties allows duplicate keys, escapes and continuations; rejecting
    # those forms prevents a later effective value bypassing an earlier match.
    for raw in properties.splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        if (not separator or not re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", key)
                or key in parsed or key not in expected):
            raise PackageError("Gradle wrapper distribution/version/hash changed; review required")
        parsed[key] = value
    if parsed != expected:
        raise PackageError("Gradle wrapper distribution/version/hash changed; review required")


def manifest_mods(data):
    manifest = json.loads(data)
    if (manifest.get("minecraft"), manifest.get("neoforge"), manifest.get("java")) != ("1.21.1", "21.1.219", "21"):
        raise PackageError("Unexpected dependency profile")
    mods = manifest.get("mods")
    if not isinstance(mods, list) or not mods:
        raise PackageError("Missing dependency list")
    ids, names = set(), set()
    for mod in mods:
        if (not isinstance(mod, dict) or mod.get("bundled") is not False
                or not re.fullmatch(r"[0-9a-f]{64}", str(mod.get("sha256", "")))
                or type(mod.get("bytes")) is not int or not 0 < mod["bytes"] <= MAX_TOTAL):
            raise PackageError("Invalid or bundled dependency")
        name, ident = mod.get("file"), mod.get("id")
        safe_name(name)
        if "/" in name or not name.endswith(".jar") or not isinstance(ident, str) or ident in ids or name.casefold() in names:
            raise PackageError("Unsafe or duplicate dependency identity")
        ids.add(ident)
        names.add(name.casefold())
    if not any(m["id"] == "epicfight" and m.get("role") == "required" for m in mods):
        raise PackageError("Epic Fight must be required and fetched separately")
    return mods


def zip_members(data, allow_directories=False):
    files, folded = {}, set()
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if len(archive.infolist()) > 10000:
            raise PackageError("Too many ZIP entries")
        total = 0
        for entry in archive.infolist():
            name = entry.filename
            safe_name(name[:-1] if entry.is_dir() else name)
            if name.casefold() in folded:
                raise PackageError("Duplicate ZIP entry: " + name)
            folded.add(name.casefold())
            mode = (entry.external_attr >> 16) & 0o170000
            if mode not in {0, stat.S_IFREG, stat.S_IFDIR} or entry.flag_bits & 1:
                raise PackageError("Symlink/special/encrypted ZIP entry: " + name)
            if entry.is_dir():
                if not allow_directories:
                    raise PackageError("Unexpected directory entry: " + name)
                continue
            if mode == stat.S_IFDIR:
                raise PackageError("Inconsistent directory metadata: " + name)
            total += entry.file_size
            if entry.file_size > MAX_FILE or total > MAX_TOTAL:
                raise PackageError("ZIP expands past size limit")
            files[name] = archive.read(entry)  # also checks each CRC
    return files


def validate_runtime(data, expected_hash, version, source=None):
    if not re.fullmatch(r"[0-9a-f]{64}", expected_hash or "") or digest(data) != expected_hash:
        raise PackageError("Runtime JAR SHA-256 mismatch")
    entries = zip_members(data, allow_directories=True)
    for name in entries:
        owned = (name == "META-INF/MANIFEST.MF" or name == "META-INF/neoforge.mods.toml"
                 or (name.startswith("dev/bekhilitt/") and name.endswith(".class"))
                 or (name.startswith(("assets/bekhilitt/", "data/bekhilitt/"))
                     and PurePosixPath(name).suffix in RESOURCE_SUFFIXES))
        if not owned:
            raise PackageError("Non-owned or unreviewed runtime entry: " + name)
        check_text(name, entries[name])
    if "dev/bekhilitt/BekhilittMod.class" not in entries:
        raise PackageError("Not a Bekhilitt runtime JAR")
    metadata = entries.get("META-INF/neoforge.mods.toml", b"").decode("utf-8")
    if not re.search(r'(?m)^version\s*=\s*"' + re.escape(version) + r'"\s*$', metadata):
        raise PackageError("Runtime version differs from source version")
    if source is not None:
        resources = {n[len("src/main/resources/"):]: b for n, b in source.items() if n.startswith("src/main/resources/")}
        for name, content in resources.items():
            if name == "META-INF/neoforge.mods.toml":
                content = content.replace(b"${version}", version.encode("utf-8"))
            if entries.get(name) != content:
                raise PackageError("Runtime/source resource mismatch: " + name)
        for name in entries:
            if name.startswith(("assets/", "data/")) and name not in resources:
                raise PackageError("Runtime resource absent from source: " + name)
            if name.endswith(".class"):
                java = name.split("$", 1)[0].removesuffix(".class") + ".java"
                if not any("src/" + side + "/java/" + java in source for side in ("main", "client")):
                    raise PackageError("Runtime class absent from source: " + name)
    return entries


def inventory(files, kind, metadata=None):
    result = {"schema": 1, "kind": kind, "hash_algorithm": "SHA-256",
              "files": [{"path": n, "bytes": len(files[n]), "sha256": digest(files[n])} for n in sorted(files)]}
    if metadata:
        result.update(metadata)
    return json_bytes(result)


def zip_bytes(files, root):
    safe_name(root)
    output = io.BytesIO()
    # ZIP_STORED avoids zlib-version variation. File order, time and modes are fixed.
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED, allowZip64=False) as archive:
        for name in sorted(files):
            safe_name(name)
            entry = zipfile.ZipInfo(root + "/" + name, date_time=STAMP)
            entry.create_system = 3
            entry.compress_type = zipfile.ZIP_STORED
            executable = name in EXECUTABLES or name.startswith("scripts/") and name.endswith(".py")
            entry.external_attr = (stat.S_IFREG | (0o755 if executable else 0o644)) << 16
            archive.writestr(entry, files[name])
    return output.getvalue()


def unroot(data):
    entries = zip_members(data)
    roots = {n.split("/", 1)[0] for n in entries}
    if len(roots) != 1 or any("/" not in n for n in entries):
        raise PackageError("Archive must have one top-level folder")
    return next(iter(roots)), {n.split("/", 1)[1]: b for n, b in entries.items()}


def verify_inventory(files, index_name, expected_kind):
    if index_name not in files:
        raise PackageError("Missing inventory: " + index_name)
    index = json.loads(files[index_name])
    if index.get("schema") != 1 or index.get("kind") != expected_kind or index.get("hash_algorithm") != "SHA-256":
        raise PackageError("Invalid inventory header")
    records = index.get("files")
    if not isinstance(records, list):
        raise PackageError("Invalid inventory file list")
    names = []
    for record in records:
        name = safe_name(record["path"])
        names.append(name)
        content = files.get(name)
        if content is None or record.get("bytes") != len(content) or record.get("sha256") != digest(content):
            raise PackageError("Inventory content mismatch: " + name)
    if names != sorted(set(names)) or set(names) != set(files) - {index_name}:
        raise PackageError("Inventory coverage mismatch")
    return index


def prepare(args):
    repo = checked_path(args.repo)
    source = collect_source(repo)
    versions = re.findall(r"(?m)^mod_version=([^\r\n]+)$", source["gradle.properties"].decode("utf-8"))
    if len(versions) != 1 or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+-epic\.[0-9]+", versions[0]):
        raise PackageError("Expected one experimental Epic mod_version")
    version = versions[0]
    manifest_mods(source["DEPENDENCIES.json"])
    jar_path = checked_path(args.jar if args.jar.is_absolute() else repo / args.jar)
    if jar_path.name != "bekhilitt-" + version + ".jar":
        raise PackageError("Expected exact runtime filename bekhilitt-" + version + ".jar")
    jar = read_file(jar_path)
    validate_runtime(jar, args.jar_sha256, version, source)
    selected_docs = {}
    for role in ("install", "qa", "release"):
        path = getattr(args, role + "_doc")
        path = checked_path(path if path.is_absolute() else repo / path, repo)
        name = path.relative_to(repo).as_posix()
        if not name.startswith("docs/") or path.suffix != ".md" or name not in source:
            raise PackageError("Selected document must be an existing docs/*.md source file: " + role)
        text = source[name].decode("utf-8")
        if not re.search("[А-Яа-яЁё]", text):
            raise PackageError("Selected Russian document has no Russian text: " + name)
        if role == "qa" and args.jar_sha256 not in text:
            raise PackageError("QA document must identify the exact approved runtime SHA-256")
        selected_docs[role] = name
    if len(set(selected_docs.values())) != 3:
        raise PackageError("Install, QA and release documents must be distinct")
    outer = {n: b for n, b in source.items() if n.startswith("docs/") or n in
             {"README.md", "CHANGELOG.md", "DEPENDENCIES.json", "scripts/fetch-official-mods.py",
              "scripts/test_fetch_official_mods.py", "scripts/package-epic-release.py"}}
    add_file(outer, "mods/" + jar_path.name, jar)
    screenshots = []
    for raw in args.screenshot:
        path = checked_path(raw if raw.is_absolute() else repo / raw, repo)
        data = read_file(path)
        if path.suffix.lower() != ".png" or not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise PackageError("Expected an explicitly selected native PNG screenshot")
        name = "screenshots/" + path.name
        add_file(outer, name, data)
        screenshots.append(name)
    metadata = {"version": version, "runtime_jar": "mods/" + jar_path.name,
                "runtime_sha256": args.jar_sha256, "documents": selected_docs,
                "screenshots": sorted(screenshots), "dependencies_bundled": False,
                "source_zip": "Bekhilitt-" + version + "-source.zip"}
    # Only path-free metadata is emitted: never disclose the build machine layout.
    return source, outer, metadata


def verify_release(data, expected_jar_hash):
    root, outer = unroot(data)
    info = verify_inventory(outer, RELEASE_INDEX, "bekhilitt-release")
    version = info["version"]
    if (not isinstance(version, str) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+-epic\.[0-9]+", version)
            or info.get("dependencies_bundled") is not False
            or info["runtime_jar"] != "mods/bekhilitt-" + version + ".jar"
            or info["source_zip"] != "Bekhilitt-" + version + "-source.zip"):
        raise PackageError("Invalid release layout metadata")
    screenshots = info.get("screenshots")
    if not isinstance(screenshots, list) or any(not isinstance(n, str) for n in screenshots):
        raise PackageError("Invalid screenshot list")
    if screenshots != sorted(set(screenshots)):
        raise PackageError("Invalid screenshot list")
    for name in screenshots:
        safe_name(name)
        if (not re.fullmatch(r"screenshots/[^/]+\.png", name, re.IGNORECASE)
                or not outer.get(name, b"").startswith(b"\x89PNG\r\n\x1a\n")):
            raise PackageError("Invalid screenshot entry")
    documents = info.get("documents")
    if (not isinstance(documents, dict) or set(documents) != {"install", "qa", "release"}
            or any(not isinstance(n, str) for n in documents.values())
            or len(set(documents.values())) != 3):
        raise PackageError("Invalid release document list")
    if root != "Bekhilitt-" + version or info["runtime_sha256"] != expected_jar_hash:
        raise PackageError("Release identity/hash differs from supplied approval")
    source_root, source = unroot(outer[info["source_zip"]])
    if source_root != "Bekhilitt-source":
        raise PackageError("Unexpected source archive root")
    verify_inventory(source, SOURCE_INDEX, "bekhilitt-source")
    for name, content in source.items():
        if name != SOURCE_INDEX and not source_allowed(name):
            raise PackageError("Excluded file present in source archive: " + name)
        check_text(name, content)
    for name in ROOT_FILES + SCRIPT_FILES:
        if name not in source:
            raise PackageError("Incomplete source archive: " + name)
    if not any(n.startswith("src/test/java/") for n in source):
        raise PackageError("Source archive lacks Java tests")
    validate_wrapper(source)
    source_version = re.findall(r"(?m)^mod_version=([^\r\n]+)$", source["gradle.properties"].decode("utf-8"))
    if source_version != [version]:
        raise PackageError("Source version differs from release version")
    manifest_mods(outer["DEPENDENCIES.json"])
    if outer["DEPENDENCIES.json"] != source["DEPENDENCIES.json"]:
        raise PackageError("Outer/source dependency manifests differ")
    validate_runtime(outer[info["runtime_jar"]], expected_jar_hash, version, source)
    allowed_outer = {"README.md", "CHANGELOG.md", "DEPENDENCIES.json", RELEASE_INDEX,
                     "scripts/fetch-official-mods.py", "scripts/test_fetch_official_mods.py",
                     "scripts/package-epic-release.py", info["source_zip"], info["runtime_jar"], *info["screenshots"]}
    allowed_outer.update(n for n in source if n.startswith("docs/"))
    if set(outer) != allowed_outer:
        raise PackageError("Unexpected or missing release entries")
    for name, content in outer.items():
        check_text(name, content)
        if name in source and source[name] != content:
            raise PackageError("Outer/source duplicate differs: " + name)
    for role, name in documents.items():
        if (not name.startswith("docs/") or not name.endswith(".md")
                or name not in outer or outer[name] != source.get(name)):
            raise PackageError("Missing or inconsistent release document")
        text = outer[name].decode("utf-8")
        if not re.search("[А-Яа-яЁё]", text) or role == "qa" and expected_jar_hash not in text:
            raise PackageError("Invalid Russian release/QA document")
    return info


def publish_no_clobber(path, data):
    path = checked_path(path)
    if not path.parent.is_dir() or path.exists():
        raise PackageError("Output parent must exist and output must not exist")
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix=".bekhilitt-package-", suffix=".part", dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        # Same-filesystem, atomic, no-clobber. Unsupported hard links fail safely.
        os.link(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="command", required=True)
    for command in ("check", "build"):
        sub = commands.add_parser(command)
        sub.add_argument("--repo", type=Path, default=Path(__file__).absolute().parent.parent)
        sub.add_argument("--jar", type=Path, required=True, help="exact owned runtime JAR path, relative to repo or absolute")
        sub.add_argument("--jar-sha256", required=True, help="approved/QA-tested SHA-256; never inferred")
        for role in ("install", "qa", "release"):
            sub.add_argument("--" + role + "-doc", type=Path, required=True, help="existing Russian docs/*.md file, relative to repo")
        sub.add_argument("--screenshot", action="append", type=Path, default=[], help="one explicitly reviewed native Minecraft PNG; repeat as needed")
        if command == "build":
            sub.add_argument("--output", type=Path, required=True, help="new ZIP path; never overwrite")
    verify = commands.add_parser("verify")
    verify.add_argument("--archive", type=Path, required=True)
    verify.add_argument("--jar-sha256", required=True, help="independently supplied approved runtime hash")
    return result


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        if args.command == "verify":
            data = read_file(checked_path(args.archive))
            info = verify_release(data, args.jar_sha256)
            print(json.dumps({"verified": True, "version": info["version"], "zip_sha256": digest(data)}, sort_keys=True))
            return 0
        source, outer, metadata = prepare(args)
        if args.command == "check":
            print(json.dumps({"checked": True, "version": metadata["version"], "source_files": len(source),
                              "runtime_sha256": metadata["runtime_sha256"], "documents": metadata["documents"],
                              "screenshots": metadata["screenshots"], "archive_written": False}, sort_keys=True))
            return 0
        add_file(source, SOURCE_INDEX, inventory(source, "bekhilitt-source"))
        add_file(outer, metadata["source_zip"], zip_bytes(source, "Bekhilitt-source"))
        add_file(outer, RELEASE_INDEX, inventory(outer, "bekhilitt-release", metadata))
        data = zip_bytes(outer, "Bekhilitt-" + metadata["version"])
        if len(data) > MAX_FILE:
            raise PackageError("Final release ZIP exceeds 64 MiB limit; nothing was published")
        verify_release(data, args.jar_sha256)
        publish_no_clobber(args.output, data)
        written = read_file(checked_path(args.output))
        if written != data:
            raise PackageError("Published archive differs from verified bytes")
        verify_release(written, args.jar_sha256)
        print(json.dumps({"created": args.output.name, "version": metadata["version"], "bytes": len(data),
                          "zip_sha256": digest(data), "runtime_sha256": args.jar_sha256,
                          "source_files": len(source) - 1, "release_files": len(outer)}, sort_keys=True))
        return 0
    except (PackageError, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, RuntimeError) as exc:
        print("ERROR: " + str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
