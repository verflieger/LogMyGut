package at.mtr.logmygut.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.data.db.MealEntity
import at.mtr.logmygut.data.db.MealItemEntity
import at.mtr.logmygut.data.seed.SeedFile
import at.mtr.logmygut.data.seed.SeedImporter
import at.mtr.logmygut.data.seed.toEntity
import at.mtr.logmygut.domain.Amount
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
    private val seed = SeedFile.parse(File("src/main/assets/foods.json").readText())

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LogMyGutDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        foods = FoodRepository(db, TagSuggester.fromJson(File("src/main/assets/keyword_rules.de.json").readText()))
    }

    @After fun tearDown() = db.close()

    @Test fun seedImportInsertsEverythingOnce() = runTest {
        val importer = SeedImporter(db)
        assertEquals(SeedImporter.Result(seed.ingredients.size + seed.dishes.size, 0), importer.import(seed))
        assertEquals(SeedImporter.Result(0, 0), importer.import(seed))
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
        assertEquals(listOf(IngredientTag(Tag.FRUCTANS, Threshold.MinSize(PortionSize.L))), foods.getIngredientTags(ketchup.id))
    }

    @Test fun seedImportStoresPortionWeight() = runTest {
        SeedImporter(db).import(seed)
        val nuts = foods.search("cashewkerne").single()
        assertEquals(30.0, nuts.portionGrams)
        assertEquals(60.0, Amount.Size(PortionSize.L).grams(nuts.portionGrams!!), 0.0)
    }

    @Test fun searchIsUmlautInsensitiveAndFindsGermanAliases() = runTest {
        SeedImporter(db).import(seed)
        assertTrue(foods.search("kase").any { it.name == "Käsespätzle" })
        assertEquals("Semmel", foods.search("semmel").first().name)
        // Other names stay findable as aliases: standard German, and Austrian where the name is standard German.
        assertEquals("Semmel", foods.search("brötchen").first().name)
        assertEquals("Kartoffeln", foods.search("erdäpfel").first().name)
        assertEquals("Tomate", foods.search("paradeiser").first().name)
        assertEquals("Topfen", foods.search("quark").first().name)
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

    @Test fun childInheritsOverridesAndRemovesParentTags() = runTest {
        SeedImporter(db).import(SeedFile.parse(vinegarSeed))
        suspend fun tags(name: String) = foods.getIngredientTags(foods.search(name).first { it.name == name }.id).toSet()

        assertEquals(setOf(IngredientTag(Tag.HISTAMINE)), tags("Essig"))
        assertEquals(setOf(IngredientTag(Tag.HISTAMINE), IngredientTag(Tag.FRUCTOSE, Threshold.MinSize(PortionSize.L))), tags("Balsamico"))
        assertEquals(emptySet<IngredientTag>(), tags("Branntweinessig"))
        // Grandchild: inherits through Balsamico, overrides the fructose threshold.
        assertEquals(setOf(IngredientTag(Tag.HISTAMINE), IngredientTag(Tag.FRUCTOSE)), tags("Balsamicocreme"))
    }

    @Test fun parentTagChangeReachesChildren() = runTest {
        SeedImporter(db).import(SeedFile.parse(vinegarSeed))
        val essig = foods.search("essig").first { it.name == "Essig" }
        db.foodDao().replaceTags(essig.id, listOf(IngredientTag(Tag.SPICY).toEntity()))

        val balsamico = foods.search("balsamico").first { it.name == "Balsamico" }
        assertEquals(setOf(Tag.SPICY, Tag.FRUCTOSE), foods.getIngredientTags(balsamico.id).map { it.tag }.toSet())
    }

    @Test fun seedUpdateSyncsUnmodifiedBuiltInsOnly() = runTest {
        val importer = SeedImporter(db)
        importer.import(SeedFile.parse(vinegarSeed))
        val brandy = foods.search("branntweinessig").single()
        db.foodDao().updateFood(brandy.copy(name = "Mein Essig", userModified = true))

        // New version: Balsamico gets an alias and loses its parent; Branntweinessig would get one too.
        val v2 = vinegarSeed
            .replace(""""parent": "Essig", "aliases": ["Aceto balsamico"]""", """"aliases": ["Aceto"]""")
            .replace(""""name": "Branntweinessig",""", """"name": "Branntweinessig", "aliases": ["Tafelessig"],""")
        val result = importer.import(SeedFile.parse(v2))

        assertEquals(SeedImporter.Result(inserted = 0, updated = 1), result)
        val balsamico = foods.search("aceto").single()
        assertEquals(null, balsamico.parentId)
        assertEquals(setOf(Tag.FRUCTOSE), foods.getIngredientTags(balsamico.id).map { it.tag }.toSet())
        // Balsamicocreme no longer inherits histamine via Essig.
        val creme = foods.search("balsamicocreme").single()
        assertEquals(setOf(Tag.FRUCTOSE), foods.getIngredientTags(creme.id).map { it.tag }.toSet())
        assertEquals("Mein Essig", foods.getFood(brandy.id)!!.name)
        assertEquals(emptyList<String>(), foods.getFood(brandy.id)!!.aliases)
    }

    @Test fun searchHitsExplainAliasMatchesAndParents() = runTest {
        SeedImporter(db).import(SeedFile.parse(vinegarSeed))
        val hit = foods.searchHits("aceto").single()
        assertEquals("Balsamico", hit.food.name)
        assertEquals("Aceto balsamico", hit.matchedAlias)
        assertEquals("Essig", hit.parentName)
        assertEquals(null, foods.searchHits("balsam").first { it.food.name == "Balsamico" }.matchedAlias)
    }

    private val vinegarSeed = """
        {"version": 1, "tags": [], "dishes": [], "ingredients": [
          {"name": "Essig", "unit": "size", "tags": ["histamine"]},
          {"name": "Balsamico", "parent": "Essig", "aliases": ["Aceto balsamico"], "unit": "size", "tags": [], "doseTags": {"fructose": "L"}},
          {"name": "Balsamicocreme", "parent": "Balsamico", "unit": "size", "tags": ["fructose"]},
          {"name": "Branntweinessig", "parent": "Essig", "unit": "size", "tags": [], "removeTags": ["histamine"]}
        ]}
    """.trimIndent()

    private fun meal() = MealEntity(timestamp = Instant.parse("2026-10-24T10:30:00Z"), zoneId = "Europe/Vienna")
}
