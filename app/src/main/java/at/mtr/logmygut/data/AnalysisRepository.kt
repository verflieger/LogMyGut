package at.mtr.logmygut.data

import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.domain.Amount
import at.mtr.logmygut.domain.AnalysisItem
import at.mtr.logmygut.domain.AnalysisMeal
import at.mtr.logmygut.domain.BowelEvent
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.MedicationEvent
import at.mtr.logmygut.domain.PortionSize
import at.mtr.logmygut.domain.SymptomEvent
import java.time.Instant

/** A meal for lists in the analysis: when, and what (item names in logged order). */
data class MealSummary(val id: Long, val time: Instant, val itemNames: List<String>)

/** Everything the analysis needs, read at one point in time. */
data class AnalysisData(
    val meals: List<AnalysisMeal>,
    val mealSummaries: Map<Long, MealSummary>,
    val tagsOf: Map<Long, List<IngredientTag>>,
    val symptoms: List<SymptomEvent>,
    val bowelMovements: List<BowelEvent>,
    val medication: List<MedicationEvent>,
)

/** Turns the journal into analysis inputs (SPEC 6). Tags are resolved from the foods' current settings (SPEC 4). */
class AnalysisRepository(db: LogMyGutDatabase, private val foods: FoodRepository) {
    private val dao = db.journalDao()

    suspend fun load(): AnalysisData {
        val snapshot = dao.getJournalSnapshot()
        val ingredientsByItem = snapshot.itemIngredients.groupBy({ it.mealItemId }, { it.ingredientId })
        val foodById = foods.getFoods(snapshot.items.map { it.foodId }.distinct()).associateBy { it.id }
        val itemsByMeal = snapshot.items.sortedBy { it.id }.groupBy { it.mealId }

        val meals = snapshot.meals.map { meal ->
            AnalysisMeal(
                id = meal.id,
                time = meal.timestamp,
                items = itemsByMeal[meal.id].orEmpty().map { item ->
                    val food = foodById[item.foodId]
                    val isDish = food?.kind == FoodKind.DISH
                    AnalysisItem(
                        amount = item.count?.let { Amount.Count(it) } ?: Amount.Size(item.size ?: PortionSize.M),
                        ingredientIds = if (isDish) ingredientsByItem[item.id].orEmpty() else listOf(item.foodId),
                        isDish = isDish,
                        isDrink = food?.drink == true,
                    )
                },
            )
        }
        val summaries = snapshot.meals.associate { meal ->
            meal.id to MealSummary(meal.id, meal.timestamp, itemsByMeal[meal.id].orEmpty().mapNotNull { foodById[it.foodId]?.name })
        }
        val ingredientIds = meals.flatMap { meal -> meal.items.flatMap { it.ingredientIds } }.distinct()
        return AnalysisData(
            meals = meals,
            mealSummaries = summaries,
            tagsOf = foods.getIngredientTags(ingredientIds),
            symptoms = snapshot.symptoms.map { SymptomEvent(it.timestamp, it.type, it.severity) },
            bowelMovements = snapshot.bowelMovements.map { BowelEvent(it.timestamp, it.bristol) },
            medication = snapshot.medication.map { MedicationEvent(it.timestamp, it.effect) },
        )
    }
}
