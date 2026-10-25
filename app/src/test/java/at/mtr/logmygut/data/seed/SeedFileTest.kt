package at.mtr.logmygut.data.seed

import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.PortionSize
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.Threshold
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedFileTest {
    private val seed = SeedFile.parse(File("src/main/assets/foods.json").readText())

    @Test fun shippedSeedIsValid() {
        assertTrue("expected ~380 ingredients, got ${seed.ingredients.size}", seed.ingredients.size >= 350)
        assertTrue("expected ~60 dishes, got ${seed.dishes.size}", seed.dishes.size >= 50)
    }

    @Test fun doseTagsAreParsed() {
        val ketchup = seed.ingredients.single { it.name == "Ketchup" }
        assertEquals(listOf(Tag.FRUCTANS to Threshold.MinSize(PortionSize.L)), ketchup.tags.map { it.tag to it.threshold })
        val oatMilk = seed.ingredients.single { it.name == "Hafermilch" }
        assertEquals(FoodUnit.COUNT, oatMilk.unit)
        assertEquals(IngredientTag(Tag.FRUCTANS, Threshold.MinCount(2)), oatMilk.tags.single { it.tag == Tag.FRUCTANS })
        assertEquals("Pflanzendrink", oatMilk.parent)
        assertEquals(250.0, oatMilk.grams)
    }

    @Test fun renamedFoodsKeepTheirSeedKey() {
        val hardCheese = seed.ingredients.single { it.name == "Hartkäse" }
        assertEquals("Gereifter Hartkäse", hardCheese.key)
        assertEquals("Gereifter Hartkäse", seed.ingredients.single { it.name == "Parmesan" }.parent)
    }

    @Test fun rejectsUnknownDishIngredient() {
        val e = assertThrows(IllegalArgumentException::class.java) { SeedFile.parse(file(dishIngredients = """["Gibtsnicht"]""")) }
        assertTrue(e.message!!.contains("Gibtsnicht"))
    }

    @Test fun rejectsUnknownTag() {
        assertThrows(IllegalArgumentException::class.java) { SeedFile.parse(file(tags = """["vitamin"]""")) }
    }

    @Test fun rejectsSmallSizeThreshold() {
        assertThrows(IllegalArgumentException::class.java) { SeedFile.parse(file(doseTags = """{"fructans": "S"}""")) }
    }

    @Test fun rejectsDuplicateAliases() {
        assertThrows(IllegalArgumentException::class.java) { SeedFile.parse(file(dishAliases = """["apfel"]""")) }
    }

    @Test fun parsesHierarchy() {
        val parsed = SeedFile.parse(hierarchy("""{"name": "Apfelessig", "parent": "Essig", "unit": "size", "tags": [], "removeTags": ["histamine"]}"""))
        val child = parsed.ingredients.single { it.name == "Apfelessig" }
        assertEquals("Essig", child.parent)
        assertEquals(listOf(Tag.HISTAMINE), child.removedTags)
    }

    @Test fun rejectsUnknownParent() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SeedFile.parse(hierarchy("""{"name": "Apfelessig", "parent": "Gibtsnicht", "unit": "size", "tags": []}"""))
        }
        assertTrue(e.message!!.contains("Gibtsnicht"))
    }

    @Test fun rejectsTooDeepHierarchyAndCycles() {
        val tooDeep = """
            {"name": "A", "parent": "Essig", "unit": "size", "tags": []},
            {"name": "B", "parent": "A", "unit": "size", "tags": []},
            {"name": "C", "parent": "B", "unit": "size", "tags": []}
        """
        assertThrows(IllegalArgumentException::class.java) { SeedFile.parse(hierarchy(tooDeep)) }
        val cycle = """
            {"name": "A", "parent": "B", "unit": "size", "tags": []},
            {"name": "B", "parent": "A", "unit": "size", "tags": []}
        """
        assertThrows(IllegalArgumentException::class.java) { SeedFile.parse(hierarchy(cycle)) }
    }

    @Test fun rejectsRemoveTagsWithoutParent() {
        assertThrows(IllegalArgumentException::class.java) {
            SeedFile.parse(hierarchy("""{"name": "Apfelessig", "unit": "size", "tags": [], "removeTags": ["histamine"]}"""))
        }
    }

    /** Essig (histamine) as root, plus [more] ingredient objects. */
    private fun hierarchy(more: String) = """
        {"version": 1, "tags": [],
         "ingredients": [{"name": "Essig", "unit": "size", "tags": ["histamine"]}, $more],
         "dishes": []}
    """.trimIndent()

    private fun file(
        tags: String = """["fructose"]""",
        doseTags: String = "{}",
        dishIngredients: String = """["Apfel"]""",
        dishAliases: String = "[]",
    ) = """
        {"version": 1, "tags": [],
         "ingredients": [{"name": "Apfel", "unit": "size", "tags": $tags, "doseTags": $doseTags}],
         "dishes": [{"name": "Apfelkuchen", "aliases": $dishAliases, "unit": "size", "ingredients": $dishIngredients}]}
    """.trimIndent()
}
