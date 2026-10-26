package at.mtr.logmygut.domain

/** How much of something was eaten: a piece count or a portion size. */
sealed interface Amount {
    data class Count(val n: Int) : Amount {
        init { require(n >= 1) { "Count must be >= 1, was $n" } }
    }
    data class Size(val size: PortionSize) : Amount

    /** Size view, used when comparing against a size threshold (SPEC 4.2): 1 → M, 2+ → L. */
    fun asSize(): PortionSize = when (this) {
        is Size -> size
        is Count -> if (n >= 2) PortionSize.L else PortionSize.M
    }

    /** Grams eaten, given the food's [portionGrams] (M portion, or one piece for counts). */
    fun grams(portionGrams: Double): Double = when (this) {
        is Count -> n * portionGrams
        is Size -> size.factor * portionGrams
    }

    /** Count view, used when comparing against a count threshold (SPEC 4.2): S, M → 1, L → 2. */
    fun asCount(): Int = when (this) {
        is Count -> n
        is Size -> if (size == PortionSize.L) 2 else 1
    }
}

/** Minimum amount from which a dose-dependent tag applies. */
sealed interface Threshold {
    data class MinSize(val size: PortionSize) : Threshold
    data class MinCount(val n: Int) : Threshold

    fun isReachedBy(amount: Amount): Boolean = when (this) {
        is MinSize -> amount.asSize() >= size
        is MinCount -> amount.asCount() >= n
    }
}

/** A tag on an ingredient; [threshold] null means it always applies. */
data class IngredientTag(val tag: Tag, val threshold: Threshold? = null)

/** Who decided a stored tag row: the seed file or the user (SPEC 4). */
enum class TagSource { SEED, USER }

/**
 * A stored tag row of an ingredient: a tag with an optional [threshold], or with [removed] a cancelled inherited tag
 * (which carries no threshold). Built-in foods that follow the seed have [TagSource.SEED] rows plus [TagSource.USER]
 * rows for the user's differences; all other foods only have user rows.
 */
data class TagRow(val tag: Tag, val threshold: Threshold?, val removed: Boolean, val source: TagSource) {
    init { require(!removed || threshold == null) { "Removed tag ${tag.key} has a threshold" } }
}

/**
 * Tags that actually apply to an ingredient eaten in [amount].
 *
 * For an ingredient inside a dish, pass the dish's amount. The conversions in [Amount]
 * implement the mapping table in SPEC section 4.2.
 */
fun effectiveTags(tags: Collection<IngredientTag>, amount: Amount): Set<Tag> =
    tags.filter { it.threshold?.isReachedBy(amount) ?: true }.mapTo(mutableSetOf()) { it.tag }

/** Maximum depth of the food hierarchy (SPEC 4.4): a food, its parent and grandparent. */
const val MaxFoodDepth = 3

/**
 * Tags of a food in the hierarchy (SPEC 4.4): the parent's resolved tags, with the food's own
 * entries replacing inherited ones of the same tag, and [removed] tags dropped.
 */
fun inheritTags(parent: List<IngredientTag>, own: List<IngredientTag>, removed: Collection<Tag>): List<IngredientTag> {
    val byTag = parent.associateByTo(linkedMapOf()) { it.tag }
    removed.forEach { byTag.remove(it) }
    own.forEach { byTag[it.tag] = it }
    return byTag.values.toList()
}

/** A food's own tags and removals: per tag, a user row wins over the seed row. */
fun ownRows(rows: List<TagRow>): List<TagRow> =
    rows.groupBy { it.tag }.values.map { sameTag -> sameTag.firstOrNull { it.source == TagSource.USER } ?: sameTag.first() }

/** Resolved tags of a food with stored [rows] under a parent whose resolved tags are [parentTags] (SPEC 4.4). */
fun resolveTags(parentTags: List<IngredientTag>, rows: List<TagRow>): List<IngredientTag> {
    val (removed, own) = ownRows(rows).partition { it.removed }
    return inheritTags(parentTags, own.map { IngredientTag(it.tag, it.threshold) }, removed.map { it.tag })
}

/**
 * Resolved tags of each of [ids] (SPEC 4.4), with [parentOf] giving the parent links and [rowsOf] the stored rows of
 * the foods and their ancestors. Depth is capped at [MaxFoodDepth], so a bad parent link can't loop.
 */
fun resolveTags(ids: Collection<Long>, parentOf: Map<Long, Long?>, rowsOf: Map<Long, List<TagRow>>): Map<Long, List<IngredientTag>> {
    val resolved = mutableMapOf<Long, List<IngredientTag>>()
    fun resolve(id: Long, depth: Int): List<IngredientTag> = resolved[id] ?: run {
        val parent = parentOf[id]?.takeIf { depth < MaxFoodDepth - 1 }?.let { resolve(it, depth + 1) }.orEmpty()
        resolveTags(parent, rowsOf[id].orEmpty()).also { resolved[id] = it }
    }
    return ids.associateWith { resolve(it, 0) }
}

/**
 * User rows so that inheriting from a parent with resolved [parentTags] yields exactly [edited]: the full own list of
 * a user-created or detached food.
 */
fun fullRowsFor(edited: List<IngredientTag>, parentTags: List<IngredientTag>): List<TagRow> {
    val own = edited.filter { it !in parentTags }.map { TagRow(it.tag, it.threshold, removed = false, TagSource.USER) }
    val removed = parentTags.filter { inherited -> edited.none { it.tag == inherited.tag } }
        .map { TagRow(it.tag, threshold = null, removed = true, TagSource.USER) }
    return own + removed
}

/**
 * User rows of a built-in food that follows the seed, saved with the tags [edited]: only the differences from its
 * [seedRows] resolved under the parent's [parentTags], so an unchanged save stores nothing.
 */
fun userRowsFor(edited: List<IngredientTag>, seedRows: List<TagRow>, parentTags: List<IngredientTag>): List<TagRow> =
    fullRowsFor(edited, parentTags = resolveTags(parentTags, seedRows))
