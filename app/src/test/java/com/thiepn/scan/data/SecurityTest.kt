package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SecurityTest {
    @Test
    fun securitySettingsCodecRoundTripsVaultPolicy() {
        val settings = DocumentSecuritySettings(
            vaultEnabled = true,
            lockOnBackground = false,
            hideMetadataWhenLocked = true,
            blockScreenshots = true,
            bestEffortSecureDelete = false,
            privacyExportMode =
                PrivacyExportMode.STRIP_METADATA
        )
        assertEquals(
            settings,
            DocumentSecuritySettingsCodec.decode(
                DocumentSecuritySettingsCodec.encode(
                    settings
                )
            )
        )
    }

    @Test
    fun defaultSecuritySettingsUseCompactNullEncoding() {
        assertNull(
            DocumentSecuritySettingsCodec.encode(
                DocumentSecuritySettings()
            )
        )
    }

    @Test
    fun integrityManifestChangesWhenFileChanges() {
        val directory = createTempDirectory(
            "scan-integrity"
        ).toFile()
        try {
            File(directory, "a.txt").writeText("one")
            File(directory, "nested").mkdirs()
            File(directory, "nested/b.txt")
                .writeText("two")
            val first = DocumentIntegrity.compute(
                directory
            )
            val repeated = DocumentIntegrity.compute(
                directory
            )
            assertEquals(
                first.rootHash,
                repeated.rootHash
            )
            assertEquals(2, first.fileCount)

            File(directory, "a.txt").writeText(
                "changed"
            )
            val changed = DocumentIntegrity.compute(
                directory
            )
            assertNotEquals(
                first.rootHash,
                changed.rootHash
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun integrityCodecPreservesStoredBaseline() {
        val manifest = DocumentIntegrityManifest(
            rootHash = "abc123",
            fileCount = 4,
            totalBytes = 100L,
            createdAt = 99L
        )
        assertEquals(
            manifest,
            DocumentIntegrityManifestCodec.decode(
                DocumentIntegrityManifestCodec.encode(
                    manifest
                )
            )
        )
    }

    @Test
    fun securityAuditCountsOnlyWarningsAndErrors() {
        val report = SecurityAuditReport(
            listOf(
                SecurityAuditIssue(
                    SecurityAuditSeverity.PASS,
                    "a",
                    "ok"
                ),
                SecurityAuditIssue(
                    SecurityAuditSeverity.WARNING,
                    "b",
                    "warning"
                ),
                SecurityAuditIssue(
                    SecurityAuditSeverity.ERROR,
                    "c",
                    "error"
                ),
                SecurityAuditIssue(
                    SecurityAuditSeverity.INFO,
                    "d",
                    "info"
                )
            )
        )
        assertEquals(1, report.warningCount)
        assertEquals(1, report.errorCount)
    }
}
