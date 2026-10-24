package at.mtr.logmygut.ui.meal

import at.mtr.logmygut.data.FoodRepository
import at.mtr.logmygut.data.JournalRepository
import at.mtr.logmygut.data.TestDatabase
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.domain.Amount
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.PortionSize
import at.mtr.logmygut.domain.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MealEditViewModelTest {
    private lateinit var db: LogMyGutDatabase
    private lateinit var foods: FoodRepository
    private lateinit var journal: JournalRepository

    @Before fun setUp() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = TestDatabase.createSeeded()
        foods = FoodRepository(db, TestDatabase.tagSuggester)
        journal = JournalRepository(db)
    }

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun dish(name: String) = foods.search(name).first { it.kind == FoodKind.DISH }

    @Test fun dishTriggersDependOnAmount() = runTest {
        val vm = MealEditViewModel(foods, journal, null)
        vm.addFood(dish("currywurst"))

        val item = vm.items.single()
        assertEquals(Amount.Count(1), item.amount)
        // Ketchup's fructans only count from L, i.e. 2+ pieces of a count dish.
        assertFalse(Tag.FRUCTANS in item.effectiveTags)
        assertTrue(Tag.FAT in item.effectiveTags)

        vm.setAmount(item.key, Amount.Count(2))
        assertTrue(Tag.FRUCTANS in vm.items.single().effectiveTags)
    }

    @Test fun adjustingIngredientsChangesTriggersAndIsSaved() = runTest {
        val vm = MealEditViewModel(foods, journal, null)
        vm.addFood(dish("bolognese"))
        val item = vm.items.single()
        assertTrue(Tag.GLUTEN in item.effectiveTags)

        // Only the minced meat left: no triggers.
        val mince = item.ingredients.single { it.name == "Faschiertes" }
        vm.setIngredients(item.key, listOf(mince), saveToDish = false)
        assertEquals(emptySet<Tag>(), vm.items.single().effectiveTags.minus(Tag.FAT))

        vm.save()

        val saved = journal.getMeal(db.journalDao().getMeals(java.time.Instant.EPOCH, java.time.Instant.now().plusSeconds(60)).single().id)!!
        val savedItem = saved.items.single()
        assertEquals(PortionSize.M, savedItem.item.size)
        assertEquals(listOf(mince.id), journal.getMealItemIngredients(listOf(savedItem.item.id))[savedItem.item.id])
        // The recipe is unchanged because saveToDish was false.
        assertTrue(foods.getDishIngredientIds(item.food.id).size > 1)
    }

    @Test fun editingLoadsExistingMeal() = runTest {
        val first = MealEditViewModel(foods, journal, null)
        first.addFood(foods.search("semmel").first())
        first.setAmount(first.items.single().key, Amount.Count(3))
        first.eatenOut = true
        first.save()
        val mealId = db.journalDao().getMeals(java.time.Instant.EPOCH, java.time.Instant.now().plusSeconds(60)).single().id

        val edit = MealEditViewModel(foods, journal, mealId)

        assertTrue(edit.isEdit)
        assertTrue(edit.eatenOut)
        assertEquals(Amount.Count(3), edit.items.single().amount)
    }

    @Test fun createdIngredientIsAddedWithItsTags() = runTest {
        val vm = MealEditViewModel(foods, journal, null)
        vm.createFood(NewFood("Omas Zwiebelkuchen", FoodKind.INGREDIENT, FoodUnit.COUNT, "Stück", setOf(Tag.FRUCTANS, Tag.GLUTEN), emptyList()))

        val item = vm.items.single()
        assertEquals("Omas Zwiebelkuchen", item.food.name)
        assertEquals(setOf(Tag.FRUCTANS, Tag.GLUTEN), item.effectiveTags)
        assertEquals("Omas Zwiebelkuchen", foods.search("zwiebelkuchen").first().name)
    }
}
