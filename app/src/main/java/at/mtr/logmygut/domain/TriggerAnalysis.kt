package at.mtr.logmygut.domain

import java.time.Duration
import java.time.Instant

// Trigger → symptom analysis (SPEC 6.3). Pure Kotlin: the data layer turns the database into these inputs.

const val DefaultSymptomThreshold = 4
const val DefaultMinExposures = 5

data class AnalysisParams(
    val windows: Map<Tag, TagWindow> = Tag.entries.associateWith { it.defaultWindow },
    /** A symptom counts as an outcome from this severity on. */
    val symptomThreshold: Int = DefaultSymptomThreshold,
    /** Below this many exposures a tag shows "zu wenig Daten". */
    val minExposures: Int = DefaultMinExposures,
) {
    fun window(tag: Tag): TagWindow = windows[tag] ?: tag.defaultWindow
}

/** What a meal may be followed by: a symptom at or above the threshold, or diarrhoea (Bristol 6–7). */
sealed interface Outcome {
    data class Symptom(val type: SymptomType) : Outcome
    data object Diarrhea : Outcome

    companion object {
        val all: List<Outcome> = SymptomType.entries.map(::Symptom) + Diarrhea
    }
}

/** A logged meal: each item with the ingredients it stands for. */
data class AnalysisMeal(val id: Long, val time: Instant, val items: List<AnalysisItem>)

/**
 * One meal item. [ingredientIds] is the ingredient itself for an ingredient item, or the dish's actual
 * ingredients for this meal (its `MealItemIngredient`s). [amount] is the item's logged amount.
 */
data class AnalysisItem(val amount: Amount, val ingredientIds: List<Long>, val isDish: Boolean, val isDrink: Boolean = false) {
    /** Amount that counts for an ingredient's dose tags (SPEC 4.2): a dish of 2+ pieces counts as 2. */
    val ingredientAmount: Amount get() =
        if (isDish && amount is Amount.Count && amount.n > 2) Amount.Count(2) else amount
}

data class SymptomEvent(val time: Instant, val type: SymptomType, val severity: Int)
data class BowelEvent(val time: Instant, val bristol: Int)
data class MedicationEvent(val time: Instant, val effect: MedEffect?)

enum class Hint { STRONG, WEAK, NONE, INSUFFICIENT }

/** A meal in a result, and whether an outcome followed within the tag's window. */
data class MealOutcome(val mealId: Long, val positive: Boolean)

data class Rate(val positive: Int, val n: Int) {
    val value: Double get() = if (n == 0) 0.0 else positive.toDouble() / n
}

data class TriggerResult(
    val tag: Tag,
    val outcome: Outcome,
    /** Meals with [tag], newest first. */
    val exposures: List<MealOutcome>,
    val control: Rate,
    /**
     * Meals of a tag with [Tag.offsetBy] taken with that medication within ±30 min, kept out of both groups
     * (today only lactose with lactase).
     */
    val withEnzyme: List<MealOutcome>,
    val hint: Hint,
) {
    val exposed: Rate get() = Rate(exposures.count { it.positive }, exposures.size)

    /**
     * rateExposed / rateControl. Infinite when only exposed meals were followed by the outcome;
     * null when there are no controls or neither group had the outcome.
     */
    val ratio: Double? get() = ratioOf(exposed, control)
}

private fun ratioOf(exposed: Rate, control: Rate): Double? = when {
    control.n == 0 || exposed.positive + control.positive == 0 -> null
    control.positive == 0 -> Double.POSITIVE_INFINITY
    else -> exposed.value / control.value
}

/** SPEC 6.3 step 6. */
fun hintFor(exposed: Rate, control: Rate, minExposures: Int): Hint {
    if (exposed.n < minExposures) return Hint.INSUFFICIENT
    val ratio = ratioOf(exposed, control) ?: return Hint.NONE
    return when {
        exposed.n >= 10 && ratio >= 2.0 && exposed.value - control.value >= 0.25 -> Hint.STRONG
        ratio >= 1.5 -> Hint.WEAK
        else -> Hint.NONE
    }
}

