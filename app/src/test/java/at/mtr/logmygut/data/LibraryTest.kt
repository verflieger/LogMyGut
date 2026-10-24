package at.mtr.logmygut.data

import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.data.db.MealEntity
import at.mtr.logmygut.data.db.MealItemEntity
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.PortionSize
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.Threshold
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Editing the food library (SPEC 4.1, 4.4). */
@RunWith(RobolectricTestRunner::class)
class LibraryTest {
    private lateinit var db: LogMyGutDatabase
    private lateinit var foods: FoodRepository

    @Before fun setUp() = runTest {
        db = TestDatabase.createSeeded()
        foods = FoodRepository(db, TestDatabase.tagSuggester, loadSeed = { TestDatabase.seed })
    }

    @After fun tearDown() = db.close()

    private suspend fun named(name: String) = foods.search(name).first { it.name == name }

    private fun draft(id: Long = 0, name: String, parentId: Long? = null, tags: List<IngredientTag> = emptyList()) = IngredientDraft(
        id = id, name = name, aliases = emptyList(), unit = FoodUnit.SIZE, unitLabel = null,
        portionGrams = 10.0, note = null, parentId = parentId, tags = tags,
    )

    @Test fun savedTagsAreStoredRelativeToTheParent() = runTest {
        val essig = named("Essig")
        // Inherits histamine, adds fructose from L.
        val wanted = listOf(IngredientTag(Tag.HISTAMINE), IngredientTag(Tag.FRUCTOSE, Threshold.MinSize(PortionSize.L)))
        val id = foods.saveIngredient(draft(name = "Himbeeressig", parentId = essig.id, tags = wanted))

        assertEquals(wanted.toSet(), foods.getIngredientTags(id).toSet())
        // Only the difference is stored, so a later change to Essig still reaches it.
        assertEquals(listOf(Tag.FRUCTOSE), db.foodDao().getTags(id).map { it.tag })

        // Without histamine: stored as a removal of the inherited tag.
        foods.saveIngredient(draft(id = id, name = "Himbeeressig", parentId = essig.id))
        assertEquals(emptyList<IngredientTag>(), foods.getIngredientTags(id))
        assertTrue(db.foodDao().getTags(id).single().removed)
    }

    @Test fun parentsMustNotFormCyclesOrExceedThreeLevels() = runTest {
        val kaese = named("Käse")              // level 1
        val weichkaese = named("Weichkäse")    // level 2, child of Käse
        val camembert = named("Camembert")     // level 3

        assertFalse(foods.canBeParent(kaese.id, camembert.id))      // cycle
        assertFalse(foods.canBeParent(null, camembert.id))          // would be level 4
        assertTrue(foods.canBeParent(null, weichkaese.id))
        assertFalse(foods.canBeParent(kaese.id, kaese.id))
        // Käse has two levels below it, so it can't move under anything.
        assertFalse(foods.canBeParent(kaese.id, named("Essig").id))
    }

    @Test fun editingABuiltInMarksItAndResetRestoresTheSeed() = runTest {
        val apfel = named("Apfel")
        foods.saveIngredient(draft(id = apfel.id, name = "Mein Apfel"))
        val edited = foods.getFood(apfel.id)!!
        assertEquals("Mein Apfel", edited.name)
        assertTrue(edited.userModified)

        foods.resetToSeed(apfel.id)

        val reset = foods.getFood(apfel.id)!!
        assertEquals("Apfel", reset.name)
        assertFalse(reset.userModified)
        assertTrue(Tag.FRUCTOSE in foods.getIngredientTags(apfel.id).map { it.tag })
    }

    @Test fun unitOfALoggedFoodDoesNotChange() = runTest {
        val semmel = named("Semmel")
        db.journalDao().saveMeal(
            MealEntity(timestamp = Instant.parse("2026-10-24T10:00:00Z"), zoneId = "Europe/Vienna"),
            listOf(MealItemEntity(mealId = 0, foodId = semmel.id, count = 1)),
            emptyMap(),
        )
        assertTrue(foods.isUsedInMeals(semmel.id))

        foods.saveIngredient(draft(id = semmel.id, name = "Semmel"))   // asks for SIZE

        assertEquals(FoodUnit.COUNT, foods.getFood(semmel.id)!!.unit)
    }

    @Test fun hiddenFoodsLeaveSearchAndDuplicatesCopyTheRecipe() = runTest {
        val pizza = foods.search("pizza").first { it.kind == FoodKind.DISH }
        val copyId = foods.duplicate(pizza.id, "(Kopie)")

        val copy = foods.getFood(copyId)!!
        assertEquals("Pizza (Kopie)", copy.name)
        assertFalse(copy.builtIn)
        assertEquals(foods.getDishIngredientIds(pizza.id).toSet(), foods.getDishIngredientIds(copyId).toSet())

        foods.setHidden(pizza.id, true)
        assertEquals(listOf("Pizza (Kopie)"), foods.search("pizza").filter { it.kind == FoodKind.DISH }.map { it.name })
    }

    @Test fun savingADishUpdatesItsRecipe() = runTest {
        val currywurst = named("Currywurst")
        val ketchup = named("Ketchup")
        foods.saveDish(DishDraft(currywurst.id, "Currywurst", emptyList(), FoodUnit.COUNT, "Stück", listOf(ketchup.id)))

        assertEquals(listOf(ketchup.id), foods.getDishIngredientIds(currywurst.id))
        assertTrue(foods.getFood(currywurst.id)!!.userModified)
    }
}
