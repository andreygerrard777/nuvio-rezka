"""Validate a compiled CS3 and produce the Nuvio/CloudStream repository files for it.

Standard library only. Output (repo.json, plugins.json, Rezka.cs3) is meant to be attached to
one GitHub release, so the install URL never changes:
    https://github.com/<owner>/<repo>/releases/download/<tag>/repo.json
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import zipfile

ENTRY_POINT = "ua.nuvio.rezka.RezkaPlugin"
FILENAME = "Rezka.cs3"


def package(cs3: Path, repository: str, tag: str, output: Path):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("repository must be GitHub owner/repository")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", tag):
        raise ValueError("release tag must contain only letters, digits, dots, dashes or underscores")
    data = cs3.read_bytes()
    if len(data) > 10 * 1024 * 1024:
        raise ValueError("CS3 exceeds Nuvio's 10 MiB limit")
    with zipfile.ZipFile(cs3) as archive:
        if not archive.read("classes.dex").startswith(b"dex\n"):
            raise ValueError("classes.dex does not have a DEX header")
        manifest = json.loads(archive.read("manifest.json"))
    if manifest.get("pluginClassName") != ENTRY_POINT:
        raise ValueError("Unexpected plugin entry point")
    version = manifest.get("version")
    if not isinstance(version, int) or version < 1:
        raise ValueError("manifest.json has no positive integer version")

    output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(cs3, output / FILENAME)
    base = f"https://github.com/{repository}/releases/download/{tag}"
    plugins = [{
        "name": "HDRezka", "internalName": "Rezka",
        "url": f"{base}/{FILENAME}", "version": version, "apiVersion": 1,
        "status": 1, "language": "uk", "authors": ["nuvio-uk-providers"],
        "description": "HDRezka: фільми, серіали, мультфільми та аніме. Усі озвучки (українські першими), до 4K, субтитри.",
        "tvTypes": ["Movie", "TvSeries", "Cartoon", "Anime", "AnimeMovie"],
        "iconUrl": "https://www.google.com/s2/favicons?domain=rezka.ag&sz=128",
        "repositoryUrl": f"https://github.com/{repository}",
        "fileSize": len(data), "fileHash": "sha256-" + hashlib.sha256(data).hexdigest(),
    }]
    repo = {
        "name": "Nuvio HDRezka",
        "description": "Нативний плагін HDRezka для Nuvio (Android TV, full-збірка)",
        "manifestVersion": 1, "pluginLists": [f"{base}/plugins.json"],
    }
    for name, value in [("repo.json", repo), ("plugins.json", plugins)]:
        (output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Install URL for Nuvio (after the files are attached to the release):")
    print(f"{base}/repo.json")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--cs3", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--output", type=Path, default=Path("dist"))
    args = parser.parse_args()
    package(args.cs3, args.repository, args.tag, args.output)
