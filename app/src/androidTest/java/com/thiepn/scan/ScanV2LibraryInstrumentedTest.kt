package com.thiepn.scan

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/**
 * P22: exercise native library interaction entry points without launching
 * the external Play services scanner or the Android document picker.
 */
class ScanV2LibraryInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun scanAndImportAreSeparateImmediateActions() {
        composeRule.onNodeWithTag("library-title").assertIsDisplayed()
        composeRule.onNodeWithTag("primary-scan-action")
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithTag("import-pdf-action")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun scanModesAreAvailableWithoutLaunchingCamera() {
        composeRule.onNodeWithTag("scan-mode-action")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("scan-mode-sheet").assertExists()
        composeRule.onNodeWithTag("scan-mode-document").assertExists()
        composeRule.onNodeWithTag("scan-mode-receipt").assertExists()
        composeRule.onNodeWithTag("scan-mode-book").assertExists()
        composeRule.onNodeWithTag("scan-mode-id_card").assertExists()
    }
}