/** A [Tag.offsetBy] medication taken this close to a meal (before or after) offsets the tag (SPEC 6.3 step 7). */
private val OffsetRange: Duration = Duration.ofMinutes(30)
private val AntidiarrhealRange: Duration = Duration.ofHours(24)

/**
 * Runs the analysis for every tag and outcome, sorted by hint strength, then ratio.
 * [tagsOf] holds each ingredient's resolved tags (inheritance already applied).
 * Entries of only drinks without any trigger (a glass of water) are not meals: as controls they would
 * make every tag look worse than it is.
 */
fun analyzeTriggers(
    meals: List<AnalysisMeal>,
    tagsOf: Map<Long, List<IngredientTag>>,
    symptoms: List<SymptomEvent>,
    bowelMovements: List<BowelEvent>,
    medication: List<MedicationEvent>,
    params: AnalysisParams = AnalysisParams(),
): List<TriggerResult> {
    val tagsByMeal = meals.associate { it.id to mealTags(it, tagsOf) }
    val sortedMeals = meals
        .filterNot { meal -> meal.items.all { it.isDrink } && tagsByMeal.getValue(meal.id).isEmpty() }
        .sortedByDescending { it.time }
    val medicationTimes = medication.groupBy({ it.effect }, { it.time }).mapValues { (_, times) -> times.sorted() }
    val antidiarrhealTimes = medicationTimes[MedEffect.ANTIDIARRHEAL].orEmpty()

    val eventTimes: Map<Outcome, List<Instant>> = Outcome.all.associateWith { outcome ->
        when (outcome) {
            is Outcome.Symptom -> symptoms.filter { it.type == outcome.type && it.severity >= params.symptomThreshold }.map { it.time }
            Outcome.Diarrhea -> bowelMovements.filter { it.bristol >= 6 }.map { it.time }
        }.sorted()
    }

    return Tag.entries.flatMap { tag ->
        val window = params.window(tag)
        val offsetTimes = tag.offsetBy?.let { medicationTimes[it] }.orEmpty()
        Outcome.all.map { outcome ->
            val events = eventTimes.getValue(outcome)
            val exposures = mutableListOf<MealOutcome>()
            val withEnzyme = mutableListOf<MealOutcome>()
            var controlN = 0
            var controlPositive = 0
            for (meal in sortedMeals) {
                if (outcome == Outcome.Diarrhea && antidiarrhealTimes.anyIn(meal.time - AntidiarrhealRange, meal.time)) continue
                val positive = events.anyIn(meal.time + window.from, meal.time + window.to)
                when {
                    tag !in tagsByMeal.getValue(meal.id) -> {
                        controlN++
                        if (positive) controlPositive++
                    }
                    offsetTimes.anyIn(meal.time - OffsetRange, meal.time + OffsetRange) ->
                        withEnzyme += MealOutcome(meal.id, positive)
                    else -> exposures += MealOutcome(meal.id, positive)
                }
            }
            val control = Rate(controlPositive, controlN)
            val exposed = Rate(exposures.count { it.positive }, exposures.size)
            TriggerResult(tag, outcome, exposures, control, withEnzyme, hintFor(exposed, control, params.minExposures))
        }
    }.sortedWith(compareBy<TriggerResult> { it.hint }.thenByDescending { it.ratio ?: -1.0 }.thenByDescending { it.exposures.size })
}

/** Tags that effectively apply to a meal (SPEC 6.3 step 1), dose thresholds included. */
fun mealTags(meal: AnalysisMeal, tagsOf: Map<Long, List<IngredientTag>>): Set<Tag> =
    meal.items.flatMapTo(mutableSetOf()) { item ->
        item.ingredientIds.flatMap { effectiveTags(tagsOf[it].orEmpty(), item.ingredientAmount) }
    }

/** Whether this sorted list has an instant in [from, to]. */
private fun List<Instant>.anyIn(from: Instant, to: Instant): Boolean {
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (this[mid] < from) low = mid + 1 else high = mid
    }
    return low < size && this[low] <= to
}
