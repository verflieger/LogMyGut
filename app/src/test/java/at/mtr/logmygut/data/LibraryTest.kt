package at.mtr.logmygut.data

import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.data.db.toRow
import at.mtr.logmygut.data.seed.SeedImporter
import at.mtr.logmygut.data.db.MealEntity
import at.mtr.logmygut.data.db.MealItemEntity
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.PortionSize
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.TagRow
import at.mtr.logmygut.domain.TagSource
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
        foods = FoodRepository(db, TestDatabase.tagSuggester, resetFood = { SeedImporter(db).reset(TestDatabase.seed, it) })
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
        assertEquals(TagSource.USER, db.foodDao().getTags(id).single().source)

        // Without histamine: stored as a removal of the inherited tag.
        foods.saveIngredient(draft(id = id, name = "Himbeeressig", parentId = essig.id))
        assertEquals(emptyList<IngredientTag>(), foods.getIngredientTags(id))
        assertTrue(db.foodDao().getTags(id).single().removed)
    }

    // Built-in foods that follow the seed store only the user's differences (SPEC 4).

    private suspend fun rows(id: Long) = db.foodDao().getTags(id).map { it.toRow() }.toSet()
    private suspend fun saveTags(food: FoodEntity, tags: List<IngredientTag>, parentId: Long? = food.parentId) =
        foods.saveIngredient(draft(id = food.id, name = food.name, parentId = parentId, tags = tags))

    private val histamine = IngredientTag(Tag.HISTAMINE)
    private val fatFromL = IngredientTag(Tag.FAT, Threshold.MinSize(PortionSize.L))

    @Test fun savingABuiltInUnchangedStoresNoUserRows() = runTest {
        val weichkaese = named("Weichkäse")    // own seed tag histamine, fat from L via Käse
        assertEquals(setOf(histamine, fatFromL), foods.getIngredientTags(weichkaese.id).toSet())

        saveTags(weichkaese, listOf(histamine, fatFromL))

        assertEquals(setOf(TagRow(Tag.HISTAMINE, null, removed = false, TagSource.SEED)), rows(weichkaese.id))
        assertFalse(foods.getFood(weichkaese.id)!!.tagsDetached)
    }

    @Test fun changingOneTagOfABuiltInStoresOneUserRow() = runTest {
        val weichkaese = named("Weichkäse")
        saveTags(weichkaese, listOf(histamine, fatFromL, IngredientTag(Tag.SPICY)))
        assertEquals(listOf(TagRow(Tag.SPICY, null, removed = false, TagSource.USER)), rows(weichkaese.id).filter { it.source == TagSource.USER })

        // Removing its own seed tag replaces that row with a user removal; the seed row stays.
        saveTags(weichkaese, listOf(fatFromL))
        assertEquals(
            setOf(TagRow(Tag.HISTAMINE, null, removed = false, TagSource.SEED), TagRow(Tag.HISTAMINE, null, removed = true, TagSource.USER)),
            rows(weichkaese.id),
        )
        assertEquals(listOf(fatFromL), foods.getIngredientTags(weichkaese.id))
        assertFalse(foods.getFood(weichkaese.id)!!.tagsDetached)
    }

    @Test fun childOfAParentWithAUserRemovalSavesWithoutUserRows() = runTest {
        val kaese = named("Käse")
        saveTags(kaese, emptyList())           // removes its own seed tag fat
        val camembert = named("Camembert")
        assertEquals(listOf(histamine), foods.getIngredientTags(camembert.id))

        saveTags(camembert, listOf(histamine))

        assertEquals(emptySet<TagRow>(), rows(camembert.id))
    }

    @Test fun savingWithAnotherParentDetachesTheFood() = runTest {
        val camembert = named("Camembert")
        val kaese = named("Käse")
        val weichkaese = named("Weichkäse")
        saveTags(camembert, listOf(histamine, fatFromL), parentId = kaese.id)

        // Full own list relative to the new parent, as user rows only.
        assertTrue(foods.getFood(camembert.id)!!.tagsDetached)
        assertEquals(setOf(TagRow(Tag.HISTAMINE, null, removed = false, TagSource.USER)), rows(camembert.id))
        assertEquals(setOf(histamine, fatFromL), foods.getIngredientTags(camembert.id).toSet())

        // A second save keeps the full list.
        saveTags(foods.getFood(camembert.id)!!, listOf(histamine, fatFromL))
        assertEquals(setOf(TagRow(Tag.HISTAMINE, null, removed = false, TagSource.USER)), rows(camembert.id))

        // Setting the parent back doesn't re-attach it.
        saveTags(foods.getFood(camembert.id)!!, listOf(histamine, fatFromL), parentId = weichkaese.id)
        assertTrue(foods.getFood(camembert.id)!!.tagsDetached)
        assertEquals(emptySet<TagRow>(), rows(camembert.id))
        assertEquals(setOf(histamine, fatFromL), foods.getIngredientTags(camembert.id).toSet())
    }

    @Test fun userCreatedFoodsOnlyGetUserRowsAndNoFlag() = runTest {
        val weichkaese = named("Weichkäse")
        val id = foods.saveIngredient(draft(name = "Brie", parentId = weichkaese.id, tags = listOf(histamine, IngredientTag(Tag.GOS))))
        assertEquals(
            setOf(TagRow(Tag.GOS, null, removed = false, TagSource.USER), TagRow(Tag.FAT, null, removed = true, TagSource.USER)),
            rows(id),
        )
        assertFalse(foods.getFood(id)!!.tagsDetached)

        // A parent change doesn't set the flag either.
        foods.saveIngredient(draft(id = id, name = "Brie", parentId = named("Käse").id, tags = listOf(histamine)))
        assertTrue(rows(id).all { it.source == TagSource.USER })
        assertFalse(foods.getFood(id)!!.tagsDetached)
        assertEquals(listOf(histamine), foods.getIngredientTags(id))
    }

    @Test fun movingUnderAParentWithAUserRemovalStoresRowsRelativeToItsEffectiveTags() = runTest {
        val kaese = named("Käse")
        saveTags(kaese, emptyList())           // user removal of its seed tag fat
        val camembert = named("Camembert")

        saveTags(camembert, listOf(histamine, fatFromL), parentId = kaese.id)

        // Käse resolves to nothing, so fat must be an own row of Camembert now.
        assertTrue(foods.getFood(camembert.id)!!.tagsDetached)
        assertEquals(
            setOf(TagRow(Tag.HISTAMINE, null, removed = false, TagSource.USER), TagRow(Tag.FAT, fatFromL.threshold, removed = false, TagSource.USER)),
            rows(camembert.id),
        )
        assertEquals(setOf(histamine, fatFromL), foods.getIngredientTags(camembert.id).toSet())
    }

    @Test fun resetOfADetachedFoodRestoresParentAndTags() = runTest {
        val camembert = named("Camembert")
        val weichkaese = named("Weichkäse")
        saveTags(camembert, emptyList(), parentId = named("Käse").id)

        foods.resetToSeed(camembert.id)

        val reset = foods.getFood(camembert.id)!!
        assertFalse(reset.tagsDetached)
        assertEquals(weichkaese.id, reset.parentId)
        assertEquals(setOf(histamine, fatFromL), foods.getIngredientTags(camembert.id).toSet())
    }

    @Test fun duplicatesShowTheOriginalsTagsAndAreNotDetached() = runTest {
        val weichkaese = named("Weichkäse")
        saveTags(weichkaese, listOf(histamine, IngredientTag(Tag.SPICY)))                 // attached, with user rows
        val camembert = named("Camembert")
        saveTags(camembert, listOf(IngredientTag(Tag.GOS)), parentId = named("Käse").id)  // detached

        for (original in listOf(weichkaese.id, camembert.id)) {
            val copyId = foods.duplicate(original, "(Kopie)")
            val copy = foods.getFood(copyId)!!
            assertFalse(copy.builtIn)
            assertFalse(copy.tagsDetached)
            assertTrue(rows(copyId).all { it.source == TagSource.USER })
            assertEquals(foods.getIngredientTags(original).toSet(), foods.getIngredientTags(copyId).toSet())
        }
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

    @Test fun parentChecksOnAGivenMap() {
        // 1 ← 2 ← 3 is full; 10 → 11 → 10 is a cycle with 12 hanging below it.
        val map = mapOf(1L to null, 2L to 1L, 3L to 2L, 10L to 11L, 11L to 10L, 12L to 10L)
        assertFalse(foods.canBeParent(map, null, 3L))   // a new food would be level 4
        assertTrue(foods.canBeParent(map, null, 2L))
        assertFalse(foods.canBeParent(map, null, 12L))  // below a cycle
        assertFalse(foods.canBeParent(map, 1L, 12L))
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
