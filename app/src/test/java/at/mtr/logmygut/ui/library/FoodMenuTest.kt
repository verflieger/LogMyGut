package at.mtr.logmygut.ui.library

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.FoodUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The ⋮ menu offers "Reset to default" for edited and for detached built-in foods (SPEC 4.1). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class FoodMenuTest {
    @get:Rule val compose = createComposeRule()

    private val builtIn = FoodEntity(kind = FoodKind.INGREDIENT, name = "Apple", unit = FoodUnit.SIZE, builtIn = true, seedKey = "Apfel")

    private fun openMenu(food: FoodEntity) {
        compose.setContent { FoodMenu(food, onHide = {}, onDuplicate = {}, onReset = {}) }
        compose.onNodeWithContentDescription("More actions").performClick()
    }

    @Test fun resetIsOfferedForADetachedUneditedFood() {
        openMenu(builtIn.copy(tagsDetached = true))
        compose.onNodeWithText("Reset to default").assertExists()
    }

    @Test fun resetIsNotOfferedForAnUnchangedFood() {
        openMenu(builtIn)
        compose.onNodeWithText("Duplicate", substring = true).assertExists()
        compose.onNodeWithText("Reset to default").assertDoesNotExist()
    }
}
