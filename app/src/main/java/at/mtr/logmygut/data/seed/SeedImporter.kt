package at.mtr.logmygut.data.seed

import androidx.room.withTransaction
import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.data.db.FoodTagEntity
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.Threshold
import at.mtr.logmygut.domain.normalizeForSearch

/**
 * Adds built-in foods that aren't in the database yet (matched by seed key).
 * Idempotent and safe to run on every start: existing entries, including the user's edits, are never touched (SPEC 4).
 */
class SeedImporter(private val db: LogMyGutDatabase) {

    /** Returns the number of foods inserted. */
    suspend fun import(seed: SeedFile): Int = db.withTransaction {
        val dao = db.foodDao()
        val existing = dao.getSeedKeys().toSet()
        var inserted = 0

        for (ingredient in seed.ingredients.filterNot { it.name in existing }) {
            dao.insertFoodWithDetails(
                food = FoodEntity(
                    kind = FoodKind.INGREDIENT,
                    name = ingredient.name,
                    aliases = ingredient.aliases,
                    unit = ingredient.unit,
                    unitLabel = ingredient.unitLabel,
                    note = ingredient.note,
                    builtIn = true,
                    seedKey = ingredient.name,
                    searchText = searchTextFor(ingredient.name, ingredient.aliases),
                ),
                tags = ingredient.tags.map { it.toEntity() },
                ingredientIds = emptyList(),
            )
            inserted++
        }

        // Dishes after ingredients, so every referenced ingredient has an id.
        val idBySeedKey = dao.getSeedKeyIds().associate { it.seedKey to it.id }
        for (dish in seed.dishes.filterNot { it.name in existing }) {
            dao.insertFoodWithDetails(
                food = FoodEntity(
                    kind = FoodKind.DISH,
                    name = dish.name,
                    aliases = dish.aliases,
                    unit = dish.unit,
                    unitLabel = dish.unitLabel,
                    builtIn = true,
                    seedKey = dish.name,
                    searchText = searchTextFor(dish.name, dish.aliases),
                ),
                tags = emptyList(),
                ingredientIds = dish.ingredients.map { idBySeedKey.getValue(it) },
            )
            inserted++
        }
        inserted
    }
}

/** Search column content: normalized name and aliases, separated so matches don't span entries. */
fun searchTextFor(name: String, aliases: List<String>): String =
    (listOf(name) + aliases).joinToString("|") { normalizeForSearch(it) }

internal fun IngredientTag.toEntity(foodId: Long = 0) = FoodTagEntity(
    foodId = foodId,
    tag = tag,
    minSize = (threshold as? Threshold.MinSize)?.size,
    minCount = (threshold as? Threshold.MinCount)?.n,
)
