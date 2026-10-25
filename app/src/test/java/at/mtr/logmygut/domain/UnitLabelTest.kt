package at.mtr.logmygut.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitLabelTest {
    @Test fun builtInLabelsInEitherLanguageMapToTheirKey() {
        assertEquals("Scheibe", unitLabelKey("Scheiben"))
        assertEquals("Scheibe", unitLabelKey(" slice "))
        assertEquals("Glas", unitLabelKey("Glasses"))
        assertEquals("Stück", unitLabelKey("piece"))
    }

    @Test fun ownLabelsStayAsTyped() {
        assertEquals("Riegel", unitLabelKey(" Riegel"))
        assertEquals("Handvoll", unitLabelKey("Handvoll"))
    }

    @Test fun drinkUnitsAreRecognisedInEitherLanguage() {
        assertTrue(isDrinkUnit(FoodUnit.COUNT, "Glas"))
        assertTrue(isDrinkUnit(FoodUnit.COUNT, "cup"))
        assertFalse(isDrinkUnit(FoodUnit.COUNT, "slice"))
        assertFalse(isDrinkUnit(FoodUnit.SIZE, "Glas"))
    }
}
