#!/usr/bin/env python3
"""Fail-closed Scan v2 source/manifest release preflight (no signing or publishing)."""
from __future__ import annotations
import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
TOOLS_NS = "{http://schemas.android.com/tools}"
BANNED_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.REQUEST_INSTALL_PACKAGES",
}
REQUIRED_VERSION = "2.0.0"
REQUIRED_CODE = 2


def find_number(gradle: str, field: str) -> int | None:
    match = re.search(r"(?m)^\s*" + re.escape(field) + r"\s*=\s*(\d+)\s*$", gradle)
    return int(match.group(1)) if match else None


def check(root: Path) -> dict:
    findings = []
    def record(name: str, valid: bool, detail: str):
        findings.append({"check": name, "status": "pass" if valid else "block", "detail": detail})

    gradle_path = root / "app/build.gradle.kts"
    manifest_path = root / "app/src/main/AndroidManifest.xml"
    db_path = root / "app/src/main/java/com/thiepn/scan/data/ScanDatabase.kt"
    roadmap_path = root / "docs/v2/MASTER_PLAN.md"
    for path in (gradle_path, manifest_path, db_path, roadmap_path):
        record("exists:" + path.relative_to(root).as_posix(), path.is_file(), "Required original repository file")
    if any(not x.is_file() for x in (gradle_path, manifest_path, db_path, roadmap_path)):
        return {"ready": False, "checks": findings}

    gradle = gradle_path.read_text(encoding="utf-8")
    database = db_path.read_text(encoding="utf-8")
    roadmap = roadmap_path.read_text(encoding="utf-8")

    version = re.search(r'(?m)^\s*versionName\s*=\s*"([^"]+)"\s*$', gradle)
    code = find_number(gradle, "versionCode")
    target = find_number(gradle, "targetSdk")
    minimum = find_number(gradle, "minSdk")
    app_id = re.search(r'(?m)^\s*applicationId\s*=\s*"([^"]+)"', gradle)
    record("version", bool(version and version.group(1) == REQUIRED_VERSION),
           f"versionName={version.group(1) if version else 'missing'}")
    record("versionCode", code is not None and code >= REQUIRED_CODE,
           f"versionCode={code}; confirm Play Console's maximum previously distributed versionCode before P40")
    record("applicationId", bool(app_id and app_id.group(1) == "com.thiepn.scan"),
           f"applicationId={app_id.group(1) if app_id else 'missing'}")
    record("sdk", target is not None and target >= 35 and minimum == 26,
           f"targetSdk={target} minSdk={minimum}; current Play policy must be rechecked at P40")
    record("schema", bool(re.search(r"version\s*=\s*22\b", database))
           and "fallbackToDestructiveMigration" not in database,
           "Keep Room v22 migration chain; no destructive fallback")
    record("roadmap", "**P39 — V2 release engineering and distribution**" in roadmap,
           "Authoritative P39 phase exists in MASTER_PLAN.md")

    try:
        manifest = ET.parse(manifest_path).getroot()
        permissions = manifest.findall("uses-permission") + manifest.findall("uses-permission-sdk-23")
        violations = []
        for permission in permissions:
            name = permission.attrib.get(ANDROID_NS + "name", "")
            removed = permission.attrib.get(TOOLS_NS + "node") == "remove"
            if name in BANNED_PERMISSIONS and not removed:
                violations.append(name)
        record("manifest-permissions", not violations,
               "Forbidden permissions not requested by Scan: " + (", ".join(violations) if violations else "none"))
        app = manifest.find("application")
        backup = app.attrib.get(ANDROID_NS + "allowBackup") if app is not None else None
        record("backup", backup == "false", f"allowBackup={backup}")
    except ET.ParseError as exc:
        record("manifest-xml", False, str(exc))
    return {"ready": all(c["status"] == "pass" for c in findings), "checks": findings}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, help="Optional JSON artifact path")
    args = parser.parse_args()
    report = check(args.project.resolve())
    result = json.dumps(report, indent=2, sort_keys=True)
    print(result)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(result + "\n", encoding="utf-8")
    return 0 if report["ready"] else 1


if __name__ == "__main__":
    sys.exit(main())
