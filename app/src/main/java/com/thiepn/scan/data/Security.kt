package com.thiepn.scan.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class PrivacyExportMode(val label: String) {
    STRIP_METADATA("Strip metadata"),
    STRIP_METADATA_AND_OCR("Strip metadata + searchable OCR")
}

data class DocumentSecuritySettings(
    val version: Int = 1,
    val vaultEnabled: Boolean = false,
    val lockOnBackground: Boolean = true,
    val hideMetadataWhenLocked: Boolean = true,
    val blockScreenshots: Boolean = true,
    val bestEffortSecureDelete: Boolean = true,
    val privacyExportMode: PrivacyExportMode =
        PrivacyExportMode.STRIP_METADATA_AND_OCR
) {
    fun normalized() = copy(version = 1)
}

object DocumentSecuritySettingsCodec {
    fun encode(value: DocumentSecuritySettings?): String? {
        val n = value?.normalized() ?: return null
        if (n == DocumentSecuritySettings()) return null
        return listOf(
            "1",
            if (n.vaultEnabled) "1" else "0",
            if (n.lockOnBackground) "1" else "0",
            if (n.hideMetadataWhenLocked) "1" else "0",
            if (n.blockScreenshots) "1" else "0",
            if (n.bestEffortSecureDelete) "1" else "0",
            n.privacyExportMode.name
        ).joinToString("\t")
    }

    fun decode(encoded: String?): DocumentSecuritySettings {
        if (encoded.isNullOrBlank()) return DocumentSecuritySettings()
        val p = encoded.split("\t")
        if (p.size != 7 || p[0] != "1") return DocumentSecuritySettings()
        return runCatching {
            DocumentSecuritySettings(
                vaultEnabled = p[1] == "1",
                lockOnBackground = p[2] == "1",
                hideMetadataWhenLocked = p[3] == "1",
                blockScreenshots = p[4] == "1",
                bestEffortSecureDelete = p[5] == "1",
                privacyExportMode = PrivacyExportMode.valueOf(p[6])
            )
        }.getOrDefault(DocumentSecuritySettings())
    }
}

data class DocumentIntegrityManifest(
    val version: Int = 1,
    val rootHash: String,
    val fileCount: Int,
    val totalBytes: Long,
    val createdAt: Long
)

object DocumentIntegrityManifestCodec {
    fun encode(value: DocumentIntegrityManifest?): String? =
        value?.let {
            listOf(
                "1",
                it.rootHash,
                it.fileCount.toString(),
                it.totalBytes.toString(),
                it.createdAt.toString()
            ).joinToString("\t")
        }

    fun decode(encoded: String?): DocumentIntegrityManifest? {
        if (encoded.isNullOrBlank()) return null
        val p = encoded.split("\t")
        if (p.size != 5 || p[0] != "1") return null
        return runCatching {
            DocumentIntegrityManifest(
                rootHash = p[1],
                fileCount = p[2].toInt(),
                totalBytes = p[3].toLong(),
                createdAt = p[4].toLong()
            )
        }.getOrNull()
    }
}

object DocumentIntegrity {
    fun compute(directory: File): DocumentIntegrityManifest {
        val files = directory.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".vaulttmp") &&
                !it.name.endsWith(".vaultbak") }
            .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
            .toList()
        val rootDigest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L

        files.forEach { file ->
            val relative = file.relativeTo(directory).invariantSeparatorsPath
            val hash = sha256(file)
            rootDigest.update(relative.toByteArray(Charsets.UTF_8))
            rootDigest.update(0)
            rootDigest.update(hash)
            rootDigest.update(0)
            totalBytes += file.length()
        }

        return DocumentIntegrityManifest(
            rootHash = rootDigest.digest().toHex(),
            fileCount = files.size,
            totalBytes = totalBytes,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}

