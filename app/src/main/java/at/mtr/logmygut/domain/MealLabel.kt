package at.mtr.logmygut.domain

import java.time.LocalTime

/** Display-only meal label derived from the time of day (SPEC 3.2); never stored. */
enum class MealLabel { BREAKFAST, LUNCH, SNACK, DINNER }

fun mealLabelFor(time: LocalTime): MealLabel = when {
    time >= LocalTime.of(5, 0) && time < LocalTime.of(10, 30) -> MealLabel.BREAKFAST
    time >= LocalTime.of(10, 30) && time < LocalTime.of(14, 30) -> MealLabel.LUNCH
    time >= LocalTime.of(17, 0) && time < LocalTime.of(21, 30) -> MealLabel.DINNER
    else -> MealLabel.SNACK
}
