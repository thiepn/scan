#!/usr/bin/env python3
"""P39 source-gate tests; use a disposable synthetic checkout, never production files."""
from __future__ import annotations
import importlib.util
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).with_name("check-v2-release-contract.py")
spec = importlib.util.spec_from_file_location("scan_v2_release_contract", MODULE)
assert spec is not None and spec.loader is not None
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class V2ReleaseContractTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="scan-v2-contract-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.gradle = "app/build.gradle.kts"
        self.manifest = "app/src/main/AndroidManifest.xml"
        self.database = "app/src/main/java/com/thiepn/scan/data/ScanDatabase.kt"
        self.roadmap = "docs/v2/MASTER_PLAN.md"
        self.put(self.gradle, '''
android { defaultConfig {
  applicationId = "com.thiepn.scan"
  minSdk = 26
  targetSdk = 36
  versionCode = 2
  versionName = "2.0.0"
}}
''')
        self.put(self.manifest, '''<manifest
xmlns:android="http://schemas.android.com/apk/res/android"
xmlns:tools="http://schemas.android.com/tools">
<uses-permission android:name="android.permission.INTERNET" tools:node="remove"/>
<application android:allowBackup="false"/>
</manifest>''')
        self.put(self.database, "class ScanDatabase { version = 22 }")
        self.put(self.roadmap, "**P39 — V2 release engineering and distribution**")

    def put(self, path, content):
        destination = self.root / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(content, encoding="utf-8")

    def read(self, path):
        return (self.root / path).read_text(encoding="utf-8")

    def findings(self):
        return module.check(self.root)

    def blocked(self, key):
        report = self.findings()
        self.assertFalse(report["ready"])
        self.assertTrue(any(x["check"] == key and x["status"] == "block"
                            for x in report["checks"]), report)

    def test_approved_baseline(self):
        self.assertTrue(self.findings()["ready"])

    def test_old_version_blocked(self):
        self.put(self.gradle, self.read(self.gradle).replace('versionName = "2.0.0"', 'versionName = "1.0.0"'))
        self.blocked("version")

    def test_non_monotonic_version_code_blocked(self):
        self.put(self.gradle, self.read(self.gradle).replace("versionCode = 2", "versionCode = 1"))
        self.blocked("versionCode")

    def test_wrong_app_id_blocked(self):
        self.put(self.gradle, self.read(self.gradle).replace("com.thiepn.scan", "com.thiepn.scan.v2"))
        self.blocked("applicationId")

    def test_broad_storage_blocked(self):
        self.put(self.manifest, self.read(self.manifest).replace("<application", 
                  '<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"/><application'))
        self.blocked("manifest-permissions")

    def test_active_network_permission_blocked(self):
        self.put(self.manifest, self.read(self.manifest).replace(' tools:node="remove"', ""))
        self.blocked("manifest-permissions")

    def test_android_backup_blocked(self):
        self.put(self.manifest, self.read(self.manifest).replace('allowBackup="false"', 'allowBackup="true"'))
        self.blocked("backup")

    def test_destructive_migration_blocked(self):
        self.put(self.database, self.read(self.database) + "\nfallbackToDestructiveMigration()")
        self.blocked("schema")

    def test_unknown_phase_blocked(self):
        self.put(self.roadmap, "# P39 not confirmed")
        self.blocked("roadmap")

    def test_missing_required_source_blocked(self):
        (self.root / self.manifest).unlink()
        self.blocked("exists:" + self.manifest)

    def test_missing_sdk_contract_blocked(self):
        self.put(self.gradle, self.read(self.gradle).replace("minSdk = 26", "minSdk = 24"))
        self.blocked("sdk")


if __name__ == "__main__":
    unittest.main()
