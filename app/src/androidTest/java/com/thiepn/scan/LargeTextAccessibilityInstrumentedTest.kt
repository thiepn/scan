package com.thiepn.scan

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test

class LargeTextAccessibilityInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun libraryIdentityAndPrimaryScanActionRemainVisibleAtLargeText() {
        composeRule
            .onNodeWithTag("library-title")
            .assertIsDisplayed()

        composeRule
            .onNodeWithTag("primary-scan-action")
            .assertIsDisplayed()
            .assertHasClickAction()
    }
}
