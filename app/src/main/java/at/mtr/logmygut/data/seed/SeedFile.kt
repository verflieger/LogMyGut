package at.mtr.logmygut.data.seed

import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.MaxFoodDepth
import at.mtr.logmygut.domain.PortionSize
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.Threshold
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.Locale

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
    val key: String? = null,
    val aliases: List<String> = emptyList(),
    val unit: String,
    val unitLabel: String? = null,
    val tags: List<String>,
    val doseTags: Map<String, JsonPrimitive> = emptyMap(),
    val note: String? = null,
    val parent: String? = null,
    val removeTags: List<String> = emptyList(),
    val grams: Double? = null,
    val drink: Boolean = false,
    val en: TextJson? = null,
)

@Serializable
private data class TextJson(val name: String, val aliases: List<String> = emptyList(), val note: String? = null)

@Serializable
private data class DishJson(
    val name: String,
    val key: String? = null,
    val aliases: List<String> = emptyList(),
    val unit: String,
    val unitLabel: String? = null,
    val ingredients: List<String>,
    val grams: Double? = null,
    val drink: Boolean = false,
    val en: TextJson? = null,
)

/** Languages the seed names foods in. German is the source ([SeedFood.name] etc.), the others are translations. */
val SeedLanguages = listOf("de", "en")

/** The seed language for [locale]: its own when the seed has it, otherwise English. */
fun seedLanguage(locale: Locale): String = locale.language.takeIf { it in SeedLanguages } ?: "en"

/** A food's name, aliases and note in one language. */
data class SeedText(val name: String, val aliases: List<String>, val note: String? = null)

/** What ingredients and dishes share: a permanent key and their texts in each language. */
sealed interface SeedFood {
    val key: String
    val name: String
    val aliases: List<String>
    val note: String?
    /** Texts in the other [SeedLanguages], by language code. */
    val translations: Map<String, SeedText>

    /** The food's texts in [language]; German when there is no translation. */
    fun text(language: String): SeedText = translations[language] ?: SeedText(name, aliases, note)

    /** Every name and alias in every language. */
    fun allNames(): List<String> = listOf(name) + aliases + translations.values.flatMap { listOf(it.name) + it.aliases }
}

data class SeedIngredient(
    override val name: String,
    override val aliases: List<String>,
    val unit: FoodUnit,
    val unitLabel: String?,
    /** Own tags; inherited ones from [parent] are not repeated here. */
    val tags: List<IngredientTag>,
    override val note: String?,
    /** Seed key of the parent ingredient (SPEC 4.4), or null. */
    val parent: String? = null,
    /** Inherited tags this ingredient does not have. */
    val removedTags: List<Tag> = emptyList(),
    /** Permanent seed key; the original name, kept when the food is renamed. */
    override val key: String = name,
    /** Grams of an M portion, or of one piece for count units; millilitres for drinks. */
    val grams: Double? = null,
    val drink: Boolean = false,
    override val translations: Map<String, SeedText> = emptyMap(),
) : SeedFood

data class SeedDish(
    override val name: String,
    override val aliases: List<String>,
    val unit: FoodUnit,
    val unitLabel: String?,
    /** Seed keys of [SeedIngredient]s. */
    val ingredients: List<String>,
    override val key: String = name,
    /** Millilitres of one portion, for drinks (dishes otherwise have no weight). */
    val grams: Double? = null,
    val drink: Boolean = false,
    override val translations: Map<String, SeedText> = emptyMap(),
) : SeedFood {
    override val note: String? get() = null
}

/** Parsed and validated `foods.json`. Seed keys (original names) identify entries across versions. */
data class SeedFile(val version: Int, val ingredients: List<SeedIngredient>, val dishes: List<SeedDish>) {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses and validates; throws [IllegalArgumentException] describing the first problem found. */
        fun parse(text: String): SeedFile {
            val raw = json.decodeFromString<SeedFileJson>(text)
            val ingredients = raw.ingredients.map(::toIngredient)
            val dishes = raw.dishes.map(::toDish)

            val ingredientKeys = ingredients.mapTo(mutableSetOf()) { it.key }
            val allKeys = ingredients.map { it.key } + dishes.map { it.key }
            require(allKeys.size == allKeys.toSet().size) { "Duplicate seed keys: ${allKeys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}" }
            validateHierarchy(ingredients)
            for (dish in dishes) {
                val missing = dish.ingredients.filterNot { it in ingredientKeys }
                require(missing.isEmpty()) { "Dish '${dish.name}' references unknown ingredients $missing" }
            }
            // Within each language, a name or alias belongs to one food, so a search term is unambiguous.
            val foods: List<SeedFood> = ingredients + dishes
            for (language in SeedLanguages) {
                val names = foods.flatMap { food -> food.text(language).let { listOf(it.name) + it.aliases } }.map { it.lowercase() }
                val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
                require(duplicates.isEmpty()) { "Duplicate names/aliases ($language): $duplicates" }
            }

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
            val removed = j.removeTags.map { Tag.fromKey(it) }
            require(removed.none { it in tagKeys }) { "'${j.name}': tag both set and removed" }
            require(j.grams == null || j.grams > 0) { "'${j.name}': grams must be positive" }
            return SeedIngredient(
                j.name, j.aliases, unit, j.unitLabel, always + dosed, j.note, j.parent, removed, j.key ?: j.name, j.grams, j.drink,
                translations(j.en),
            )
        }

        private fun validateHierarchy(ingredients: List<SeedIngredient>) {
            val byKey = ingredients.associateBy { it.key }
            for (ingredient in ingredients) {
                var current = ingredient
                var depth = 1
                while (true) {
                    val parentName = current.parent ?: break
                    current = byKey[parentName] ?: throw IllegalArgumentException("'${ingredient.name}': unknown parent '$parentName'")
                    depth++
                    require(depth <= MaxFoodDepth) { "'${ingredient.name}': hierarchy deeper than $MaxFoodDepth (or a cycle)" }
                }
                require(ingredient.removedTags.isEmpty() || ingredient.parent != null) { "'${ingredient.name}': removeTags without parent" }
            }
        }

        private fun toDish(j: DishJson): SeedDish {
            require(j.ingredients.isNotEmpty()) { "Dish '${j.name}' has no ingredients" }
            require(j.grams == null || j.grams > 0) { "Dish '${j.name}': grams must be positive" }
            return SeedDish(
                j.name, j.aliases, parseUnit(j.name, j.unit, j.unitLabel), j.unitLabel, j.ingredients, j.key ?: j.name, j.grams, j.drink,
                translations(j.en),
            )
        }

        private fun translations(en: TextJson?): Map<String, SeedText> =
            en?.let { mapOf("en" to SeedText(it.name, it.aliases, it.note)) }.orEmpty()

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
