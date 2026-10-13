#!/usr/bin/env python3
"""Fetch pinned external mods from official hosts; never execute or unpack them.

Python 3.9+, standard library only. Run --help for usage. The manifest is trusted
release input: hashes detect corrupted/wrong downloads, not a tampered manifest.
"""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import urllib.parse
import urllib.request

HOSTS = frozenset({"www.curseforge.com", "edge.forgecdn.net", "mediafilez.forgecdn.net",
                   "media.forgecdn.net", "api.modrinth.com", "cdn.modrinth.com"})
PAGE_HOSTS = frozenset({"www.curseforge.com", "modrinth.com"})
MAX_FILE_SIZE = 256 * 1024 * 1024
MAX_METADATA_SIZE = 2 * 1024 * 1024


class FetchError(Exception):
    pass


def checked_url(url, hosts=HOSTS):
    if not isinstance(url, str) or any(ord(c) < 32 for c in url):
        raise FetchError("Invalid URL")
    try:
        p = urllib.parse.urlsplit(url)
        valid = (p.scheme == "https" and p.hostname in hosts and
                 p.port in (None, 443) and not p.username and not p.password and not p.fragment)
    except ValueError as exc:
        raise FetchError("Invalid URL") from exc
    if not valid:
        raise FetchError("Refusing URL outside the HTTPS official-host allowlist")
    return url


class OfficialRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        checked_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def open_official(url):
    checked_url(url)
    req = urllib.request.Request(url, headers={"User-Agent": "BekhilittDependencyFetcher/1.0 (local installer)",
                                               "Accept-Encoding": "identity"})
    response = urllib.request.build_opener(OfficialRedirects()).open(req, timeout=45)
    try:
        checked_url(response.geturl())
    except Exception:
        response.close()
        raise
    return response


def read_manifest(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(data, dict) or (data.get("minecraft"), data.get("neoforge"), data.get("java")) != ("1.21.1", "21.1.219", "21"):
        raise FetchError("Manifest is not the Minecraft 1.21.1 / NeoForge 21.1.219 / Java 21 profile")
    mods = data.get("mods")
    if not isinstance(mods, list) or not mods:
        raise FetchError("Manifest has no dependencies")
    ids, names = set(), set()
    for mod in mods:
        if not isinstance(mod, dict):
            raise FetchError("Invalid dependency entry")
        name, ident = mod.get("file"), mod.get("id")
        if (not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9 ._+()-]*\.jar", name)
                or "/" in name or "\\" in name or ".." in name or name.casefold() in names):
            raise FetchError("Unsafe or duplicate JAR filename")
        if not isinstance(ident, str) or not re.fullmatch(r"[a-z0-9_-]+", ident) or ident in ids:
            raise FetchError("Invalid or duplicate dependency ID")
        if not re.fullmatch(r"[0-9a-f]{64}", str(mod.get("sha256", ""))):
            raise FetchError("Missing or invalid SHA-256 for " + ident)
        size = mod.get("bytes")
        if type(size) is not int or not 0 < size <= MAX_FILE_SIZE:
            raise FetchError("Missing or invalid byte count for " + ident)
        if mod.get("role") not in {"required", "experimental_profile"} or mod.get("bundled") is not False:
            raise FetchError("Unknown role or bundled dependency: " + ident)
        checked_url(mod.get("official_page"), PAGE_HOSTS)
        # Check the provider route before any filesystem writes or requests.
        provider_route(mod)
        ids.add(ident)
        names.add(name.casefold())
    if "epicfight" not in ids or not any(m["id"] == "epicfight" and m["role"] == "required" for m in mods):
        raise FetchError("Required Epic Fight dependency is missing")
    return mods


def provider_route(mod):
    page = mod["official_page"]
    if page == "https://www.curseforge.com/minecraft/mc-mods/epic-fight-mod/files/8175609" and mod["id"] == "epicfight":
        return "https://www.curseforge.com/api/v1/mods/405076/files/8175609/download", None
    match = re.fullmatch(r"https://modrinth\.com/(?:mod|datapack)/([a-z0-9-]+)/version/([A-Za-z0-9]+)", page)
    if match and match.group(1) == mod["id"]:
        return "https://api.modrinth.com/v2/version/" + match.group(2), match.group(2)
    raise FetchError("Unsupported pinned official page; install manually from " + page)


