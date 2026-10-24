package at.mtr.logmygut.data.seed

import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.PortionSize
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.Threshold
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Serializable
private data class SeedFileJson(
    val version: Int,
    val tags: List<String>,
    val ingredients: List<IngredientJson>,
    val dishes: List<DishJson>,
)

@Serializable
private data class IngredientJson(
    val name: String,
    val aliases: List<String> = emptyList(),
    val unit: String,
    val unitLabel: String? = null,
    val tags: List<String>,
    val doseTags: Map<String, JsonPrimitive> = emptyMap(),
    val note: String? = null,
)

@Serializable
private data class DishJson(
    val name: String,
    val aliases: List<String> = emptyList(),
    val unit: String,
    val unitLabel: String? = null,
    val ingredients: List<String>,
)

data class SeedIngredient(
    val name: String,
    val aliases: List<String>,
    val unit: FoodUnit,
    val unitLabel: String?,
    val tags: List<IngredientTag>,
    val note: String?,
)

data class SeedDish(
    val name: String,
    val aliases: List<String>,
    val unit: FoodUnit,
    val unitLabel: String?,
    /** Names of [SeedIngredient]s (their seed keys). */
    val ingredients: List<String>,
)

/** Parsed and validated `foods.de.json`. Names are the stable seed keys. */
data class SeedFile(val version: Int, val ingredients: List<SeedIngredient>, val dishes: List<SeedDish>) {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses and validates; throws [IllegalArgumentException] describing the first problem found. */
        fun parse(text: String): SeedFile {
            val raw = json.decodeFromString<SeedFileJson>(text)
            val ingredients = raw.ingredients.map(::toIngredient)
            val dishes = raw.dishes.map(::toDish)

            val ingredientNames = ingredients.mapTo(mutableSetOf()) { it.name }
            for (dish in dishes) {
                val missing = dish.ingredients.filterNot { it in ingredientNames }
                require(missing.isEmpty()) { "Dish '${dish.name}' references unknown ingredients $missing" }
            }
            val allNames = (ingredients.map { listOf(it.name) + it.aliases } + dishes.map { listOf(it.name) + it.aliases })
                .flatten().map { it.lowercase() }
            val duplicates = allNames.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            require(duplicates.isEmpty()) { "Duplicate names/aliases: $duplicates" }

            return SeedFile(raw.version, ingredients, dishes)
        }

        private fun toIngredient(j: IngredientJson): SeedIngredient {
            val unit = parseUnit(j.name, j.unit, j.unitLabel)
            val always = j.tags.map { IngredientTag(Tag.fromKey(it)) }
            val dosed = j.doseTags.map { (key, value) ->
                val threshold = when (unit) {
                    FoodUnit.SIZE -> Threshold.MinSize(
                        PortionSize.entries.firstOrNull { it.name == value.content && it != PortionSize.S }
                            ?: throw IllegalArgumentException("'${j.name}': size threshold must be M or L, was $value"),
                    )
                    FoodUnit.COUNT -> Threshold.MinCount(
                        value.intOrNull?.takeIf { it >= 2 }
                            ?: throw IllegalArgumentException("'${j.name}': count threshold must be an int >= 2, was $value"),
                    )
                }
                IngredientTag(Tag.fromKey(key), threshold)
            }
            val tagKeys = (always + dosed).map { it.tag }
            require(tagKeys.size == tagKeys.toSet().size) { "'${j.name}': tag listed twice" }
            return SeedIngredient(j.name, j.aliases, unit, j.unitLabel, always + dosed, j.note)
        }

        private fun toDish(j: DishJson): SeedDish {
            require(j.ingredients.isNotEmpty()) { "Dish '${j.name}' has no ingredients" }
            return SeedDish(j.name, j.aliases, parseUnit(j.name, j.unit, j.unitLabel), j.unitLabel, j.ingredients)
        }

        private fun parseUnit(name: String, unit: String, unitLabel: String?): FoodUnit {
            val parsed = when (unit) {
                "count" -> FoodUnit.COUNT
                "size" -> FoodUnit.SIZE
                else -> throw IllegalArgumentException("'$name': unknown unit '$unit'")
            }
            require((parsed == FoodUnit.COUNT) == (unitLabel != null)) { "'$name': unitLabel required exactly for count units" }
            return parsed
        }
    }
}
