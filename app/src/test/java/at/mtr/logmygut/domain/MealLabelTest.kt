package at.mtr.logmygut.domain

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class MealLabelTest {
    @Test fun boundaries() {
        assertEquals(MealLabel.SNACK, mealLabelFor(LocalTime.of(4, 59)))
        assertEquals(MealLabel.BREAKFAST, mealLabelFor(LocalTime.of(5, 0)))
        assertEquals(MealLabel.LUNCH, mealLabelFor(LocalTime.of(10, 30)))
        assertEquals(MealLabel.SNACK, mealLabelFor(LocalTime.of(14, 30)))
        assertEquals(MealLabel.DINNER, mealLabelFor(LocalTime.of(17, 0)))
        assertEquals(MealLabel.SNACK, mealLabelFor(LocalTime.of(21, 30)))
    }
}
