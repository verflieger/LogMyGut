package at.mtr.logmygut.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.data.db.MealEntity
import at.mtr.logmygut.data.db.MealItemEntity
import at.mtr.logmygut.data.seed.SeedFile
import at.mtr.logmygut.data.seed.SeedImporter
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.PortionSize
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.TagSuggester
import at.mtr.logmygut.domain.Threshold
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room on the JVM via Robolectric: seed import, search and meal persistence. */
@RunWith(RobolectricTestRunner::class)
class DatabaseTest {
    private lateinit var db: LogMyGutDatabase
    private lateinit var foods: FoodRepository
    private val seed = SeedFile.parse(File("src/main/assets/foods.de.json").readText())

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LogMyGutDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        foods = FoodRepository(db, TagSuggester.fromJson(File("src/main/assets/keyword_rules.de.json").readText()))
    }

    @After fun tearDown() = db.close()

    @Test fun seedImportInsertsEverythingOnce() = runTest {
        val importer = SeedImporter(db)
        assertEquals(seed.ingredients.size + seed.dishes.size, importer.import(seed))
        assertEquals(0, importer.import(seed))
        assertEquals(seed.ingredients.size, db.foodDao().count(FoodKind.INGREDIENT))
        assertEquals(seed.dishes.size, db.foodDao().count(FoodKind.DISH))
    }

    @Test fun seedImportKeepsUserEdits() = runTest {
        SeedImporter(db).import(seed)
        val apple = foods.search("apfel").first { it.seedKey == "Apfel" }
        db.foodDao().updateFood(apple.copy(name = "Mein Apfel", userModified = true))

        SeedImporter(db).import(seed)

        assertEquals("Mein Apfel", foods.getFood(apple.id)!!.name)
    }

    @Test fun dishRecipeAndDoseTagsRoundTrip() = runTest {
        SeedImporter(db).import(seed)
        val currywurst = foods.search("currywurst").single()
        val ingredientNames = foods.getDishIngredientIds(currywurst.id).map { foods.getFood(it)!!.name }.toSet()
        assertEquals(setOf("Bratwurst", "Ketchup", "Currypulver"), ingredientNames)

        val ketchup = foods.search("ketchup").first { it.kind == FoodKind.INGREDIENT }
        assertEquals(
            setOf(IngredientTag(Tag.FRUCTOSE, Threshold.MinSize(PortionSize.L)), IngredientTag(Tag.FRUCTANS, Threshold.MinSize(PortionSize.L))),
            foods.getIngredientTags(ketchup.id).toSet(),
        )
    }

    @Test fun searchIsUmlautInsensitiveAndFindsAustrianAliases() = runTest {
        SeedImporter(db).import(seed)
        assertTrue(foods.search("kase").any { it.name == "Käsespätzle" })
        assertEquals("Brötchen", foods.search("semmel").first().name)
        assertEquals("Kartoffeln", foods.search("erdäpfel").first().name)
        assertEquals(emptyList<FoodEntity>(), foods.search("   "))
    }

    @Test fun searchRanksNamePrefixThenRecentlyUsed() = runTest {
        val a = foods.createIngredient("Testbrot A", FoodUnit.SIZE, null, emptyList())
        val b = foods.createIngredient("Testbrot B", FoodUnit.SIZE, null, emptyList())
        foods.createIngredient("Großes Testbrot", FoodUnit.SIZE, null, emptyList())
        assertEquals(listOf("Testbrot A", "Testbrot B", "Großes Testbrot"), foods.search("testbrot").map { it.name })

        db.journalDao().saveMeal(meal(), listOf(MealItemEntity(mealId = 0, foodId = b, size = PortionSize.M)), emptyMap())

        assertEquals(listOf("Testbrot B", "Testbrot A", "Großes Testbrot"), foods.search("testbrot").map { it.name })
        assertNotNull(foods.getFood(b)!!.lastUsedAt)
        assertEquals(null, foods.getFood(a)!!.lastUsedAt)
    }

    @Test fun mealStoresActualDishIngredients() = runTest {
        SeedImporter(db).import(seed)
        val bolognese = foods.search("bolognese").first { it.kind == FoodKind.DISH }
        val garlic = foods.search("knoblauch").first().id
        val withoutGarlic = foods.getDishIngredientIds(bolognese.id) - garlic

        val journal = db.journalDao()
        val mealId = journal.saveMeal(
            meal(),
            listOf(MealItemEntity(mealId = 0, foodId = bolognese.id, size = PortionSize.L)),
            mapOf(0 to withoutGarlic),
        )

        val item = journal.getMealItems(listOf(mealId)).single()
        val stored = journal.getMealItemIngredients(listOf(item.id)).map { it.ingredientId }.toSet()
        assertEquals(withoutGarlic.toSet(), stored)
        // The saved recipe is unchanged.
        assertTrue(garlic in foods.getDishIngredientIds(bolognese.id))
    }

    @Test fun editingMealReplacesItems() = runTest {
        val bread = foods.createIngredient("Brot", FoodUnit.COUNT, "Scheibe", emptyList())
        val journal = db.journalDao()
        val id = journal.saveMeal(meal(), listOf(MealItemEntity(mealId = 0, foodId = bread, count = 1)), emptyMap())
        journal.saveMeal(meal().copy(id = id), listOf(MealItemEntity(mealId = id, foodId = bread, count = 3)), emptyMap())
        assertEquals(listOf(3), journal.getMealItems(listOf(id)).map { it.count })
    }

    private fun meal() = MealEntity(timestamp = Instant.parse("2026-10-24T10:30:00Z"), zoneId = "Europe/Vienna")
}
