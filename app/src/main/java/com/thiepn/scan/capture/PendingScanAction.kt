package com.thiepn.scan.capture

import com.thiepn.scan.data.ScanMode
import java.util.Base64

internal sealed interface PendingScanAction {
    data class NewDocument(
        val mode: ScanMode,
        val rapid: Boolean = false
    ) : PendingScanAction
    data class RapidExistingStart(
        val documentId: String,
        val mode: ScanMode
    ) : PendingScanAction
    data class RapidContinue(
        val documentId: String,
        val mode: ScanMode,
        val sessionId: String
    ) : PendingScanAction
    data class IdBack(
        val documentId: String
    ) : PendingScanAction
    data class Append(
        val documentId: String,
        val mode: ScanMode
    ) : PendingScanAction
    data class Insert(
        val documentId: String,
        val index: Int,
        val mode: ScanMode
    ) : PendingScanAction
    data class Retake(
        val documentId: String,
        val pageId: String,
        val mode: ScanMode
    ) : PendingScanAction
}

internal object PendingScanActionCodec {
    private const val VERSION = "2"

    fun encode(action: PendingScanAction): String {
        val fields = when (action) {
            is PendingScanAction.NewDocument ->
                listOf(
                    "NEW",
                    action.mode.name,
                    action.rapid.toString()
                )
            is PendingScanAction.RapidExistingStart ->
                listOf(
                    "RAPID_START",
                    action.documentId,
                    action.mode.name
                )
            is PendingScanAction.RapidContinue ->
                listOf(
                    "RAPID_CONTINUE",
                    action.documentId,
                    action.mode.name,
                    action.sessionId
                )
            is PendingScanAction.IdBack ->
                listOf(
                    "ID_BACK",
                    action.documentId
                )
            is PendingScanAction.Append ->
                listOf(
                    "APPEND",
                    action.documentId,
                    action.mode.name
                )
            is PendingScanAction.Insert ->
                listOf(
                    "INSERT",
                    action.documentId,
                    action.index.toString(),
                    action.mode.name
                )
            is PendingScanAction.Retake ->
                listOf(
                    "RETAKE",
                    action.documentId,
                    action.pageId,
                    action.mode.name
                )
        }
        return (
            listOf(VERSION) + fields
            ).joinToString(".") {
                Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                        it.toByteArray(Charsets.UTF_8)
                    )
            }
    }

    fun decode(encoded: String): PendingScanAction? =
        runCatching {
            val fields = encoded
                .split('.')
                .map {
                    Base64.getUrlDecoder()
                        .decode(it)
                        .toString(Charsets.UTF_8)
                }
            if (
                fields.isEmpty() ||
                fields[0] != VERSION
            ) {
                return@runCatching null
            }

            when (fields.getOrNull(1)) {
                "NEW" ->
                    PendingScanAction.NewDocument(
                        mode = scanMode(fields[2]),
                        rapid = fields[3].toBooleanStrict()
                    )
                "RAPID_START" ->
                    PendingScanAction.RapidExistingStart(
                        documentId = fields[2],
                        mode = scanMode(fields[3])
                    )
                "RAPID_CONTINUE" ->
                    PendingScanAction.RapidContinue(
                        documentId = fields[2],
                        mode = scanMode(fields[3]),
                        sessionId = fields[4]
                    )
                "ID_BACK" ->
                    PendingScanAction.IdBack(
                        documentId = fields[2]
                    )
                "APPEND" ->
                    PendingScanAction.Append(
                        documentId = fields[2],
                        mode = scanMode(fields[3])
                    )
                "INSERT" ->
                    PendingScanAction.Insert(
                        documentId = fields[2],
                        index = fields[3].toInt(),
                        mode = scanMode(fields[4])
                    )
                "RETAKE" ->
                    PendingScanAction.Retake(
                        documentId = fields[2],
                        pageId = fields[3],
                        mode = scanMode(fields[4])
                    )
                else -> null
            }
        }.getOrNull()

    private fun scanMode(value: String): ScanMode =
        ScanMode.entries.first {
            it.name == value
        }
}
