package com.thiepn.scan.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class SecureBackupResult(
    val file: File,
    val pageCount: Int,
    val fileCount: Int
)

class SecureDocumentBackup(
    private val dao: DocumentDao,
    private val files: FileStore,
    private val searchIndex: OcrSearchIndex
) {
    companion object {
        private val MAGIC = byteArrayOf(
            0x53, 0x43, 0x41, 0x4E, 0x42, 0x4B, 0x50, 0x32
        )
        private const val ITERATIONS = 240_000
        private const val SALT_SIZE = 16
        private const val IV_SIZE = 12
        private const val KEY_BITS = 256
    }

    private val random = SecureRandom()

    suspend fun create(
        documentId: String,
        password: CharArray
    ): SecureBackupResult {
        require(password.size >= 8) {
            "Backup password must be at least 8 characters"
        }
        val document = dao.getDocument(documentId)
            ?: throw IllegalArgumentException("Document not found")
        val pages = dao.getAllDocumentPages(documentId)
        val fields = dao.getDocumentFields(documentId)
        val directory = files.documentDir(documentId)
        val destination = files.secureBackupFile(
            documentId,
            document.title
        )
        val temporary = files.temporaryExport(destination)
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val iv = ByteArray(IV_SIZE).also(random::nextBytes)
        val key = deriveKey(password, salt, ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.ENCRYPT_MODE,
                key,
                GCMParameterSpec(128, iv)
            )
            updateAAD(MAGIC)
        }

        val assetFiles = directory.walkTopDown()
            .filter { it.isFile }
            .sortedBy {
                it.relativeTo(directory).invariantSeparatorsPath
            }
            .toList()

        try {
            DataOutputStream(
                BufferedOutputStream(FileOutputStream(temporary))
            ).use { raw ->
                raw.write(MAGIC)
                raw.writeInt(ITERATIONS)
                raw.writeInt(salt.size)
                raw.write(salt)
                raw.writeInt(iv.size)
                raw.write(iv)
                CipherOutputStream(raw, cipher).use { encrypted ->
                    ZipOutputStream(
                        BufferedOutputStream(encrypted)
                    ).use { zip ->
                        val manifest = createManifest(
                            document,
                            pages,
                            fields,
                            directory
                        )
                        zip.putNextEntry(
                            ZipEntry("manifest.json")
                        )
                        zip.write(
                            manifest.toString()
                                .toByteArray(Charsets.UTF_8)
                        )
                        zip.closeEntry()

                        assetFiles.forEach { file ->
                            val relative = file.relativeTo(directory)
                                .invariantSeparatorsPath
                            zip.putNextEntry(
                                ZipEntry("files/$relative")
                            )
                            file.inputStream().buffered().use {
                                input ->
                                input.copyTo(zip)
                            }
                            zip.closeEntry()
                        }
                    }
                }
            }
            files.commitGeneratedExport(
                temporary,
                destination
            )
            return SecureBackupResult(
                destination,
                pages.size,
                assetFiles.size
            )
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            password.fill('\u0000')
        }
    }

    suspend fun restore(
        source: File,
        password: CharArray
    ): String {
        require(source.isFile) { "Backup file is unavailable" }
        val staging = files.temporaryDirectory(
            "scan-restore"
        )
        try {
            decryptArchive(source, staging, password)
            val manifestFile = File(staging, "manifest.json")
            require(manifestFile.isFile) {
                "Backup manifest is missing"
            }
            val manifest = JSONObject(
                manifestFile.readText()
            )
            require(manifest.optInt("version") == 2) {
                "Unsupported backup version"
            }

            val newId = UUID.randomUUID().toString()
            val newDir = files.documentDir(newId)
            val stagedFiles = File(staging, "files")
            if (stagedFiles.exists()) {
                copyTree(stagedFiles, newDir)
            }

            val sourceDocument =
                documentFromJson(
                    manifest.getJSONObject("document")
                )
            val restoredSecurity =
                DocumentSecuritySettingsCodec.decode(
                    sourceDocument.securityRecipe
                ).copy(vaultEnabled = false)
            val pdfRelative =
                manifest.optString(
                    "pdfRelativePath",
                    ""
                ).takeIf { it.isNotBlank() }

            val document = sourceDocument.copy(
                id = newId,
                title = sourceDocument.title +
                    " (Restored)",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                pdfPath = pdfRelative?.let {
                    File(newDir, it).absolutePath
                },
                favorite = false,
                archived = false,
                trashedAt = null,
                processing = false,
                folderId = null,
                securityRecipe =
                    DocumentSecuritySettingsCodec.encode(
                        restoredSecurity
                    ),
                integrityManifest = null
            )

            val pageArray = manifest.getJSONArray("pages")
            val oldToNew = linkedMapOf<String, String>()
            for (i in 0 until pageArray.length()) {
                val oldId = pageArray
                    .getJSONObject(i)
                    .getString("id")
                oldToNew[oldId] =
                    UUID.randomUUID().toString()
            }

            val pages = buildList {
                for (i in 0 until pageArray.length()) {
                    val json = pageArray.getJSONObject(i)
                    val page = pageFromJson(
                        json,
                        newId,
                        newDir,
                        oldToNew
                    )
                    add(page)
                }
            }
            val fields = manifest
                .optJSONArray("fields")
                ?.let { array ->
                    buildList {
                        for (i in 0 until array.length()) {
                            val item = array.getJSONObject(i)
                            add(
                                DocumentFieldEntity(
                                    documentId = newId,
                                    fieldKey =
                                        item.getString("fieldKey"),
                                    label =
                                        item.getString("label"),
                                    value =
                                        item.getString("value"),
                                    confidence =
                                        item.optDouble(
                                            "confidence",
                                            0.0
                                        ).toFloat(),
                                    source =
                                        item.optString(
                                            "source",
                                            "BACKUP"
                                        )
                                )
                            )
                        }
                    }
                }
                .orEmpty()

            dao.restoreSecurityBackup(
                document,
                pages,
                fields
            )
            pages.filter { !it.deleted }
                .forEach { page ->
                    if (page.ocrText.isNotBlank()) {
                        searchIndex.upsertPage(
                            newId,
                            page.id,
                            page.ocrText
                        )
                    }
                }
            return newId
        } finally {
            password.fill('\u0000')
            staging.deleteRecursively()
        }
    }

    private fun decryptArchive(
        source: File,
        staging: File,
        password: CharArray
    ) {
        DataInputStream(
            BufferedInputStream(FileInputStream(source))
        ).use { raw ->
            val magic = ByteArray(MAGIC.size)
            raw.readFully(magic)
            require(magic.contentEquals(MAGIC)) {
                "This is not a Scan secure backup"
            }
            val iterations = raw.readInt()
            require(iterations in 100_000..1_000_000) {
                "Invalid backup key settings"
            }
            val saltSize = raw.readInt()
            require(saltSize in 16..64) {
                "Invalid backup salt"
            }
            val salt = ByteArray(saltSize)
            raw.readFully(salt)
            val ivSize = raw.readInt()
            require(ivSize in 12..16) {
                "Invalid backup IV"
            }
            val iv = ByteArray(ivSize)
            raw.readFully(iv)

            val key = deriveKey(
                password,
                salt,
                iterations
            )
            val cipher = Cipher.getInstance(
                "AES/GCM/NoPadding"
            ).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(128, iv)
                )
                updateAAD(MAGIC)
            }

            CipherInputStream(raw, cipher).use { decrypted ->
                ZipInputStream(
                    BufferedInputStream(decrypted)
                ).use { zip ->
                    while (true) {
                        val entry =
                            zip.nextEntry ?: break
                        val target = safeTarget(
                            staging,
                            entry.name
                        )
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream()
                                .buffered()
                                .use { output ->
                                    zip.copyTo(output)
                                }
                        }
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    private fun createManifest(
        document: DocumentEntity,
        pages: List<PageEntity>,
        fields: List<DocumentFieldEntity>,
        directory: File
    ): JSONObject {
        val documentJson = documentToJson(document)
        val pageArray = JSONArray()
        pages.forEach { page ->
            pageArray.put(
                pageToJson(page, directory)
            )
        }
        val fieldsArray = JSONArray()
        fields.forEach { field ->
            fieldsArray.put(
                JSONObject()
                    .put("fieldKey", field.fieldKey)
                    .put("label", field.label)
                    .put("value", field.value)
                    .put("confidence", field.confidence)
                    .put("source", field.source)
            )
        }
        val pdfRelative = document.pdfPath
            ?.let(::File)
            ?.takeIf { it.exists() }
            ?.let {
                it.relativeTo(directory)
                    .invariantSeparatorsPath
            }
            .orEmpty()

        return JSONObject()
            .put("version", 2)
            .put("document", documentJson)
            .put("pages", pageArray)
            .put("fields", fieldsArray)
            .put("pdfRelativePath", pdfRelative)
    }

    private fun documentToJson(
        d: DocumentEntity
    ): JSONObject = JSONObject()
        .put("title", d.title)
        .put("createdAt", d.createdAt)
        .put("updatedAt", d.updatedAt)
        .put("pageCount", d.pageCount)
        .put("ocrText", d.ocrText)
        .put("ocrScript", d.ocrScript)
        .put("documentType", d.documentType)
        .put("suggestedType", d.suggestedType)
        .put("needsReview", d.needsReview)
        .put("scanMode", d.scanMode)
        .put("publishingRecipe", d.publishingRecipe)
        .put("complianceRecipe", d.complianceRecipe)
        .put("securityRecipe", d.securityRecipe)

    private fun documentFromJson(
        j: JSONObject
    ): DocumentEntity = DocumentEntity(
        id = "backup",
        title = j.optString("title", "Restored Scan"),
        createdAt = j.optLong("createdAt"),
        updatedAt = j.optLong("updatedAt"),
        pdfPath = null,
        pageCount = j.optInt("pageCount"),
        ocrText = j.optString("ocrText"),
        ocrScript = j.optString(
            "ocrScript",
            OcrScript.LATIN.name
        ),
        documentType = j.optString(
            "documentType",
            DocumentType.UNSPECIFIED.name
        ),
        suggestedType = j.nullableString(
            "suggestedType"
        ),
        needsReview = j.optBoolean(
            "needsReview",
            false
        ),
        scanMode = j.optString(
            "scanMode",
            ScanMode.DOCUMENT.name
        ),
        publishingRecipe = j.nullableString(
            "publishingRecipe"
        ),
        complianceRecipe = j.nullableString(
            "complianceRecipe"
        ),
        securityRecipe = j.nullableString(
            "securityRecipe"
        )
    )

    private fun pageToJson(
        p: PageEntity,
        directory: File
    ): JSONObject = JSONObject()
        .put("id", p.id)
        .put("position", p.position)
        .put("sortKey", p.sortKey)
        .put("deleted", p.deleted)
        .put("rotationDegrees", p.rotationDegrees)
        .put("cropQuad", p.cropQuad)
        .put("visualRecipe", p.visualRecipe)
        .put("cleanupRecipe", p.cleanupRecipe)
        .put(
            "imageRelativePath",
            File(p.imagePath)
                .relativeTo(directory)
                .invariantSeparatorsPath
        )
        .put("width", p.width)
        .put("height", p.height)
        .put("ocrText", p.ocrText)
        .put("ocrLayout", p.ocrLayout)
        .put("ocrBaseLayout", p.ocrBaseLayout)
        .put("textEditRecipe", p.textEditRecipe)
        .put("markupRecipe", p.markupRecipe)
        .put("formFillRecipe", p.formFillRecipe)
        .put("structuredData", p.structuredData)
        .put("assemblyMetadata", p.assemblyMetadata)
        .put("ocrFingerprint", p.ocrFingerprint)
        .put("ocrScript", p.ocrScript)
        .put(
            "sourceSpreadPageId",
            p.sourceSpreadPageId
        )
        .put("bookSide", p.bookSide)
        .put(
            "bookSplitConfidence",
            p.bookSplitConfidence
        )
        .put(
            "bookDewarpStrength",
            p.bookDewarpStrength
        )
        .put(
            "preservedBookSource",
            p.preservedBookSource
        )
        .put(
            "bookReviewResolved",
            p.bookReviewResolved
        )

    private fun pageFromJson(
        j: JSONObject,
        documentId: String,
        directory: File,
        idMap: Map<String, String>
    ): PageEntity {
        val oldId = j.getString("id")
        return PageEntity(
            id = idMap.getValue(oldId),
            documentId = documentId,
            position = j.getInt("position"),
            sortKey = j.optLong("sortKey"),
            deleted = j.optBoolean("deleted"),
            rotationDegrees =
                j.optInt("rotationDegrees"),
            cropQuad = j.nullableString("cropQuad"),
            visualRecipe =
                j.nullableString("visualRecipe"),
            cleanupRecipe =
                j.nullableString("cleanupRecipe"),
            imagePath = File(
                directory,
                j.getString("imageRelativePath")
            ).absolutePath,
            width = j.getInt("width"),
            height = j.getInt("height"),
            ocrText = j.optString("ocrText"),
            ocrLayout =
                j.nullableString("ocrLayout"),
            ocrBaseLayout =
                j.nullableString("ocrBaseLayout"),
            textEditRecipe =
                j.nullableString("textEditRecipe"),
            markupRecipe =
                j.nullableString("markupRecipe"),
            formFillRecipe =
                j.nullableString("formFillRecipe"),
            structuredData =
                j.nullableString("structuredData"),
            assemblyMetadata =
                j.nullableString("assemblyMetadata"),
            ocrFingerprint =
                j.nullableString("ocrFingerprint"),
            ocrScript =
                j.nullableString("ocrScript"),
            sourceSpreadPageId =
                j.nullableString(
                    "sourceSpreadPageId"
                )?.let(idMap::get),
            bookSide =
                j.nullableString("bookSide"),
            bookSplitConfidence =
                if (j.isNull("bookSplitConfidence")) {
                    null
                } else {
                    j.optDouble(
                        "bookSplitConfidence"
                    ).toFloat()
                },
            bookDewarpStrength =
                j.optDouble(
                    "bookDewarpStrength",
                    0.0
                ).toFloat(),
            preservedBookSource =
                j.optBoolean(
                    "preservedBookSource"
                ),
            bookReviewResolved =
                j.optBoolean(
                    "bookReviewResolved"
                )
        )
    }

    private fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int
    ): SecretKeySpec {
        val spec = PBEKeySpec(
            password,
            salt,
            iterations,
            KEY_BITS
        )
        val bytes = try {
            SecretKeyFactory.getInstance(
                "PBKDF2WithHmacSHA256"
            ).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return SecretKeySpec(bytes, "AES")
    }

    private fun safeTarget(
        root: File,
        entryName: String
    ): File {
        require(
            entryName == "manifest.json" ||
                entryName.startsWith("files/")
        ) {
            "Invalid backup entry"
        }
        val target = File(root, entryName)
        val canonicalRoot = root.canonicalFile
        val canonical = target.canonicalFile
        require(
            canonical.path.startsWith(
                canonicalRoot.path +
                    File.separator
            )
        ) {
            "Unsafe backup entry"
        }
        return canonical
    }

    private fun copyTree(
        source: File,
        destination: File
    ) {
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source)
            val target = File(
                destination,
                relative.invariantSeparatorsPath
            )
            if (file.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                file.copyTo(
                    target,
                    overwrite = true
                )
            }
        }
    }

    private fun JSONObject.nullableString(
        key: String
    ): String? =
        if (!has(key) || isNull(key)) {
            null
        } else {
            optString(key).takeIf { it.isNotBlank() }
        }
}
