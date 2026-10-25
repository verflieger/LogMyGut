package at.mtr.logmygut.ui

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import at.mtr.logmygut.LogMyGutApp
import at.mtr.logmygut.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** End-to-end in English: UI texts, built-in food names and unit labels follow the language. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rGB")
class EnglishFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun logMealInEnglish() {
        // The seed import runs in the background on app start; wait until the foods have English names.
        val db = ApplicationProvider.getApplicationContext<LogMyGutApp>().container.database
        compose.waitUntil(10_000) { runBlocking { db.foodDao().getBySeedKey("Semmel")?.name == "Bread roll" } }

        compose.onNodeWithText("Food & drinks").performClick()
        // A German name still finds the food.
        compose.onNode(hasSetTextAction() and hasText("Search food or dish …")).performTextInput("semmel")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Bread roll")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Bread roll"))[0].performClick()
        compose.onNodeWithText("Triggers: Fructans, Gluten").assertExists()
        compose.onNodeWithText("Save").performClick()

        compose.waitUntil(5_000) {
            runCatching { compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Bread roll (1 piece)")) }.isSuccess
        }
    }
}
