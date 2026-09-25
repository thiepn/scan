package com.thiepn.scan.data

import android.app.ActivityManager
import android.content.Context

enum class DeviceMemoryTier {
    LOW,
    BALANCED,
    HIGH
}

data class DeviceRenderBudget(
    val memoryTier: DeviceMemoryTier,
    val lowRamDevice: Boolean,
    val libraryThumbnailLongEdge: Int,
    val pagePreviewLongEdge: Int,
    val largeDocumentPreviewLongEdge: Int,
    val enhancementPreviewLongEdge: Int,
    val semanticProcessingLongEdge: Int,
    val bookProcessingLongEdge: Int,
    val pdfImportLongEdge: Int,
    val editedExportLongEdge: Int,
    val largeDocumentPageThreshold: Int
) {
    fun previewLongEdge(
        requested: Int,
        pageCount: Int = 1
    ): Int {
        val cap = if (pageCount >= largeDocumentPageThreshold) {
            largeDocumentPreviewLongEdge
        } else {
            pagePreviewLongEdge
        }
        return requested
            .coerceAtLeast(1)
            .coerceAtMost(cap)
    }

    fun thumbnailLongEdge(requested: Int): Int =
        requested
            .coerceAtLeast(1)
            .coerceAtMost(libraryThumbnailLongEdge)

    fun enhancementLongEdge(requested: Int): Int =
        requested
            .coerceAtLeast(1)
            .coerceAtMost(enhancementPreviewLongEdge)

    fun exportLongEdge(requested: Int?): Int =
        if (requested == null) {
            editedExportLongEdge
        } else {
            requested.coerceAtMost(editedExportLongEdge)
        }
}

object DeviceRenderBudgetPolicy {
    fun forMemory(
        memoryClassMb: Int,
        lowRamDevice: Boolean
    ): DeviceRenderBudget {
        val tier = when {
            lowRamDevice || memoryClassMb < 256 -> DeviceMemoryTier.LOW
            memoryClassMb < 512 -> DeviceMemoryTier.BALANCED
            else -> DeviceMemoryTier.HIGH
        }

        return when (tier) {
            DeviceMemoryTier.LOW -> DeviceRenderBudget(
                memoryTier = tier,
                lowRamDevice = true,
                libraryThumbnailLongEdge = 360,
                pagePreviewLongEdge = 1050,
                largeDocumentPreviewLongEdge = 760,
                enhancementPreviewLongEdge = 900,
                semanticProcessingLongEdge = 2200,
                bookProcessingLongEdge = 2600,
                pdfImportLongEdge = 1800,
                editedExportLongEdge = 3000,
                largeDocumentPageThreshold = 120
            )
            DeviceMemoryTier.BALANCED -> DeviceRenderBudget(
                memoryTier = tier,
                lowRamDevice = lowRamDevice,
                libraryThumbnailLongEdge = 480,
                pagePreviewLongEdge = 1400,
                largeDocumentPreviewLongEdge = 980,
                enhancementPreviewLongEdge = 1200,
                semanticProcessingLongEdge = 2600,
                bookProcessingLongEdge = 3200,
                pdfImportLongEdge = 2200,
                editedExportLongEdge = 4000,
                largeDocumentPageThreshold = 160
            )
            DeviceMemoryTier.HIGH -> DeviceRenderBudget(
                memoryTier = tier,
                lowRamDevice = lowRamDevice,
                libraryThumbnailLongEdge = 640,
                pagePreviewLongEdge = 1800,
                largeDocumentPreviewLongEdge = 1200,
                enhancementPreviewLongEdge = 1500,
                semanticProcessingLongEdge = 2800,
                bookProcessingLongEdge = 3600,
                pdfImportLongEdge = 2400,
                editedExportLongEdge = 5000,
                largeDocumentPageThreshold = 220
            )
        }
    }
}

class DeviceCapabilityPolicy(
    context: Context
) {
    private val activityManager =
        context.applicationContext.getSystemService(
            Context.ACTIVITY_SERVICE
        ) as ActivityManager

    val budget: DeviceRenderBudget by lazy {
        DeviceRenderBudgetPolicy.forMemory(
            memoryClassMb = activityManager.memoryClass,
            lowRamDevice = activityManager.isLowRamDevice
        )
    }
}
