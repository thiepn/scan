package com.thiepn.scan

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class LargeTextAccessibilityInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun libraryIdentityAndPrimaryScanActionRemainVisibleAtLargeText() {
        composeRule
            .onNodeWithText("Local-first document scanner")
            .assertIsDisplayed()

        composeRule
            .onNode(hasText("Scan") and hasClickAction())
            .assertIsDisplayed()
    }
}