def download_url(mod, opener=open_official):
    url, version_id = provider_route(mod)
    if version_id is None:
        return url
    with opener(url) as response:
        raw = response.read(MAX_METADATA_SIZE + 1)
    if len(raw) > MAX_METADATA_SIZE:
        raise FetchError("Oversized Modrinth response")
    metadata = json.loads(raw)
    if not isinstance(metadata, dict) or metadata.get("id") != version_id:
        raise FetchError("Modrinth returned a different version")
    files = metadata.get("files", [])
    matches = [f for f in files if isinstance(f, dict) and f.get("filename") == mod["file"] and f.get("size") == mod["bytes"]]
    if len(matches) != 1:
        raise FetchError("Pinned filename/size absent or ambiguous on Modrinth")
    return checked_url(matches[0].get("url"), frozenset({"cdn.modrinth.com"}))


def valid_file(path, mod):
    if path.is_symlink() or not path.is_file() or path.stat().st_size != mod["bytes"]:
        return False
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest() == mod["sha256"]


def fetch_one(mod, destination, verify_only=False, opener=open_official):
    target = destination / mod["file"]
    if target.is_symlink():
        raise FetchError("Refusing symlink: " + target.name)
    if target.exists():
        if valid_file(target, mod):
            return "verified (already present)"
        raise FetchError("Existing file failed SHA-256/size validation; move it aside yourself: " + str(target))
    if verify_only:
        raise FetchError("Missing file: " + str(target))
    url = download_url(mod, opener)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix=".bekhilitt-", suffix=".part", dir=destination, delete=False) as output:
            temporary = Path(output.name)
            digest, size = hashlib.sha256(), 0
            with opener(url) as response:
                while True:
                    chunk = response.read(65536)
                    if not chunk:
                        break
                    size += len(chunk)
                    if size > mod["bytes"]:
                        raise FetchError("Download larger than pinned byte count")
                    digest.update(chunk)
                    output.write(chunk)
            if size != mod["bytes"] or digest.hexdigest() != mod["sha256"]:
                raise FetchError("Downloaded file failed SHA-256/size validation")
            output.flush()
            os.fsync(output.fileno())
        # Atomic, no-clobber publication. A concurrent existing file is never overwritten.
        os.link(temporary, target)
        return "downloaded and verified"
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=Path(__file__).resolve().parent.parent / "DEPENDENCIES.json")
    parser.add_argument("--mods-dir", type=Path, required=True, help="mods folder in a NEW isolated 1.21.1 profile")
    parser.add_argument("--include-worldgen", action="store_true", help="also fetch experimental_profile world-generation mods")
    parser.add_argument("--verify-only", action="store_true", help="offline check; never download or create directories")
    args = parser.parse_args(argv)
    try:
        mods = read_manifest(args.manifest)
        selected = [m for m in mods if m["role"] == "required" or args.include_worldgen]
        destination = args.mods_dir.expanduser()
        if destination.is_symlink():
            raise FetchError("Refusing symlink mods directory")
        if not args.verify_only:
            destination.mkdir(parents=True, exist_ok=True)
        failures = 0
        for mod in selected:
            try:
                print(mod["id"] + ": " + fetch_one(mod, destination, args.verify_only))
            except (FetchError, OSError, ValueError, KeyError, TypeError, http.client.HTTPException) as exc:
                failures += 1
                print(mod["id"] + ": FAILED: " + str(exc) + "\nOfficial page: " + mod["official_page"], file=sys.stderr)
        if failures:
            print("Profile incomplete. Do not launch. Download missing files from their official pages, then run --verify-only.", file=sys.stderr)
            return 1
        print("Selected dependencies verified. This does not verify Minecraft, NeoForge, the Bekhilitt JAR, or runtime compatibility.")
        return 0
    except (FetchError, OSError, ValueError, KeyError, TypeError, http.client.HTTPException) as exc:
        print("ERROR: " + str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
