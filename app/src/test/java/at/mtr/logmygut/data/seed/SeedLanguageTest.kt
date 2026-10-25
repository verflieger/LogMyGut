package at.mtr.logmygut.data.seed

import at.mtr.logmygut.data.FoodRepository
import at.mtr.logmygut.data.TestDatabase
import at.mtr.logmygut.data.db.LogMyGutDatabase
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Built-in food names follow the app's language; search finds them in every language. */
@RunWith(RobolectricTestRunner::class)
class SeedLanguageTest {
    private lateinit var db: LogMyGutDatabase
    private lateinit var foods: FoodRepository
    private val seed = TestDatabase.seed

    @Before fun setUp() {
        db = TestDatabase.create()
        foods = FoodRepository(db, TestDatabase.tagSuggester)
    }

    @After fun tearDown() = db.close()

    private suspend fun food(key: String) = db.foodDao().getBySeedKey(key)!!

    @Test fun everyShippedFoodHasAnEnglishName() {
        val foods: List<SeedFood> = seed.ingredients + seed.dishes
        assertEquals(emptyList<String>(), foods.filter { "en" !in it.translations }.map { it.name })
    }

    @Test fun seedLanguageFallsBackToEnglish() {
        assertEquals("de", seedLanguage(Locale.forLanguageTag("de-AT")))
        assertEquals("en", seedLanguage(Locale.UK))
        assertEquals("en", seedLanguage(Locale.FRANCE))
    }

    @Test fun namesFollowTheLanguageAndSearchFindsBoth() = runTest {
        SeedImporter(db).import(seed, "en")
        assertEquals("Bread roll", food("Semmel").name)
        assertTrue(foods.search("semmel").any { it.name == "Bread roll" })
        assertTrue(foods.search("brötchen").any { it.name == "Bread roll" })

        SeedImporter(db).import(seed, "de")
        assertEquals("Semmel", food("Semmel").name)
        assertTrue(foods.search("bread roll").any { it.name == "Semmel" })
        // The name isn't repeated among the aliases.
        assertTrue(food("Semmel").aliases.none { it.equals("Semmel", ignoreCase = true) })
        assertEquals(SeedImporter.Result(0, 0), SeedImporter(db).import(seed, "de"))
    }

    @Test fun changedFoodsFollowTheLanguageUnlessRenamed() = runTest {
        SeedImporter(db).import(seed, "de")
        val roll = food("Semmel")
        db.foodDao().updateFood(roll.copy(note = "Meine Notiz", userModified = true))
        val apple = food("Apfel")
        db.foodDao().updateFood(apple.copy(name = "Mein Apfel", userModified = true))

        SeedImporter(db).import(seed, "en")
        val renamed = food("Semmel")
        assertEquals("Bread roll", renamed.name)
        assertEquals("Meine Notiz", renamed.note)
        assertTrue("old name stays searchable", "Semmel" in renamed.aliases)
        assertEquals("Mein Apfel", food("Apfel").name)
    }

    @Test fun renamedSeedEntriesFollowTheLanguageToo() = runTest {
        SeedImporter(db).import(seed, "de")
        // A food changed by the user before its seed rename (Paradeiser → Tomate) still counts as not renamed.
        val tomato = food("Paradeiser")
        db.foodDao().updateFood(tomato.copy(name = "Paradeiser", userModified = true))

        SeedImporter(db).import(seed, "en")
        assertEquals("Tomato", food("Paradeiser").name)
    }
}
