package at.mtr.logmygut.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagSuggesterTest {
    private val suggester = TagSuggester.fromJson(File("src/main/assets/keyword_rules.de.json").readText())

    @Test fun normalizesCaseAndUmlauts() {
        assertEquals("kase", normalizeForSearch("  Käse "))
        assertEquals("grusse", normalizeForSearch("Grüße"))
        assertEquals("creme fraiche", normalizeForSearch("Crème fraîche"))
    }

    @Test fun matchesKeywordsInsideCompoundWords() {
        assertEquals(setOf(Tag.FRUCTANS, Tag.GLUTEN), suggester.suggest("Zwiebelbrot"))
        assertTrue(Tag.GOS in suggester.suggest("Rote Linsensuppe"))
        assertTrue(Tag.SPICY in suggester.suggest("Scharfes Gemüsecurry"))
        assertEquals(setOf(Tag.FRUCTANS, Tag.GLUTEN, Tag.LACTOSE), suggester.suggest("Topfenpalatschinken"))
    }

    @Test fun removesWinOverAdds() {
        assertFalse(Tag.LACTOSE in suggester.suggest("Laktosefreie Milch"))
        assertFalse(Tag.CAFFEINE in suggester.suggest("Kräutertee"))
        val glutenFree = suggester.suggest("Glutenfreies Brot")
        assertFalse(Tag.GLUTEN in glutenFree)
        assertFalse(Tag.FRUCTANS in glutenFree)
    }

    @Test fun unknownFoodGetsNoSuggestions() {
        assertEquals(emptySet<Tag>(), suggester.suggest("Reis"))
    }
}
