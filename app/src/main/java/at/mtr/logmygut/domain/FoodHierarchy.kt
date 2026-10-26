package at.mtr.logmygut.domain

/** Something wrong with the parent links of ingredients (SPEC 4.4); [foods] are the foods involved. */
sealed interface ParentProblem {
    val foods: List<Long>

    /** Foods whose parent links form a loop, each one's parent being the next. */
    data class Cycle(override val foods: List<Long>) : ParentProblem

    /** The path from a top-level food down to a food one level deeper than [MaxFoodDepth] allows (the last one). */
    data class TooDeep(override val foods: List<Long>) : ParentProblem
}

/**
 * Problems in [parentOf] (food id → parent id): foods on a cycle, and foods deeper than [MaxFoodDepth] levels, each
 * reported once at the first level too deep (its descendants are deeper too). Parents missing from the map count as
 * top-level foods; foods hanging below a cycle are only reported through the cycle.
 */
fun parentProblems(parentOf: Map<Long, Long?>): List<ParentProblem> {
    val depth = mutableMapOf<Long, Int>()   // the top-level food is 1
    val unrooted = mutableSetOf<Long>()     // on a cycle or below one
    val problems = mutableListOf<ParentProblem>()

    fun pathFromTop(id: Long): List<Long> = generateSequence(id) { parentOf[it] }.take(MaxFoodDepth + 1).toList().asReversed()

    for (start in parentOf.keys) {
        // Walk up until a food already known, the top, or a food seen on this walk (a new cycle).
        val path = mutableListOf<Long>()
        var current: Long? = start
        while (current != null && current !in depth && current !in unrooted && current !in path) {
            path += current
            current = parentOf[current]
        }
        val base = when (current) {
            null -> 0
            in depth -> depth.getValue(current)
            else -> null
        }
        if (base == null) {
            if (current in path) problems += ParentProblem.Cycle(path.subList(path.indexOf(current), path.size).toList())
            unrooted += path
            continue
        }
        path.asReversed().forEachIndexed { i, id ->
            val d = base + i + 1
            depth[id] = d
            if (d == MaxFoodDepth + 1) problems += ParentProblem.TooDeep(pathFromTop(id))
        }
    }
    return problems
}

/**
 * Level of [id] in [parentOf] (a top-level food is 1), or null if its parent chain is longer than [MaxFoodDepth]
 * levels, which includes chains on or below a cycle. Parents missing from the map count as top-level foods.
 */
fun levelOf(parentOf: Map<Long, Long?>, id: Long): Int? =
    generateSequence(id) { parentOf[it] }.take(MaxFoodDepth + 1).count().takeIf { it <= MaxFoodDepth }

/** Where the seed sync puts every ingredient ([target], food id → parent id) and which built-in ones it detaches. */
data class SeedParentPlan(val target: Map<Long, Long?>, val toDetach: Set<Long>)

/**
 * Plans the parents of one seed sync (SPEC 4). [currentParent] holds every ingredient's current parent link,
 * [seedParent] the seed's parent of every food in the seed, [attached] the built-in ingredients whose tags follow the
 * seed. Attached foods move to their seed parent; one is detached instead, staying where it is, when the seed no
 * longer has it or its move takes part in a cycle or a hierarchy deeper than [MaxFoodDepth] levels. A problem no move
 * takes part in was there before and is left alone.
 */
fun planSeedParents(currentParent: Map<Long, Long?>, seedParent: Map<Long, Long?>, attached: Set<Long>): SeedParentPlan {
    // 1. Attached foods the seed no longer has.
    val dropped = attached.filterTo(mutableSetOf()) { it !in seedParent }
    val following = (attached - dropped).toMutableSet()

    // 2. Following foods at their seed parent, all others where they are.
    val target = currentParent.toMutableMap()
    following.forEach { target[it] = seedParent.getValue(it) }

    // 3. For each problem, the moves on its cycle or on its path from the top are not made. Each round undoes at least
    // one move or stops, so the loop ends; for a cycle this may undo more moves than strictly needed.
    val blocked = mutableSetOf<Long>()
    while (true) {
        val candidates = parentProblems(target).flatMapTo(mutableSetOf()) { problem ->
            problem.foods.filter { it in following && target[it] != currentParent[it] }
        }
        if (candidates.isEmpty()) break
        candidates.forEach { target[it] = currentParent[it] }
        following -= candidates
        blocked += candidates
    }
    return SeedParentPlan(target, dropped + blocked)
}
