package at.mtr.logmygut.data.seed

import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.TagRow
import at.mtr.logmygut.domain.TagSource
import at.mtr.logmygut.domain.Threshold
import at.mtr.logmygut.domain.inheritTags
import at.mtr.logmygut.domain.resolveTags

/** Another food as [seedDifferences] needs it: its seed key (null for a user-created one) and current name. */
data class FoodRef(val seedKey: String?, val name: String)

/**
 * A built-in food as stored, with what [seedDifferences] needs about its relatives: its own tag [rows], the resolved
 * tags of its current parent ([parentTags]) and the parent itself, and for a dish its recipe.
 */
data class StoredBuiltIn(
    val food: FoodEntity,
    val rows: List<TagRow> = emptyList(),
    val parentTags: List<IngredientTag> = emptyList(),
    val parent: FoodRef? = null,
    val recipe: List<FoodRef> = emptyList(),
)

/** One way a built-in food differs from its seed entry ("Änderungen gegenüber Standard", SPEC 2). */
sealed interface SeedDifference {
    data class Renamed(val seedName: String) : SeedDifference
    data object Note : SeedDifference
    data object PortionWeight : SeedDifference
    /** Marked as a drink, the seed's entry isn't one. */
    data object MarkedDrink : SeedDifference
    /** No longer marked as a drink, the seed's entry is one. */
    data object UnmarkedDrink : SeedDifference
    data object UnitChanged : SeedDifference
    data object Aliases : SeedDifference
    /** Names of the current and the seed parent; null for none. */
    data class Parent(val current: String?, val seed: String?) : SeedDifference
    data class RecipeAdded(val names: List<String>) : SeedDifference
    data class RecipeRemoved(val names: List<String>) : SeedDifference
    data class TagAdded(val tag: Tag, val threshold: Threshold?) : SeedDifference
    data class TagRemoved(val tag: Tag) : SeedDifference
    /** The tag stays, from another amount ([threshold] null: always). */
    data class TagThreshold(val tag: Tag, val threshold: Threshold?) : SeedDifference
    data object NotInSeed : SeedDifference
    data object Detached : SeedDifference
}

/** A built-in food on the changes screen with how it differs from its seed entry; empty: no visible difference. */
data class SeedChangesRow(val food: FoodEntity, val differences: List<SeedDifference>)

/**
 * How [stored] differs from its entry in [seed], named in [language] like the import does ([searchableText]).
 * Tags of an attached food compare its user rows with its seed rows, both under the current parent; a detached
 * food's resolved tags are compared with what the seed alone would give. Empty when nothing visible differs.
 */
fun seedDifferences(stored: StoredBuiltIn, seed: SeedFile, language: String): List<SeedDifference> {
    val food = stored.food
    val seedFood = seed.find(food)
    val detached = listOfNotNull(SeedDifference.Detached.takeIf { food.tagsDetached })
    if (seedFood == null) return listOf(SeedDifference.NotInSeed) + detached

    val text = seedFood.searchableText(language)
    val differences = mutableListOf<SeedDifference>()
    if (food.name != text.name) differences += SeedDifference.Renamed(text.name)
    // The names themselves are covered by Renamed, so either one may sit among the aliases.
    fun aliasSet(aliases: List<String>) =
        aliases.map { it.lowercase() }.toSet() - setOf(food.name.lowercase(), text.name.lowercase())
    if (aliasSet(food.aliases) != aliasSet(text.aliases)) differences += SeedDifference.Aliases
    // An edited food keeps its note when the language changes, so the seed's note in any language is unchanged.
    val seedNotes = (listOf(seedFood.note) + seedFood.translations.values.map { it.note }).mapTo(mutableSetOf()) { it.orEmpty() }
    if (food.note.orEmpty() !in seedNotes) differences += SeedDifference.Note
    if (food.unit != seedFood.unit || food.unitLabel != seedFood.unitLabel) differences += SeedDifference.UnitChanged
    if (food.portionGrams != seedFood.grams) differences += SeedDifference.PortionWeight
    if (food.drink != seedFood.drink) {
        differences += if (food.drink) SeedDifference.MarkedDrink else SeedDifference.UnmarkedDrink
    }

    when (seedFood) {
        is SeedIngredient -> {
            // A user-created parent has no seed key, so it never matches.
            val parentChanged = stored.parent?.let { it.seedKey == null || it.seedKey != seedFood.parent } ?: (seedFood.parent != null)
            if (parentChanged) {
                val seedParent = seedFood.parent?.let { seed.ingredient(it).text(language).name }
                differences += SeedDifference.Parent(stored.parent?.name, seedParent)
            }
            val actual = resolveTags(stored.parentTags, stored.rows)
            val base = if (food.tagsDetached) seed.resolvedTags(seedFood) else
                resolveTags(stored.parentTags, stored.rows.filter { it.source == TagSource.SEED })
            differences += tagDifferences(base, actual)
        }
        is SeedDish -> {
            val seedKeys = seedFood.ingredients.toSet()
            val added = stored.recipe.filter { it.seedKey !in seedKeys }.map { it.name }
            val currentKeys = stored.recipe.mapNotNull { it.seedKey }.toSet()
            val removed = seedFood.ingredients.filter { it !in currentKeys }
                .map { seed.ingredient(it).text(language).name }
            if (added.isNotEmpty()) differences += SeedDifference.RecipeAdded(added)
            if (removed.isNotEmpty()) differences += SeedDifference.RecipeRemoved(removed)
        }
    }
    return differences + detached
}

/** Added, removed and re-dosed tags from [base] to [actual], in tag order. */
private fun tagDifferences(base: List<IngredientTag>, actual: List<IngredientTag>): List<SeedDifference> {
    val before = base.associateBy { it.tag }
    val after = actual.associateBy { it.tag }
    return Tag.entries.mapNotNull { tag ->
        val old = before[tag]
        val new = after[tag]
        when {
            old == null && new != null -> SeedDifference.TagAdded(tag, new.threshold)
            old != null && new == null -> SeedDifference.TagRemoved(tag)
            old != null && new != null && old.threshold != new.threshold -> SeedDifference.TagThreshold(tag, new.threshold)
            else -> null
        }
    }
}

/** The tags [ingredient] resolves to within the seed alone (SPEC 4.4); the seed's hierarchy is validated on parse. */
private fun SeedFile.resolvedTags(ingredient: SeedIngredient): List<IngredientTag> {
    val parent = ingredient.parent?.let(::ingredient)
    return inheritTags(parent?.let(::resolvedTags).orEmpty(), ingredient.tags, ingredient.removedTags)
}
