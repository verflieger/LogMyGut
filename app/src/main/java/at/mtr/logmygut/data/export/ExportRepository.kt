package at.mtr.logmygut.data.export

import androidx.room.withTransaction
import at.mtr.logmygut.data.AnalysisRepository
import at.mtr.logmygut.data.FoodRepository
import at.mtr.logmygut.data.db.DailyCheckInEntity
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.domain.Amount
import at.mtr.logmygut.domain.AnalysisParams
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.Hint
import at.mtr.logmygut.domain.MedEffect
import at.mtr.logmygut.domain.PainChange
import at.mtr.logmygut.domain.PortionSize
import at.mtr.logmygut.domain.SymptomType
import at.mtr.logmygut.domain.TriggerResult
import at.mtr.logmygut.domain.analyzeTriggers
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A logged entry as exported (SPEC 7.1): food names resolved, notes included. */
sealed interface ExportEntry {
    val time: Instant
    val zoneId: String
    val note: String?

    data class Meal(
        val id: Long,
        override val time: Instant,
        override val zoneId: String,
        override val note: String?,
        val eatenOut: Boolean,
        val items: List<Item>,
    ) : ExportEntry {
        /** [ingredients]: a dish item's actual ingredients in this meal; [millilitres] for drinks with a known portion. */
        data class Item(
            val name: String,
            val kind: FoodKind,
            val drink: Boolean,
            val count: Int?,
            val unitLabel: String?,
            val size: PortionSize?,
            val millilitres: Double?,
            val ingredients: List<String>,
        )
    }

    data class BowelMovement(
        override val time: Instant,
        override val zoneId: String,
        override val note: String?,
        val bristol: Int,
        val urgent: Boolean,
        val painBefore: Boolean,
        val painAfter: PainChange?,
    ) : ExportEntry

    data class Symptom(
        override val time: Instant,
        override val zoneId: String,
        override val note: String?,
        val type: SymptomType,
        val severity: Int,
    ) : ExportEntry

    data class Medication(
        override val time: Instant,
        override val zoneId: String,
        override val note: String?,
        val name: String,
        val effect: MedEffect?,
    ) : ExportEntry
}

/** Entries in chronological order, plus the daily check-ins. */
data class ExportData(val entries: List<ExportEntry>, val checkIns: List<DailyCheckInEntity>) {
    /** Only entries whose day in [zone] lies in [from]..[to]. */
    fun between(from: LocalDate, to: LocalDate, zone: ZoneId): ExportData = ExportData(
        entries.filter { it.time.atZone(zone).toLocalDate() in from..to },
        checkIns.filter { it.date in from..to },
    )

    /** The first day with an entry or check-in, or null when there is none. */
    fun firstDay(zone: ZoneId): LocalDate? =
        (entries.map { it.time.atZone(zone).toLocalDate() } + checkIns.map { it.date }).minOrNull()

    val isEmpty: Boolean get() = entries.isEmpty() && checkIns.none { it.sleep != null || it.stress != null }
}

/** What the PDF report shows (SPEC 7.1): the period's entries and what stands out in them. */
data class Report(
    val from: LocalDate,
    val to: LocalDate,
    val data: ExportData,
    /** Strong and weak hints from the period's meals, in the analysis' order. */
    val hints: List<TriggerResult>,
    val params: AnalysisParams,
)

/** Reads the journal for the CSV export and the PDF report. */
class ExportRepository(
    private val db: LogMyGutDatabase,
    private val foods: FoodRepository,
    private val analysis: AnalysisRepository,
) {
    private val dao = db.journalDao()

    suspend fun load(): ExportData {
        val (snapshot, checkIns) = db.withTransaction { dao.getJournalSnapshot() to db.backupDao().getCheckIns() }
        val ingredientsByItem = snapshot.itemIngredients.groupBy({ it.mealItemId }, { it.ingredientId })
        val foodIds = (snapshot.items.map { it.foodId } + snapshot.itemIngredients.map { it.ingredientId }).distinct()
        val foodById = foods.getFoods(foodIds).associateBy { it.id }
        val itemsByMeal = snapshot.items.sortedBy { it.id }.groupBy { it.mealId }

        val meals = snapshot.meals.map { meal ->
            ExportEntry.Meal(
                id = meal.id,
                time = meal.timestamp,
                zoneId = meal.zoneId,
                note = meal.note,
                eatenOut = meal.eatenOut,
                items = itemsByMeal[meal.id].orEmpty().mapNotNull { item ->
                    val food = foodById[item.foodId] ?: return@mapNotNull null
                    val amount = item.count?.let(Amount::Count) ?: Amount.Size(item.size ?: PortionSize.M)
                    ExportEntry.Meal.Item(
                        name = food.name,
                        kind = food.kind,
                        drink = food.drink,
                        count = item.count,
                        unitLabel = food.unitLabel,
                        size = item.size,
                        millilitres = if (food.drink) food.portionGrams?.let(amount::grams) else null,
                        ingredients = ingredientsByItem[item.id].orEmpty().mapNotNull { foodById[it]?.name },
                    )
                },
            )
        }
        val entries = meals +
            snapshot.bowelMovements.map {
                ExportEntry.BowelMovement(it.timestamp, it.zoneId, it.note, it.bristol, it.urgent, it.painBefore, it.painAfter)
            } +
            snapshot.symptoms.sortedBy { it.type.ordinal }.map { ExportEntry.Symptom(it.timestamp, it.zoneId, it.note, it.type, it.severity) } +
            snapshot.medication.map { ExportEntry.Medication(it.timestamp, it.zoneId, it.note, it.name, it.effect) }
        // Stable sort keeps a meal before a symptom logged at the same minute, as in the timeline.
        return ExportData(entries.sortedBy { it.time }, checkIns)
    }

    /**
     * The report for [from]..[to] in [zone]. Hints compare only meals from the period; symptoms and bowel movements
     * after it still count for its last meals, as in the analysis tab.
     */
    suspend fun report(from: LocalDate, to: LocalDate, params: AnalysisParams, zone: ZoneId): Report {
        val data = load().between(from, to, zone)
        val all = analysis.load()
        val meals = all.meals.filter { it.time.atZone(zone).toLocalDate() in from..to }
        val hints = analyzeTriggers(meals, all.tagsOf, all.symptoms, all.bowelMovements, all.medication, params)
            .filter { it.hint == Hint.STRONG || it.hint == Hint.WEAK }
        return Report(from, to, data, hints, params)
    }
}