data class VaultState(
    val protectedDocumentIds: Set<String> = emptySet(),
    val lockedDocumentIds: Set<String> = emptySet(),
    val integrityFailedDocumentIds: Set<String> = emptySet(),
    val busyDocumentIds: Set<String> = emptySet()
)

class SecurityVaultManager(
    private val context: Context,
    private val dao: DocumentDao,
    private val files: FileStore,
    private val scope: CoroutineScope
) {
    companion object {
        private const val PREFS = "scan_security_v2"
        private const val PREF_IDS = "protected_document_ids"
        private const val KEY_ALIAS = "scan_vault_aes_v2"
        private val MAGIC = byteArrayOf(
            0x53, 0x43, 0x41, 0x4E, 0x56, 0x32, 0x45, 0x31
        )
        private const val IV_SIZE = 12
        private const val BUFFER = 64 * 1024
    }

    private val prefs = context.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE
    )
    private val mutex = Mutex()
    private val random = SecureRandom()
    private val _state = MutableStateFlow(
        VaultState(
            protectedDocumentIds = registeredIds(),
            lockedDocumentIds = registeredIds()
        )
    )
    val state: StateFlow<VaultState> = _state.asStateFlow()

    fun isProtected(documentId: String): Boolean =
        documentId in _state.value.protectedDocumentIds

    fun isLocked(documentId: String): Boolean =
        documentId in _state.value.lockedDocumentIds

    fun isUnlocked(documentId: String): Boolean =
        !isProtected(documentId) || !isLocked(documentId)

    fun sealRegisteredOnColdStartAsync() {
        scope.launch(Dispatchers.IO) {
            sealRegisteredOnColdStart()
        }
    }

    suspend fun sealRegisteredOnColdStart() = mutex.withLock {
        val ids = registeredIds()
        _state.value = _state.value.copy(
            protectedDocumentIds = ids,
            lockedDocumentIds = ids,
            busyDocumentIds = ids
        )
        val failed = _state.value.integrityFailedDocumentIds.toMutableSet()
        ids.forEach { id ->
            runCatching {
                files.deletePlaintextExportsForDocument(id)
                val directory = files.documentDir(id)
                recoverInterruptedTransactions(directory)
                directory.walkTopDown()
                    .filter { it.isFile && !isSealed(it) }
                    .toList()
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        validatePlaintextIntegrityIfPresent(id, directory, failed)
                    }
                sealDirectory(directory)
            }
        }
        _state.value = _state.value.copy(
            integrityFailedDocumentIds = failed,
            busyDocumentIds = emptySet()
        )
    }

    suspend fun enable(documentId: String) = mutex.withLock {
        val directory = files.documentDir(documentId)
        val manifest = DocumentIntegrity.compute(directory)
        dao.setIntegrityManifest(
            documentId,
            DocumentIntegrityManifestCodec.encode(manifest),
            System.currentTimeMillis()
        )

        // Register before sealing so a process death mid-transaction is
        // recoverable on the next cold start.
        saveRegistered(registeredIds() + documentId)
        _state.value = _state.value.copy(
            protectedDocumentIds = registeredIds(),
            busyDocumentIds =
                _state.value.busyDocumentIds + documentId
        )

        try {
            sealDirectory(directory)
            _state.value = _state.value.copy(
                lockedDocumentIds =
                    _state.value.lockedDocumentIds + documentId
            )
        } catch (error: Throwable) {
            val recovered = runCatching {
                unsealDirectory(directory)
            }.isSuccess
            if (recovered) {
                saveRegistered(
                    registeredIds() - documentId
                )
                dao.setIntegrityManifest(
                    documentId,
                    null,
                    System.currentTimeMillis()
                )
                _state.value = _state.value.copy(
                    protectedDocumentIds =
                        registeredIds(),
                    lockedDocumentIds =
                        _state.value.lockedDocumentIds -
                            documentId
                )
            } else {
                // Fail closed if rollback itself cannot restore a
                // consistent plaintext state.
                _state.value = _state.value.copy(
                    lockedDocumentIds =
                        _state.value.lockedDocumentIds +
                            documentId
                )
            }
            throw error
        } finally {
            _state.value = _state.value.copy(
                busyDocumentIds =
                    _state.value.busyDocumentIds - documentId
            )
        }
    }

    suspend fun disable(documentId: String) = mutex.withLock {
        _state.value = _state.value.copy(
            busyDocumentIds =
                _state.value.busyDocumentIds + documentId
        )
        try {
            unsealDirectory(files.documentDir(documentId))
            saveRegistered(registeredIds() - documentId)
            _state.value = _state.value.copy(
                protectedDocumentIds = registeredIds(),
                lockedDocumentIds =
                    _state.value.lockedDocumentIds - documentId,
                integrityFailedDocumentIds =
                    _state.value.integrityFailedDocumentIds - documentId
            )
        } finally {
            _state.value = _state.value.copy(
                busyDocumentIds =
                    _state.value.busyDocumentIds - documentId
            )
        }
    }

    suspend fun unlock(documentId: String): Boolean = mutex.withLock {
        if (documentId !in registeredIds()) return true
        _state.value = _state.value.copy(
            busyDocumentIds =
                _state.value.busyDocumentIds + documentId
        )
        return try {
            val directory = files.documentDir(documentId)
            unsealDirectory(directory)
            val expected = dao.getDocument(documentId)
                ?.integrityManifest
                ?.let(DocumentIntegrityManifestCodec::decode)
            val actual = DocumentIntegrity.compute(directory)
            val valid = expected == null ||
                (
                    expected.rootHash == actual.rootHash &&
                    expected.fileCount == actual.fileCount
                )
            _state.value = _state.value.copy(
                lockedDocumentIds =
                    _state.value.lockedDocumentIds - documentId,
                integrityFailedDocumentIds =
                    if (valid) {
                        _state.value.integrityFailedDocumentIds - documentId
                    } else {
                        _state.value.integrityFailedDocumentIds + documentId
                    }
            )
            valid
        } finally {
            _state.value = _state.value.copy(
                busyDocumentIds =
                    _state.value.busyDocumentIds - documentId
            )
        }
    }

    suspend fun lock(
        documentId: String,
        purgeExports: Boolean = true
    ) = mutex.withLock {
        if (documentId !in registeredIds()) return
        if (documentId in _state.value.lockedDocumentIds) return
        _state.value = _state.value.copy(
            busyDocumentIds =
                _state.value.busyDocumentIds + documentId
        )
        val directory = files.documentDir(documentId)
        try {
            if (purgeExports) {
                files.deletePlaintextExportsForDocument(
                    documentId
                )
            }
            if (directory.exists()) {
                val manifest = DocumentIntegrity.compute(directory)
                dao.setIntegrityManifest(
                    documentId,
                    DocumentIntegrityManifestCodec.encode(manifest),
                    System.currentTimeMillis()
                )
                sealDirectory(directory)
            }
            _state.value = _state.value.copy(
                lockedDocumentIds =
                    _state.value.lockedDocumentIds + documentId
            )
        } catch (error: Throwable) {
            val recovered = runCatching {
                unsealDirectory(directory)
            }.isSuccess
            if (!recovered) {
                // A partial seal must never be exposed as an unlocked
                // document. Treat rollback failure as locked/fail-closed.
                _state.value = _state.value.copy(
                    lockedDocumentIds =
                        _state.value.lockedDocumentIds +
                            documentId
                )
            }
            throw error
        } finally {
            _state.value = _state.value.copy(
                busyDocumentIds =
                    _state.value.busyDocumentIds - documentId
            )
        }
    }

    fun lockAllAsync() {
        scope.launch(Dispatchers.IO) {
            lockAll()
        }
    }

    fun lockOnBackgroundAsync() {
        scope.launch(Dispatchers.IO) {
            registeredIds().forEach { id ->
                val settings = dao.getDocument(id)
                    ?.securityRecipe
                    ?.let(
                        DocumentSecuritySettingsCodec::decode
                    )
                    ?: DocumentSecuritySettings()
                if (settings.lockOnBackground) {
                    runCatching {
                        lock(
                            documentId = id,
                            purgeExports = false
                        )
                    }
                }
            }
        }
    }

    suspend fun lockAll() {
        registeredIds().forEach { id ->
            runCatching { lock(id) }
        }
    }

    fun forgetDocument(documentId: String) {
        saveRegistered(registeredIds() - documentId)
        _state.value = _state.value.copy(
            protectedDocumentIds = registeredIds(),
            lockedDocumentIds =
                _state.value.lockedDocumentIds - documentId,
            integrityFailedDocumentIds =
                _state.value.integrityFailedDocumentIds - documentId,
            busyDocumentIds =
                _state.value.busyDocumentIds - documentId
        )
    }

    fun bestEffortSecureDelete(documentId: String) {
        val directory = files.documentDir(documentId)
        directory.walkBottomUp()
            .filter { it.isFile }
            .forEach(::bestEffortOverwrite)
        directory.deleteRecursively()
        forgetDocument(documentId)
    }

    private suspend fun validatePlaintextIntegrityIfPresent(
        documentId: String,
        directory: File,
        failed: MutableSet<String>
    ) {
        val expected = runCatching {
            dao.getDocument(documentId)
                ?.integrityManifest
        }.getOrNull()
            ?.let(DocumentIntegrityManifestCodec::decode)
            ?: return
        val actual = DocumentIntegrity.compute(directory)
        if (
            expected.rootHash != actual.rootHash ||
            expected.fileCount != actual.fileCount
        ) {
            failed += documentId
        }
    }

    private fun registeredIds(): Set<String> =
        prefs.getStringSet(PREF_IDS, emptySet())
            ?.toSet()
            .orEmpty()

    private fun saveRegistered(ids: Set<String>) {
        check(
            prefs.edit()
                .putStringSet(PREF_IDS, ids)
                .commit()
        ) {
            "Could not persist secure-vault registry"
        }
    }

    private fun recoverInterruptedTransactions(
        directory: File
    ) {
        if (!directory.exists()) return

        directory.walkBottomUp()
            .filter {
                it.isFile &&
                    it.name.endsWith(".vaultbak")
            }
            .forEach { backup ->
                val original = File(
                    backup.parentFile,
                    backup.name.removeSuffix(
                        ".vaultbak"
                    )
                )
                if (original.exists()) {
                    backup.delete()
                } else {
                    backup.renameTo(original)
                }
            }

        directory.walkBottomUp()
            .filter {
                it.isFile &&
                    it.name.endsWith(".vaulttmp")
            }
            .forEach { temporary ->
                val original = File(
                    temporary.parentFile,
                    temporary.name.removeSuffix(
                        ".vaulttmp"
                    )
                )
                if (original.exists()) {
                    temporary.delete()
                }
            }
    }

    private fun sealDirectory(directory: File) {
        if (!directory.exists()) return
        directory.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".vaulttmp") &&
                !it.name.endsWith(".vaultbak") }
            .forEach { sealFile(it) }
    }

    private fun unsealDirectory(directory: File) {
        if (!directory.exists()) return
        directory.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".vaulttmp") &&
                !it.name.endsWith(".vaultbak") }
            .forEach { unsealFile(it) }
    }

    private fun sealFile(file: File) {
        if (isSealed(file)) return
        val temp = File(file.parentFile, file.name + ".vaulttmp")
        temp.delete()
        val iv = ByteArray(IV_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.ENCRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, iv)
            )
            updateAAD(MAGIC)
        }

        try {
            DataOutputStream(
                BufferedOutputStream(FileOutputStream(temp))
            ).use { raw ->
                raw.write(MAGIC)
                raw.writeByte(iv.size)
                raw.write(iv)
                CipherOutputStream(raw, cipher).use { encrypted ->
                    BufferedInputStream(
                        FileInputStream(file)
                    ).use { input ->
                        input.copyTo(encrypted, BUFFER)
                    }
                }
            }
            replaceAtomically(temp, file)
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    private fun unsealFile(file: File) {
        if (!isSealed(file)) return
        val temp = File(file.parentFile, file.name + ".vaulttmp")
        temp.delete()
        try {
            DataInputStream(
                BufferedInputStream(FileInputStream(file))
            ).use { raw ->
                val magic = ByteArray(MAGIC.size)
                raw.readFully(magic)
                require(magic.contentEquals(MAGIC)) {
                    "Invalid vault file"
                }
                val ivSize = raw.readUnsignedByte()
                require(ivSize in 12..16) {
                    "Invalid vault IV"
                }
                val iv = ByteArray(ivSize)
                raw.readFully(iv)
                val cipher = Cipher.getInstance(
                    "AES/GCM/NoPadding"
                ).apply {
                    init(
                        Cipher.DECRYPT_MODE,
                        secretKey(),
                        GCMParameterSpec(128, iv)
                    )
                    updateAAD(MAGIC)
                }
                CipherInputStream(raw, cipher).use { decrypted ->
                    BufferedOutputStream(
                        FileOutputStream(temp)
                    ).use { output ->
                        decrypted.copyTo(output, BUFFER)
                    }
                }
            }
            replaceAtomically(temp, file)
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    private fun isSealed(file: File): Boolean {
        if (!file.isFile || file.length() < MAGIC.size + 2) {
            return false
        }
        return runCatching {
            FileInputStream(file).use { input ->
                val head = ByteArray(MAGIC.size)
                input.read(head) == head.size &&
                    head.contentEquals(MAGIC)
            }
        }.getOrDefault(false)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(
            "AndroidKeyStore"
        ).apply {
            load(null)
        }
        val existing = store.getKey(
            KEY_ALIAS,
            null
        ) as? SecretKey
        if (existing != null) return existing

        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or
                        KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(
                        KeyProperties.BLOCK_MODE_GCM
                    )
                    .setEncryptionPaddings(
                        KeyProperties.ENCRYPTION_PADDING_NONE
                    )
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    }

    private fun replaceAtomically(
        temporary: File,
        destination: File
    ) {
        val backup = File(
            destination.parentFile,
            destination.name + ".vaultbak"
        )
        backup.delete()
        require(destination.renameTo(backup)) {
            "Could not stage vault file"
        }
        if (!temporary.renameTo(destination)) {
            backup.renameTo(destination)
            temporary.delete()
            error("Could not replace vault file")
        }
        backup.delete()
    }

    private fun bestEffortOverwrite(file: File) {
        if (!file.isFile) return
        runCatching {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                var remaining = raf.length()
                raf.seek(0)
                val buffer = ByteArray(BUFFER)
                while (remaining > 0) {
                    random.nextBytes(buffer)
                    val count = minOf(
                        remaining,
                        buffer.size.toLong()
                    ).toInt()
                    raf.write(buffer, 0, count)
                    remaining -= count
                }
                raf.fd.sync()
            }
        }
        file.delete()
    }
}


enum class SecurityAuditSeverity {
    PASS,
    WARNING,
    ERROR,
    INFO
}

data class SecurityAuditIssue(
    val severity: SecurityAuditSeverity,
    val code: String,
    val message: String
)

data class SecurityAuditReport(
    val issues: List<SecurityAuditIssue>
) {
    val errorCount: Int
        get() = issues.count {
            it.severity == SecurityAuditSeverity.ERROR
        }
    val warningCount: Int
        get() = issues.count {
            it.severity == SecurityAuditSeverity.WARNING
        }
}
