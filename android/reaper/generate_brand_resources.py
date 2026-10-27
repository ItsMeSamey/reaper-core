#!/usr/bin/env python3
# Copyright 2026 The Reaper Authors.
# Use of this source code is governed by a BSD-style license.
"""Generate Android branding from one VectorDrawable, using Android's renderer."""

import argparse
import copy
import json
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ANDROID = "http://schemas.android.com/apk/res/android"
A = "{" + ANDROID + "}"
ET.register_namespace("android", ANDROID)
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
BACKGROUND = "#132A2B"


def write_xml(path, root):
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(root).write(path, encoding="utf-8", xml_declaration=True)


def vector(mark, variant):
    size = variant["size"]
    crop = variant["crop"]
    width = variant.get("width", size)
    viewport_width = crop * width / size
    root = ET.Element("vector", {
        A + "width": f"{width}dp", A + "height": f"{size}dp",
        A + "viewportWidth": str(viewport_width),
        A + "viewportHeight": str(crop),
    })
    background = variant["background"]
    if background != "none":
        path = ("M54,4A50,50 0,1 1,54,104A50,50 0,1 1,54,4Z"
                if background == "circle" else "M0,0H108V108H0Z")
        ET.SubElement(root, "path", {A + "fillColor": BACKGROUND,
                                   A + "pathData": path})
    group = ET.SubElement(root, "group", {
        A + "translateX": str((viewport_width - 108) / 2),
        A + "translateY": str((crop - 108) / 2),
    })
    if "animation_group" in variant:
        group.set(A + "name", variant["animation_group"])
        group.set(A + "pivotX", "54")
        group.set(A + "pivotY", "54")
    if "mark_scale" in variant:
        group.set(A + "pivotX", "54")
        group.set(A + "pivotY", "54")
        group.set(A + "scaleX", str(variant["mark_scale"]))
        group.set(A + "scaleY", str(variant["mark_scale"]))
    if background != "only":
        for child in mark:
            child = copy.deepcopy(child)
            for node in child.iter():
                if A + "fillColor" in node.attrib:
                    node.set(A + "fillColor", variant["color"])
            group.append(child)
    return root


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", required=True)
    parser.add_argument("--mark", required=True)
    parser.add_argument("--nala-dir", required=True)
    parser.add_argument("--rasterizer", required=True)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    spec = json.loads(Path(args.spec).read_text())
    mark = ET.parse(args.mark).getroot()
    out = Path(args.output_dir)
    res = out / "res"
    out.mkdir(parents=True, exist_ok=True)

    for variant in spec["vectors"]:
        write_xml(res / variant["path"], vector(mark, variant))
    for variant in spec["symbols"]:
        write_xml(out / "symbols" / variant["path"], vector(mark, variant))
    for variant in spec["shields"]:
        shield = ET.parse(Path(args.nala_dir) / variant["source"]).getroot()
        for node in shield.iter():
            if A + "fillColor" in node.attrib:
                node.set(A + "fillColor", variant["color"])
        write_xml(out / "symbols" / variant["path"], shield)

    adaptive = ET.Element("adaptive-icon")
    ET.SubElement(adaptive, "background", {
        A + "drawable": "@color/reaper_launcher_background"})
    ET.SubElement(adaptive, "foreground", {
        A + "drawable": "@mipmap/layered_app_icon"})
    ET.SubElement(adaptive, "monochrome", {
        A + "drawable": "@drawable/themed_app_icon"})
    for name in ("ic_launcher", "ic_launcher_round"):
        write_xml(res / "drawable" / (name + ".xml"), adaptive)
    colors = ET.Element("resources")
    ET.SubElement(colors, "color", {"name": "reaper_launcher_background"}).text = BACKGROUND
    write_xml(res / "values" / "reaper_brand_colors.xml", colors)

    # Preserve PNG resource types and all upstream density configurations.
    # BitmapFactory consumers cannot decode VectorDrawable XML.
    with tempfile.TemporaryDirectory(prefix="brand-render-", dir=out) as temporary:
        for index, variant in enumerate(spec["rasters"]):
            source = Path(temporary) / f"{index}.xml"
            write_xml(source, vector(mark, variant))
            command = [args.rasterizer, "--jvm-args=-Xmx256m -XX:ActiveProcessorCount=1",
                       str(source)]
            for bucket, scale in DENSITIES.items():
                png = res / f'{variant["type"]}-{bucket}' / (variant["name"] + ".png")
                png.parent.mkdir(parents=True, exist_ok=True)
                command += [str(png), str(scale), "0"]
            subprocess.run(command, check=True)
        source = Path(temporary) / "product-logo.xml"
        write_xml(source, vector(mark, {
            "size": 32, "crop": 72, "color": "#23C6B3", "background": "none"}))
        command = [args.rasterizer, "--jvm-args=-Xmx256m -XX:ActiveProcessorCount=1", str(source)]
        for scale in (1, 2, 3):
            png = out / "theme" / f"default_{scale * 100}_percent" / "reaper" / "product_logo_32.png"
            png.parent.mkdir(parents=True, exist_ok=True)
            command += [str(png), str(scale), "0"]
        subprocess.run(command, check=True)


if __name__ == "__main__":
    main()
