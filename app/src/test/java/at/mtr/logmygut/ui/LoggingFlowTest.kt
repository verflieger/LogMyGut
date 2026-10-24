package at.mtr.logmygut.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import at.mtr.logmygut.LogMyGutApp
import at.mtr.logmygut.MainActivity
import at.mtr.logmygut.domain.FoodKind
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** End-to-end on a simulated device: log a meal from the Heute screen and see it in the timeline. */
@RunWith(RobolectricTestRunner::class)
class LoggingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun logMealAndSeeItOnToday() {
        // The seed import runs in the background on app start; wait until it's done.
        val db = ApplicationProvider.getApplicationContext<LogMyGutApp>().container.database
        compose.waitUntil(10_000) { runBlocking { db.foodDao().count(FoodKind.DISH) > 0 } }

        compose.onNodeWithText("Essen").performClick()
        compose.onNode(hasSetTextAction() and hasText("Lebensmittel oder Gericht suchen …")).performTextInput("semmel")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Semmel")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Semmel"))[0].performClick()
        compose.onNodeWithText("Auslöser: Fruktane, Gluten").assertExists()
        compose.onNodeWithText("Speichern").performClick()

        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Semmel (1 Stück)")).fetchSemanticsNodes().isNotEmpty() }
    }
}
