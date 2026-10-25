package at.mtr.logmygut.ui

import androidx.compose.ui.test.hasContentDescription
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
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.ui.common.LongDayFormat
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** End-to-end on a simulated device in German: logging from Heute, and the Verlauf calendar. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de-rAT")
class LoggingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun logMealAndSeeItOnToday() {
        // The seed import runs in the background on app start; wait until it's done.
        val db = ApplicationProvider.getApplicationContext<LogMyGutApp>().container.database
        compose.waitUntil(10_000) { runBlocking { db.foodDao().count(FoodKind.DISH) > 0 } }

        compose.onNodeWithText("Essen & Trinken").performClick()
        compose.onNode(hasSetTextAction() and hasText("Lebensmittel oder Gericht suchen …")).performTextInput("semmel")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Semmel")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Semmel"))[0].performClick()
        compose.onNodeWithText("Auslöser: Fruktane, Gluten").assertExists()
        compose.onNodeWithText("Speichern").performClick()

        // The check-in card can push the timeline below the fold; scroll to the entry like a user would.
        compose.waitUntil(5_000) {
            runCatching { compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Semmel (1 Stück)")) }.isSuccess
        }
    }

    @Test fun calendarOpensTodayWithCheckIn() {
        compose.onNodeWithText("Verlauf").performClick()
        val today = LongDayFormat.format(LocalDate.now())
        compose.onNode(hasContentDescription(today, substring = true)).performClick()

        compose.onNodeWithText(today).assertExists()
        compose.onNodeWithText("Tages-Check-in").assertExists()
        compose.onNodeWithText("Stress").assertExists()
    }
}
