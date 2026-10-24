package at.mtr.logmygut.data.seed

import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.PortionSize
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.Threshold
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedFileTest {
    private val seed = SeedFile.parse(File("src/main/assets/foods.de.json").readText())

    @Test fun shippedSeedIsValid() {
        assertTrue("expected ~220 ingredients, got ${seed.ingredients.size}", seed.ingredients.size >= 200)
        assertTrue("expected ~40 dishes, got ${seed.dishes.size}", seed.dishes.size >= 30)
    }

    @Test fun doseTagsAreParsed() {
        val ketchup = seed.ingredients.single { it.name == "Ketchup" }
        assertEquals(
            setOf(Tag.FRUCTOSE to Threshold.MinSize(PortionSize.L), Tag.FRUCTANS to Threshold.MinSize(PortionSize.L)),
            ketchup.tags.map { it.tag to it.threshold }.toSet(),
        )
        val oatMilk = seed.ingredients.single { it.name == "Hafermilch" }
        assertEquals(FoodUnit.COUNT, oatMilk.unit)
        assertEquals(Threshold.MinCount(2), oatMilk.tags.single().threshold)
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
