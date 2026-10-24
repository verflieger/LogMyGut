package at.mtr.logmygut.data

import at.mtr.logmygut.data.db.BowelMovementEntity
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.data.db.MealEntity
import at.mtr.logmygut.data.db.MealItemEntity
import at.mtr.logmygut.data.db.MealWithItems
import at.mtr.logmygut.data.db.MedicationIntakeEntity
import at.mtr.logmygut.data.db.SymptomEntryEntity
import at.mtr.logmygut.domain.MedEffect
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** A dish item's actual ingredients for one meal, keyed by the item's position in the meal. */
typealias DishIngredientsByItemIndex = Map<Int, List<Long>>

/** Logging and reading journal entries. Delete methods return an undo action. */
class JournalRepository(db: LogMyGutDatabase) {
    private val dao = db.journalDao()

    fun observeDay(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Flow<List<TimelineEntry>> {
        val from = date.atStartOfDay(zone).toInstant()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant()
        return combine(
            dao.observeMealsWithItems(from, to),
            dao.observeBowelMovements(from, to),
            dao.observeSymptoms(from, to),
            dao.observeMedication(from, to),
            ::buildTimeline,
        )
    }

    // Meals

    suspend fun getMeal(id: Long): MealWithItems? = dao.getMealWithItems(id)

    /** Actual ingredient ids per dish item id. */
    suspend fun getMealItemIngredients(itemIds: Collection<Long>): Map<Long, List<Long>> =
        dao.getMealItemIngredients(itemIds).groupBy({ it.mealItemId }, { it.ingredientId })

    suspend fun saveMeal(meal: MealEntity, items: List<MealItemEntity>, dishIngredients: DishIngredientsByItemIndex): Long {
        require(items.isNotEmpty()) { "A meal needs at least one item" }
        return dao.saveMeal(meal, items, dishIngredients)
    }

    suspend fun deleteMeal(id: Long): suspend () -> Unit {
        val snapshot = dao.getMealWithItems(id) ?: return {}
        val items = snapshot.items.sortedBy { it.item.id }.map { it.item }
        val ingredientsByItemId = getMealItemIngredients(items.map { it.id })
        val dishIngredients = items.withIndex()
            .mapNotNull { (index, item) -> ingredientsByItemId[item.id]?.let { index to it } }
            .toMap()
        dao.deleteMeal(id)
        return { dao.restoreMeal(snapshot.meal, items, dishIngredients) }
    }

    // Bowel movements

    suspend fun getBowelMovement(id: Long) = dao.getBowelMovement(id)

    suspend fun saveBowelMovement(entry: BowelMovementEntity) {
        require(entry.bristol in 1..7) { "Bristol type must be 1..7" }
        if (entry.id == 0L) dao.insertBowelMovement(entry) else dao.updateBowelMovement(entry)
    }

    suspend fun deleteBowelMovement(entry: BowelMovementEntity): suspend () -> Unit {
        dao.deleteBowelMovement(entry)
        return { dao.insertBowelMovement(entry) }
    }

    // Symptoms

    suspend fun getSymptomsAt(timestamp: Instant) = dao.getSymptomsAt(timestamp)

    /** Saves a symptom group, replacing the one at [oldTimestamp] when editing. */
    suspend fun saveSymptoms(oldTimestamp: Instant?, entries: List<SymptomEntryEntity>) {
        require(entries.isNotEmpty()) { "At least one symptom is required" }
        require(entries.map { it.timestamp }.distinct().size == 1) { "A symptom group shares one timestamp" }
        require(entries.all { it.severity in 0..10 }) { "Severity must be 0..10" }
        dao.replaceSymptomGroup(oldTimestamp, entries)
    }

    suspend fun deleteSymptomsAt(timestamp: Instant): suspend () -> Unit {
        val snapshot = dao.getSymptomsAt(timestamp)
        dao.deleteSymptomsAt(timestamp)
        return { dao.insertSymptoms(snapshot) }
    }

    // Medication

    suspend fun getMedication(id: Long) = dao.getMedication(id)

    suspend fun saveMedication(entry: MedicationIntakeEntity) {
        val trimmed = entry.copy(name = entry.name.trim())
        require(trimmed.name.isNotEmpty()) { "Medication name is required" }
        if (trimmed.id == 0L) dao.insertMedication(trimmed) else dao.updateMedication(trimmed)
    }

    suspend fun deleteMedication(entry: MedicationIntakeEntity): suspend () -> Unit {
        dao.deleteMedication(entry)
        return { dao.insertMedication(entry) }
    }

    /** Previously used medication names (newest first, case-insensitively unique) with their last effect tag. */
    suspend fun medicationSuggestions(): List<Pair<String, MedEffect?>> =
        dao.getRecentMedication().distinctBy { it.name.lowercase() }.map { it.name to it.effect }
}
