package at.mtr.logmygut.data

import at.mtr.logmygut.data.db.BowelMovementEntity
import at.mtr.logmygut.data.db.MealWithItems
import at.mtr.logmygut.data.db.MedicationIntakeEntity
import at.mtr.logmygut.data.db.SymptomEntryEntity
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.MedEffect
import at.mtr.logmygut.domain.PortionSize
import at.mtr.logmygut.domain.SymptomType
import java.time.Instant

/** One row of a day timeline (SPEC 6.1). */
sealed interface TimelineEntry {
    val timestamp: Instant

    data class Meal(
        override val timestamp: Instant,
        val mealId: Long,
        val items: List<Item>,
        val eatenOut: Boolean,
    ) : TimelineEntry {
        data class Item(val name: String, val kind: FoodKind, val count: Int?, val unitLabel: String?, val size: PortionSize?)
    }

    data class BowelMovement(override val timestamp: Instant, val entry: BowelMovementEntity) : TimelineEntry

    /** All symptom entries saved together (same timestamp), ordered by symptom type. */
    data class Symptoms(override val timestamp: Instant, val values: List<Pair<SymptomType, Int>>) : TimelineEntry

    data class Medication(override val timestamp: Instant, val id: Long, val name: String, val effect: MedEffect?) : TimelineEntry
}

/** Merges a day's entries into one chronological list. */
fun buildTimeline(
    meals: List<MealWithItems>,
    bowelMovements: List<BowelMovementEntity>,
    symptoms: List<SymptomEntryEntity>,
    medication: List<MedicationIntakeEntity>,
): List<TimelineEntry> {
    val entries = mutableListOf<TimelineEntry>()
    meals.mapTo(entries) { m ->
        TimelineEntry.Meal(
            timestamp = m.meal.timestamp,
            mealId = m.meal.id,
            items = m.items.sortedBy { it.item.id }.map {
                TimelineEntry.Meal.Item(it.food.name, it.food.kind, it.item.count, it.food.unitLabel, it.item.size)
            },
            eatenOut = m.meal.eatenOut,
        )
    }
    bowelMovements.mapTo(entries) { TimelineEntry.BowelMovement(it.timestamp, it) }
    symptoms.groupBy { it.timestamp }.mapTo(entries) { (timestamp, group) ->
        TimelineEntry.Symptoms(timestamp, group.sortedBy { it.type.ordinal }.map { it.type to it.severity })
    }
    medication.mapTo(entries) { TimelineEntry.Medication(it.timestamp, it.id, it.name, it.effect) }
    // Stable sort keeps a meal before a symptom logged at the same minute.
    return entries.sortedBy { it.timestamp }
}
