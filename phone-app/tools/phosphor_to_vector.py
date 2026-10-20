"""Turn Phosphor SVG icons into Android vector drawables (white, tinted in Compose).

Usage: python3 phone-app/tools/phosphor_to_vector.py [weight ...]
Reads prototype/assets/icons/phosphor/<weight>/*.svg and writes
phone-app/app/src/main/res/drawable/ph_<name>[_<weight>].xml (no suffix for regular).
"""

import pathlib
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "prototype/assets/icons/phosphor"
OUT = ROOT / "phone-app/app/src/main/res/drawable"
NS = "{http://www.w3.org/2000/svg}"


def convert(svg_path: pathlib.Path, weight: str) -> pathlib.Path:
    svg = ET.parse(svg_path).getroot()
    _, _, vw, vh = svg.get("viewBox", "0 0 256 256").split()
    paths = []
    for el in svg.iter():
        if el.tag != f"{NS}path":
            continue
        alpha = el.get("opacity")
        extra = f' android:fillAlpha="{alpha}"' if alpha else ""
        paths.append(f'    <path android:fillColor="#FFFFFFFF"{extra} android:pathData="{el.get("d")}" />')
    if not paths:
        raise SystemExit(f"no paths in {svg_path}")
    name = "ph_" + svg_path.stem.replace("-", "_") + ("" if weight == "regular" else f"_{weight}")
    out = OUT / f"{name}.xml"
    out.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- Phosphor Icons ({weight}), MIT: {svg_path.stem} -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n'
        '    android:height="24dp"\n'
        f'    android:viewportWidth="{vw}"\n'
        f'    android:viewportHeight="{vh}">\n' + "\n".join(paths) + "\n</vector>\n"
    )
    return out


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for weight in sys.argv[1:] or ["regular", "fill"]:
        for svg in sorted((SRC / weight).glob("*.svg")):
            convert(svg, weight)
    print(len(list(OUT.glob("ph_*.xml"))), "drawables in", OUT.relative_to(ROOT))
