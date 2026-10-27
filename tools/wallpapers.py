#!/usr/bin/env python3
"""Build wallpapers/index.json, the online wallpaper list the launcher downloads, from wallpapers/catalog.json.

Each catalog entry names a picture wallpapers/files/<id>.webp (1920x1080; add it first, e.g. with ImageMagick:
magick in.jpg -resize 1920x1080^ -gravity center -extent 1920x1080 -quality 66 files/<id>.webp). This script makes
the 384x216 thumbnail in wallpapers/thumbs/ and writes the index with each file's size and SHA-256.

Usage: tools/wallpapers.py            (rewrites the index and any missing thumbnail)
       tools/wallpapers.py --check    (exit 1 when the index, a picture or a thumbnail is out of date)
"""
import hashlib
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent / "wallpapers"
FIELDS = ("id", "title", "credit", "license", "source")


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(check):
    catalog = json.loads((ROOT / "catalog.json").read_text(encoding="utf-8"))
    entries, problems = [], []
    for item in catalog:
        missing = [f for f in FIELDS if not item.get(f)]
        if missing:
            problems.append(f"{item.get('id', '?')}: missing {', '.join(missing)}")
            continue
        picture = ROOT / "files" / f"{item['id']}.webp"
        thumb = ROOT / "thumbs" / f"{item['id']}.webp"
        if not picture.is_file():
            problems.append(f"{item['id']}: no {picture.relative_to(ROOT.parent)}")
            continue
        if not thumb.is_file():
            if check:
                problems.append(f"{item['id']}: no thumbnail")
                continue
            thumb.parent.mkdir(exist_ok=True)
            subprocess.run(["magick", str(picture), "-resize", "384x216^", "-gravity", "center", "-extent", "384x216",
                            "-quality", "72", str(thumb)], check=True)
        entries.append({**{f: item[f] for f in FIELDS},
                        "file": f"files/{item['id']}.webp", "size": picture.stat().st_size, "sha256": sha256(picture),
                        "thumb": f"thumbs/{item['id']}.webp"})
    index = json.dumps({"version": 1, "wallpapers": entries}, ensure_ascii=False, indent=1) + "\n"
    path = ROOT / "index.json"
    if check:
        if not path.is_file() or path.read_text(encoding="utf-8") != index:
            problems.append("index.json is out of date: run tools/wallpapers.py")
    elif not problems:
        path.write_text(index, encoding="utf-8")
    for p in problems:
        print(p, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(build("--check" in sys.argv[1:]))
