#!/usr/bin/env python3
"""Apply reviewed, exact-anchor changes to the pinned EasyTier source."""
import json
import sys
from pathlib import Path

root = Path(sys.argv[1]).resolve(strict=True)
manifest = Path(__file__).with_name("easytier-android-patches.json")
staged = {}
for patch in json.loads(manifest.read_text(encoding="utf-8")):
    path = (root / patch["path"]).resolve(strict=True)
    if not path.is_relative_to(root):
        raise SystemExit("Patch path escapes source checkout")
    source = staged.get(path)
    if source is None:
        source = path.read_text(encoding="utf-8")
    if source.count(patch["old"]) != 1:
        raise SystemExit(f"Unexpected upstream source: {patch['path']}")
    staged[path] = source.replace(patch["old"], patch["new"], 1)
for path, source in staged.items():
    path.write_text(source, encoding="utf-8")
print(f"Applied reviewed Android patches to {len(staged)} source files")
