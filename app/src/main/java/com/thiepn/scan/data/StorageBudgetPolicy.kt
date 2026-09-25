package com.thiepn.scan.data

import java.io.File
import kotlin.math.max

object StorageBudgetPolicy {
    const val RESERVED_FREE_BYTES: Long =
        64L * 1024L * 1024L

    fun requiredFreeBytes(
        estimatedWorkingBytes: Long
    ): Long {
        val working = max(
            estimatedWorkingBytes,
            0L
        )
        return saturatingAdd(
            RESERVED_FREE_BYTES,
            working
        )
    }

    fun pdfImportWorkingBytes(
        sourceBytes: Long
    ): Long = saturatingMultiply(
        sourceBytes.coerceAtLeast(0L),
        3L
    )

    fun pdfExportWorkingBytes(
        sourceBytes: Long
    ): Long = saturatingMultiply(
        sourceBytes.coerceAtLeast(0L),
        2L
    )

    fun hasEnoughSpace(
        usableBytes: Long,
        estimatedWorkingBytes: Long
    ): Boolean =
        usableBytes >= requiredFreeBytes(
            estimatedWorkingBytes
        )

    private fun saturatingAdd(
        left: Long,
        right: Long
    ): Long {
        if (right > Long.MAX_VALUE - left) {
            return Long.MAX_VALUE
        }
        return left + right
    }

    private fun saturatingMultiply(
        value: Long,
        multiplier: Long
    ): Long {
        if (value <= 0L || multiplier <= 0L) {
            return 0L
        }
        if (value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE
        }
        return value * multiplier
    }
}

object StorageSpaceGuard {
    fun require(
        anchor: File,
        estimatedWorkingBytes: Long,
        operation: String
    ) {
        val directory = (
            if (anchor.isDirectory) {
                anchor
            } else {
                anchor.parentFile
            }
            ) ?: anchor.absoluteFile.parentFile
            ?: throw IllegalStateException(
                "Storage location is unavailable"
            )
        directory.mkdirs()

        val usable = directory.usableSpace
        val required =
            StorageBudgetPolicy.requiredFreeBytes(
                estimatedWorkingBytes
            )
        kotlin.require(usable >= required) {
            "Not enough free storage to $operation. " +
                "Free at least " +
                formatBytes(required - usable) +
                " and try again."
        }
    }

    private fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        val mib = 1024L * 1024L
        val gib = 1024L * mib
        return when {
            safe >= gib ->
                ((safe + gib - 1) / gib).toString() +
                    " GB"
            else ->
                ((safe + mib - 1) / mib)
                    .coerceAtLeast(1L)
                    .toString() + " MB"
        }
    }
}
