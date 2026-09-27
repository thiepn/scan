package com.thiepn.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.thiepn.scan.data.DocumentPageSearchHit
import com.thiepn.scan.data.FormField
import com.thiepn.scan.data.FormFieldType
import com.thiepn.scan.data.PageEntity
import com.thiepn.scan.data.PageFormRecipe
import com.thiepn.scan.data.PageFormRecipeCodec
import com.thiepn.scan.ui.DocumentSearchDialog
import com.thiepn.scan.ui.FormEditorDialog
import com.thiepn.scan.ui.MarkupEditorDialog
import com.thiepn.scan.ui.ScanTheme
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class AdvancedVisualComposablesInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private enum class Surface {
        SEARCH,
        MARKUP,
        FORM
    }

    @Test
    fun captureAdvancedProductionSurfaces() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val source = samplePage(File(targetContext.cacheDir, "advanced-visual-source.png"))

        val formRecipe = PageFormRecipe(
            fields = listOf(
                FormField(
                    id = "name",
                    type = FormFieldType.TEXT,
                    label = "Name",
                    left = 0.13f,
                    top = 0.20f,
                    right = 0.84f,
                    bottom = 0.28f,
                    value = "Jonathan",
                    required = true,
                    fieldOrder = 0
                ),
                FormField(
                    id = "available",
                    type = FormFieldType.CHECKBOX,
                    label = "Available Saturday",
                    left = 0.13f,
                    top = 0.42f,
                    right = 0.20f,
                    bottom = 0.49f,
                    checked = true,
                    fieldOrder = 1
                )
            )
        )

        val page = PageEntity(
            id = "visual-page",
            documentId = "visual-document",
            position = 0,
            imagePath = source.absolutePath,
            width = 1240,
            height = 1754,
            ocrText = "Travel expense report Berlin hotel rail ticket total 379.90",
            formFillRecipe = PageFormRecipeCodec.encode(formRecipe)
        )

        val surface = mutableStateOf(Surface.SEARCH)

        composeRule.setContent {
            ScanTheme {
                when (surface.value) {
                    Surface.SEARCH -> DocumentSearchDialog(
                        query = "Berlin",
                        hits = listOf(
                            DocumentPageSearchHit(
                                pageId = page.id,
                                pageNumber = 1,
                                snippet = "Travel expenses · ‹Berlin› · hotel and rail ticket · total €379.90",
                                rank = -1.0,
                                matchingWords = emptyList()
                            )
                        ),
                        searching = false,
                        onQueryChange = {},
                        onDismiss = {},
                        onOpenHit = {}
                    )

                    Surface.MARKUP -> MarkupEditorDialog(
                        page = page,
                        onDismiss = {},
                        onSave = {}
                    )

                    Surface.FORM -> FormEditorDialog(
                        page = page,
                        onDismiss = {},
                        onSave = {}
                    )
                }
            }
        }

        waitForText("Find in document")
        capture(targetContext.filesDir, "07-document-search")

        composeRule.runOnIdle { surface.value = Surface.MARKUP }
        waitForText("Markup & redaction")
        capture(targetContext.filesDir, "08-markup-redaction")

        composeRule.runOnIdle { surface.value = Surface.FORM }
        waitForText("Fill form")
        capture(targetContext.filesDir, "09-form-filling")
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithText(text).assertIsDisplayed()
            }.isSuccess
        }
        composeRule.waitForIdle()
    }

    private fun capture(directory: File, name: String) {
        directory.mkdirs()
        composeRule.waitForIdle()
        Thread.sleep(250)
        dismissSystemAnrIfPresent()
        Thread.sleep(250)
        val bitmap = InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .takeScreenshot()
        FileOutputStream(File(directory, "$name.png")).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
    }

    private fun dismissSystemAnrIfPresent() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val root = automation.rootInActiveWindow ?: return

        fun findWait(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == "Wait" && node.isClickable) {
                return node
            }
            for (index in 0 until node.childCount) {
                findWait(node.getChild(index))?.let { return it }
            }
            return null
        }

        findWait(root)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun samplePage(file: File): File {
        val bitmap = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(250, 250, 248))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 33, 40)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        paint.textSize = 58f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("VOLUNTEER REGISTRATION", 90f, 145f, paint)

        paint.textSize = 32f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.color = Color.rgb(90, 96, 108)
        canvas.drawText("Community Weekend · Berlin", 90f, 205f, paint)

        paint.color = Color.rgb(210, 214, 220)
        paint.strokeWidth = 3f
        canvas.drawLine(90f, 255f, 1150f, 255f, paint)

        paint.color = Color.rgb(45, 49, 57)
        paint.textSize = 31f
        val lines = listOf(
            "Name        ______________________________",
            "Email       ______________________________",
            "Phone       ______________________________",
            "",
            "Availability",
            "□ Friday     □ Saturday     □ Sunday",
            "",
            "Preferred team",
            "□ Welcome    □ Logistics    □ Media",
            "",
            "Signature    ______________________________"
        )
        var y = 350f
        lines.forEach { line ->
            if (line.isBlank()) {
                y += 34f
            } else {
                canvas.drawText(line, 90f, y, paint)
                y += 76f
            }
        }

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 96, out)
        }
        bitmap.recycle()
        return file
    }
}
