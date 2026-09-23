package com.thiepn.scan.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.thiepn.scan.data.AccessibilityMode
import com.thiepn.scan.data.ComplianceReport
import com.thiepn.scan.data.ComplianceSeverity
import com.thiepn.scan.data.ComplianceSettings
import com.thiepn.scan.data.ExportOptimization
import com.thiepn.scan.data.PdfSignatureReport
import com.thiepn.scan.data.PdfStandard

@Composable
fun StandardsTools(
    settings: ComplianceSettings,
    enabled: Boolean,
    onSettings: () -> Unit,
    onExport: () -> Unit,
    onSign: () -> Unit,
    onValidate: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Standards, accessibility & signatures",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                settings.pdfStandard.label + " · " +
                    settings.accessibilityMode.label + " · " +
                    settings.documentLanguage + " · " +
                    settings.optimization.label,
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onExport,
                    enabled = enabled
                ) { Text("Standards export") }
                OutlinedButton(
                    onClick = onSign,
                    enabled = enabled
                ) { Text("Digitally sign") }
                OutlinedButton(
                    onClick = onValidate,
                    enabled = enabled
                ) { Text("Validate PDF") }
                OutlinedButton(
                    onClick = onSettings,
                    enabled = enabled
                ) { Text("Settings") }
            }
        }
    }
}

@Composable
fun ComplianceSettingsDialog(
    initial: ComplianceSettings,
    onDismiss: () -> Unit,
    onSave: (ComplianceSettings) -> Unit
) {
    var standard by remember(initial) {
        mutableStateOf(initial.pdfStandard)
    }
    var accessibility by remember(initial) {
        mutableStateOf(initial.accessibilityMode)
    }
    var language by remember(initial) {
        mutableStateOf(initial.documentLanguage)
    }
    var optimization by remember(initial) {
        mutableStateOf(initial.optimization)
    }
    var validateAfterExport by remember(initial) {
        mutableStateOf(initial.validateAfterExport)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PDF standards & accessibility") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 680.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "PDF/A exports are rebuilt from Scan page images/OCR and cannot use password encryption.",
                    style = MaterialTheme.typography.bodySmall
                )

                Text(
                    "PDF standard",
                    style = MaterialTheme.typography.titleSmall
                )
                PdfStandard.entries.forEach { option ->
                    FilterChip(
                        selected = standard == option,
                        onClick = { standard = option },
                        label = { Text(option.label) }
                    )
                }

                Text(
                    "Accessibility",
                    style = MaterialTheme.typography.titleSmall
                )
                AccessibilityMode.entries.forEach { option ->
                    FilterChip(
                        selected = accessibility == option,
                        onClick = { accessibility = option },
                        label = { Text(option.label) }
                    )
                }
                if (
                    accessibility ==
                    AccessibilityMode.TAGGED_OCR
                ) {
                    OutlinedTextField(
                        value = language,
                        onValueChange = {
                            language = it.take(35)
                        },
                        label = {
                            Text("Document language (BCP 47)")
                        },
                        supportingText = {
                            Text("Examples: en, de-DE, ko-KR, fr")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                Text(
                    "Optimization",
                    style = MaterialTheme.typography.titleSmall
                )
                ExportOptimization.entries.forEach { option ->
                    FilterChip(
                        selected = optimization == option,
                        onClick = { optimization = option },
                        label = { Text(option.label) }
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(
                        8.dp
                    )
                ) {
                    Checkbox(
                        checked = validateAfterExport,
                        onCheckedChange = {
                            validateAfterExport = it
                        }
                    )
                    Text(
                        "Validate after export",
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        ComplianceSettings(
                            pdfStandard = standard,
                            accessibilityMode = accessibility,
                            documentLanguage = language,
                            optimization = optimization,
                            validateAfterExport =
                                validateAfterExport
                        ).normalized()
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
fun CertificateSigningDialog(
    onDismiss: () -> Unit,
    onSign: (
        password: CharArray,
        reason: String,
        location: String
    ) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var reason by remember {
        mutableStateOf("Approved document")
    }
    var location by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Digitally sign PDF") },
        text = {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "The PKCS#12 password is used only for this signing operation and is not saved.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it.take(256)
                    },
                    label = { Text("Certificate password") },
                    visualTransformation =
                        PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = {
                        reason = it.take(300)
                    },
                    label = { Text("Reason") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = {
                        location = it.take(300)
                    },
                    label = { Text("Location (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chars = password.toCharArray()
                    password = ""
                    onSign(
                        chars,
                        reason.trim(),
                        location.trim()
                    )
                }
            ) { Text("Sign") }
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
fun PdfValidationDialog(
    title: String,
    compliance: ComplianceReport,
    signatures: PdfSignatureReport? = null,
    onDismiss: () -> Unit,
    onShare: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 700.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    if (compliance.passed) {
                        "Scan's configured checks passed"
                    } else {
                        compliance.errorCount.toString() +
                            " compliance error" +
                            (if (compliance.errorCount == 1) {
                                ""
                            } else {
                                "s"
                            })
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (compliance.passed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                Text(
                    compliance.standard.label + " · " +
                        compliance.accessibilityMode.label,
                    style = MaterialTheme.typography.bodySmall
                )

                compliance.issues.forEach { issue ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(10.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                issue.severity.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = when (
                                    issue.severity
                                ) {
                                    ComplianceSeverity.ERROR ->
                                        MaterialTheme.colorScheme.error
                                    ComplianceSeverity.WARNING ->
                                        MaterialTheme.colorScheme.tertiary
                                    ComplianceSeverity.INFO ->
                                        MaterialTheme.colorScheme.primary
                                }
                            )
                            Text(
                                issue.message,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (signatures != null) {
                    Text(
                        "Digital signatures",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (!signatures.hasSignatures) {
                        Text("No digital signatures found.")
                    }
                    signatures.signatures.forEach { signature ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(10.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    signature.name.ifBlank {
                                        signature.subject.ifBlank {
                                            "Signature " +
                                                (signature.index + 1)
                                        }
                                    },
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    signature.message,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Cryptographic: " +
                                        yesNo(
                                            signature.cryptographicallyValid
                                        ) +
                                        " · Whole document: " +
                                        yesNo(
                                            signature.coversWholeDocument
                                        ),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Certificate valid at signing: " +
                                        yesNo(
                                            signature.certificateValidAtSigning
                                        ) +
                                        " · Device trusted: " +
                                        yesNo(
                                            signature.trustedByDevice
                                        ),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (signature.issuer.isNotBlank()) {
                                    Text(
                                        "Issuer: " +
                                            signature.issuer,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (onShare != null) {
                TextButton(onClick = onShare) {
                    Text("Share PDF")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

private fun yesNo(value: Boolean): String =
    if (value) "yes" else "no"
