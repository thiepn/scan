package com.thiepn.scan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.thiepn.scan.data.DocumentSecuritySettings
import com.thiepn.scan.data.PrivacyExportMode
import com.thiepn.scan.data.SecurityAuditReport
import com.thiepn.scan.data.SecurityAuditSeverity

@Composable
fun SecurityTools(
    settings: DocumentSecuritySettings,
    locked: Boolean,
    integrityFailed: Boolean,
    enabled: Boolean,
    onSettings: () -> Unit,
    onLock: () -> Unit,
    onBackup: () -> Unit,
    onPrivacyExport: () -> Unit,
    onAudit: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Security & privacy",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                buildString {
                    append(
                        if (settings.vaultEnabled) {
                            if (locked) "Vault locked"
                            else "Vault unlocked"
                        } else {
                            "Vault disabled"
                        }
                    )
                    append(" · ")
                    append(settings.privacyExportMode.label)
                    if (integrityFailed) {
                        append(" · Integrity warning")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (integrityFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSettings,
                    enabled = enabled
                ) { Text("Settings") }
                OutlinedButton(
                    onClick = onBackup,
                    enabled = enabled && !locked
                ) { Text("Encrypted backup") }
                OutlinedButton(
                    onClick = onPrivacyExport,
                    enabled = enabled && !locked
                ) { Text("Privacy export") }
                OutlinedButton(
                    onClick = onAudit,
                    enabled = enabled
                ) { Text("Audit") }
            }
            if (settings.vaultEnabled && !locked) {
                OutlinedButton(
                    onClick = onLock,
                    enabled = enabled
                ) { Text("Lock now") }
            }
        }
    }
}

@Composable
fun SecuritySettingsDialog(
    initial: DocumentSecuritySettings,
    onDismiss: () -> Unit,
    onSave: (DocumentSecuritySettings) -> Unit
) {
    var vault by remember(initial) {
        mutableStateOf(initial.vaultEnabled)
    }
    var lockBackground by remember(initial) {
        mutableStateOf(initial.lockOnBackground)
    }
    var hideMetadata by remember(initial) {
        mutableStateOf(initial.hideMetadataWhenLocked)
    }
    var blockScreenshots by remember(initial) {
        mutableStateOf(initial.blockScreenshots)
    }
    var secureDelete by remember(initial) {
        mutableStateOf(initial.bestEffortSecureDelete)
    }
    var privacyMode by remember(initial) {
        mutableStateOf(initial.privacyExportMode)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Security & privacy") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 680.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Vault encryption protects document image/PDF files when locked. The live Room database, including OCR metadata, is not encrypted by this vault.",
                    style = MaterialTheme.typography.bodySmall
                )
                SecurityToggle(
                    checked = vault,
                    onCheckedChange = { vault = it },
                    title = "Encrypted vault",
                    detail = "AES-256-GCM seal document binaries when locked."
                )
                SecurityToggle(
                    checked = lockBackground,
                    onCheckedChange = { lockBackground = it },
                    title = "Lock on background",
                    detail = "Re-seal this vault when Scan leaves the foreground.",
                    enabled = vault
                )
                SecurityToggle(
                    checked = hideMetadata,
                    onCheckedChange = { hideMetadata = it },
                    title = "Hide locked metadata",
                    detail = "Use a generic title and hide previews while locked.",
                    enabled = vault
                )
                SecurityToggle(
                    checked = blockScreenshots,
                    onCheckedChange = { blockScreenshots = it },
                    title = "Block screenshots",
                    detail = "Use Android FLAG_SECURE while this protected document is open."
                )
                SecurityToggle(
                    checked = secureDelete,
                    onCheckedChange = { secureDelete = it },
                    title = "Best-effort secure deletion",
                    detail = "Overwrite file bytes before deletion. Flash wear-leveling means physical erasure cannot be guaranteed."
                )

                Text(
                    "Privacy export",
                    style = MaterialTheme.typography.titleSmall
                )
                PrivacyExportMode.entries.forEach { option ->
                    FilterChip(
                        selected = privacyMode == option,
                        onClick = { privacyMode = option },
                        label = { Text(option.label) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        DocumentSecuritySettings(
                            vaultEnabled = vault,
                            lockOnBackground = lockBackground,
                            hideMetadataWhenLocked = hideMetadata,
                            blockScreenshots = blockScreenshots,
                            bestEffortSecureDelete = secureDelete,
                            privacyExportMode = privacyMode
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SecurityToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    detail: String,
    enabled: Boolean = true
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
        Column(Modifier.padding(start = 6.dp, top = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun CreateSecureBackupDialog(
    onDismiss: () -> Unit,
    onCreate: (CharArray) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = password.length >= 8 && password == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Encrypted backup") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "The backup keeps editable document/page data and files in an AES-256-GCM encrypted .scanbak archive. The password cannot be recovered.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(256) },
                    label = { Text("Backup password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.take(256) },
                    label = { Text("Confirm password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                if (password.isNotEmpty() && password.length < 8) {
                    Text(
                        "Use at least 8 characters.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chars = password.toCharArray()
                    password = ""
                    confirm = ""
                    onCreate(chars)
                },
                enabled = valid
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    password = ""
                    confirm = ""
                    onDismiss()
                }
            ) { Text("Cancel") }
        }
    )
}

@Composable
fun RestoreSecureBackupDialog(
    onDismiss: () -> Unit,
    onRestore: (CharArray) -> Unit
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore encrypted backup") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Enter the password used when the .scanbak file was created.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(256) },
                    label = { Text("Backup password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chars = password.toCharArray()
                    password = ""
                    onRestore(chars)
                },
                enabled = password.isNotBlank()
            ) { Text("Restore") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    password = ""
                    onDismiss()
                }
            ) { Text("Cancel") }
        }
    )
}

@Composable
fun SecurityAuditDialog(
    report: SecurityAuditReport,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Security audit") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 650.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    report.errorCount.toString() +
                        " errors · " +
                        report.warningCount +
                        " warnings",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (report.errorCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                report.issues.forEach { issue ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                issue.severity.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = when (issue.severity) {
                                    SecurityAuditSeverity.ERROR ->
                                        MaterialTheme.colorScheme.error
                                    SecurityAuditSeverity.WARNING ->
                                        MaterialTheme.colorScheme.tertiary
                                    SecurityAuditSeverity.PASS ->
                                        MaterialTheme.colorScheme.primary
                                    SecurityAuditSeverity.INFO ->
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Text(
                                issue.message,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun VaultLockedScreen(
    contentPadding: PaddingValues,
    busy: Boolean,
    integrityWarning: Boolean,
    onUnlock: () -> Unit,
    onBack: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        Card(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    if (integrityWarning) {
                        Icons.Default.Security
                    } else {
                        Icons.Default.Lock
                    },
                    contentDescription = null
                )
                Text(
                    if (integrityWarning) {
                        "Secure document · integrity warning"
                    } else {
                        "Secure document locked"
                    },
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    "Authenticate with biometrics or your device credential to decrypt this document.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = onUnlock,
                    enabled = !busy
                ) { Text(if (busy) "Unlocking…" else "Unlock") }
                TextButton(
                    onClick = onBack,
                    enabled = !busy
                ) { Text("Back") }
            }
        }
    }
}
