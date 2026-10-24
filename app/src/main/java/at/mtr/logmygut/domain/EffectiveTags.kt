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

/**
 * Tags that actually apply to an ingredient eaten in [amount].
 *
 * For an ingredient inside a dish, pass the dish's amount. The conversions in [Amount]
 * implement the mapping table in SPEC section 4.2.
 */
fun effectiveTags(tags: Collection<IngredientTag>, amount: Amount): Set<Tag> =
    tags.filter { it.threshold?.isReachedBy(amount) ?: true }.mapTo(mutableSetOf()) { it.tag }
