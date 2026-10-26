package at.mtr.logmygut.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import at.mtr.logmygut.LogMyGutApp
import at.mtr.logmygut.MainActivity
import at.mtr.logmygut.data.IngredientDraft
import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.Tag
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Expert mode (SPEC 2): the switch shows the detached ingredients and the changes from the default, and the changes
 * follow "Reset to default" right away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rGB")
class ExpertFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val container = ApplicationProvider.getApplicationContext<LogMyGutApp>().container

    private suspend fun save(food: FoodEntity, tags: List<IngredientTag>, parentId: Long? = food.parentId) =
        container.foodRepository.saveIngredient(
            IngredientDraft(
                id = food.id, name = food.name, aliases = food.aliases, unit = food.unit, unitLabel = food.unitLabel,
                portionGrams = food.portionGrams, note = food.note, parentId = parentId, tags = tags, drink = food.drink,
            ),
        )

    @Test fun expertScreensListDetachedAndChangedFoods() {
        val dao = container.database.foodDao()
        compose.waitUntil(10_000) { runBlocking { dao.getBySeedKey("Semmel")?.name == "Bread roll" } }
        runBlocking {
            // Semmel without its parent (detached, same tags as its own); Apfel with lactose added (attached).
            val roll = dao.getBySeedKey("Semmel")!!
            save(roll, container.foodRepository.getIngredientTags(roll.id), parentId = null)
            val apple = dao.getBySeedKey("Apfel")!!
            save(apple, container.foodRepository.getIngredientTags(apple.id) + IngredientTag(Tag.LACTOSE))
        }

        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Expert mode").performScrollTo()
        compose.onNodeWithText("Detached ingredients").assertDoesNotExist()
        compose.onNodeWithText("Changes from the default").assertDoesNotExist()
        compose.onNodeWithText("Expert mode").performClick()

        // The switch is saved in DataStore, which updates the screen a moment later.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Detached ingredients").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Detached ingredients").performScrollTo().performClick()
        compose.onNodeWithText("No detached ingredients.").assertDoesNotExist()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Bread roll").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Changes from the default").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Lactose added").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Belongs to — (default: Bread rolls)").assertExists()
        compose.onNodeWithText("detached").assertExists()

        // A tap opens the food's editor; resetting it there takes it off the list, the other food stays.
        compose.onNodeWithText("Bread roll").performClick()
        compose.onNodeWithText("Tags no longer follow the default").assertExists()
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Reset to default").performClick()
        compose.waitUntil(5_000) { runBlocking { dao.getBySeedKey("Semmel")?.tagsDetached == false } }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Bread roll").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Lactose added").assertExists()

        // Switched off, the screens are no longer listed.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Expert mode").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Detached ingredients").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Changes from the default").assertDoesNotExist()
    }
}
