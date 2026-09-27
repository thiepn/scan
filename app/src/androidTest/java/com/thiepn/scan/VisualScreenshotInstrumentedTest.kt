package com.thiepn.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.thiepn.scan.data.ScanMode
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class VisualScreenshotInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun captureRepresentativeV1Surfaces() {
        shell("rm -rf /sdcard/scan-v1-screenshots && mkdir -p /sdcard/scan-v1-screenshots")

        val app = instrumentation.targetContext.applicationContext as ScanApplication
        val repository = app.graph.repository

        val expensePage = samplePage(
            fileName = "expense-report.png",
            title = "TRAVEL EXPENSE REPORT",
            subtitle = "Berlin · September 2026",
            sections = listOf(
                "Rail ticket · Köln → Berlin                         €64.90",
                "Hotel · 2 nights                                  €238.00",
                "Local transit                                      €19.60",
                "Meals                                               €57.40",
                "",
                "TOTAL                                              €379.90",
                "",
                "Project: Community Outreach",
                "Status: Ready for reimbursement"
            )
        )
        val receiptPage = samplePage(
            fileName = "receipt.png",
            title = "RECEIPT",
            subtitle = "Market Hall Berlin",
            sections = listOf(
                "Coffee                                               €3.80",
                "Sandwich                                             €8.90",
                "Water                                                €2.20",
                "",
                "TOTAL                                               €14.90",
                "VISA •••• 1842",
                "",
                "Thank you"
            )
        )
        val formPage = samplePage(
            fileName = "registration-form.png",
            title = "VOLUNTEER REGISTRATION",
            subtitle = "Community Weekend",
            sections = listOf(
                "Name        ______________________________",
                "Email       ______________________________",
                "Phone       ______________________________",
                "",
                "Availability",
                "☐ Friday     ☐ Saturday     ☐ Sunday",
                "",
                "Preferred team",
                "☐ Welcome    ☐ Logistics    ☐ Media",
                "",
                "Signature    ______________________________"
            )
        )
        val notesPage = samplePage(
            fileName = "lecture-notes.png",
            title = "ANALYSIS II",
            subtitle = "Compactness & convergence",
            sections = listOf(
                "Bolzano–Weierstrass",
                "Every bounded sequence in ℝⁿ has a convergent subsequence.",
                "",
                "Uniform convergence",
                "sup |fₙ(x) − f(x)| → 0",
                "",
                "Exam reminder: state assumptions before applying theorem."
            )
        )

        val expenseId = runBlocking {
            val id = repository.ingestScan(
                pageUris = listOf(Uri.fromFile(expensePage), Uri.fromFile(receiptPage)),
                pdfUri = null,
                scanMode = ScanMode.PHOTO,
                awaitProcessing = true
            )
            repository.rename(id, "Travel Expenses — Berlin")
            repository.setScanMode(id, ScanMode.DOCUMENT)
            val folder = repository.createFolder("Work")
            val tag = repository.createTag("Receipts")
            repository.setDocumentFolder(listOf(id), folder)
            repository.addTagsToDocuments(listOf(id), listOf(tag))
            repository.setFavorite(id, true)
            id
        }

        runBlocking {
            val formId = repository.ingestScan(
                pageUris = listOf(Uri.fromFile(formPage)),
                pdfUri = null,
                scanMode = ScanMode.PHOTO,
                awaitProcessing = true
            )
            repository.rename(formId, "Volunteer Registration Form")
            repository.setScanMode(formId, ScanMode.FORM)
            repository.setDocumentsNeedsReview(listOf(formId), true)

            val notesId = repository.ingestScan(
                pageUris = listOf(Uri.fromFile(notesPage)),
                pdfUri = null,
                scanMode = ScanMode.PHOTO,
                awaitProcessing = true
            )
            repository.rename(notesId, "Analysis II — Lecture Notes")
            repository.setScanMode(notesId, ScanMode.NOTES)
        }

        waitForText("Travel Expenses — Berlin")
        capture("01-library")

        composeRule.onNodeWithTag("primary-scan-action").performClick()
        waitForText("Choose scan mode")
        capture("02-scan-modes")
        back()

        composeRule.onNodeWithContentDescription("Sort and filter").performClick()
        waitForText("Sort & filter")
        capture("03-sort-filter")
        back()

        composeRule.onNodeWithContentDescription("Automation Center").performClick()
        waitForText("Automation Center")
        capture("04-automation-center")
        back()

        composeRule.onNodeWithText("Travel Expenses — Berlin").performClick()
        waitForText("Travel Expenses — Berlin")
        capture("05-document-view")

        composeRule.onNodeWithContentDescription("Export PDF").performClick()
        waitForText("Export PDF")
        capture("06-export-pdf")
        back()

        composeRule
            .onNodeWithContentDescription("Markup, redact, or sign")
            .performScrollTo()
            .performClick()
        waitForText("Markup & redaction")
        capture("07-markup-redaction")
        back()

        composeRule
            .onNodeWithContentDescription("Fill form fields")
            .performScrollTo()
            .performClick()
        waitForText("Fill form")
        capture("08-form-filling")
        back()

        composeRule.onNodeWithContentDescription("Find in document").performClick()
        waitForText("Find in document")
        capture("09-document-search")

        // Keep the seeded document id referenced so compiler/test reports make the
        // relationship between seeded data and the captured document explicit.
        check(expenseId.isNotBlank())
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText(text).assertExists()
            }.isSuccess
        }
        composeRule.waitForIdle()
    }

    private fun back() {
        shell("input keyevent 4")
        Thread.sleep(400)
        composeRule.waitForIdle()
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        Thread.sleep(600)
        shell("screencap -p /sdcard/scan-v1-screenshots/${name}.png")
        Thread.sleep(250)
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    private fun samplePage(
        fileName: String,
        title: String,
        subtitle: String,
        sections: List<String>
    ): File {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, fileName)
        val bitmap = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(250, 250, 248))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(28, 31, 38)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        paint.textSize = 62f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(title, 96f, 150f, paint)

        paint.textSize = 34f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.color = Color.rgb(90, 96, 108)
        canvas.drawText(subtitle, 96f, 215f, paint)

        paint.color = Color.rgb(215, 218, 224)
        paint.strokeWidth = 3f
        canvas.drawLine(96f, 260f, 1144f, 260f, paint)

        paint.color = Color.rgb(43, 47, 55)
        paint.textSize = 31f
        var y = 340f
        sections.forEach { line ->
            if (line.isBlank()) {
                y += 34f
            } else {
                canvas.drawText(line, 96f, y, paint)
                y += 72f
            }
        }

        paint.color = Color.rgb(230, 232, 236)
        canvas.drawRect(96f, 1580f, 1144f, 1584f, paint)
        paint.color = Color.rgb(110, 116, 128)
        paint.textSize = 25f
        canvas.drawText("Sample document used only for Scan v1 visual capture", 96f, 1645f, paint)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 96, out)
        }
        bitmap.recycle()
        return file
    }
}
