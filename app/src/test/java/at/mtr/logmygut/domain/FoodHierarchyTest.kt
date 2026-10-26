package at.mtr.logmygut.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FoodHierarchyTest {
    @Test fun validHierarchyHasNoProblems() {
        // 1 ← 2 ← 3, and 4 on its own; 9 is a parent missing from the map.
        assertEquals(emptyList<ParentProblem>(), parentProblems(mapOf(1L to null, 2L to 1L, 3L to 2L, 4L to null, 5L to 9L)))
    }

    @Test fun cycleIsReportedOnceWithItsFoods() {
        // 1 → 2 → 3 → 1, with 4 hanging below the cycle.
        val problems = parentProblems(mapOf(1L to 2L, 2L to 3L, 3L to 1L, 4L to 1L))
        val cycle = problems.single() as ParentProblem.Cycle
        assertEquals(setOf(1L, 2L, 3L), cycle.foods.toSet())
    }

    @Test fun selfParentIsACycle() {
        assertEquals(listOf(ParentProblem.Cycle(listOf(1L))), parentProblems(mapOf(1L to 1L)))
    }

    @Test fun tooDeepReportsThePathFromTheTop() {
        // 1 ← 2 ← 3 ← 4 ← 5: 4 is the first food too deep; 5 is covered by it.
        val problems = parentProblems(mapOf(5L to 4L, 4L to 3L, 3L to 2L, 2L to 1L, 1L to null))
        assertEquals(listOf(ParentProblem.TooDeep(listOf(1L, 2L, 3L, 4L))), problems)
    }

    @Test fun movingAFoodCountsItsDescendants() {
        // 10 ← 11 ← 12 is fine; 10 under 1 ← 2 makes 12 level 5 and 11 level 4.
        val map = mapOf(1L to null, 2L to 1L, 10L to null, 11L to 10L, 12L to 11L)
        assertEquals(emptyList<ParentProblem>(), parentProblems(map))
        val problems = parentProblems(map + (10L to 2L))
        assertEquals(listOf(ParentProblem.TooDeep(listOf(1L, 2L, 10L, 11L))), problems)
    }

    @Test fun swapIsFineInTheFinalMap() {
        // Before: A (1) under B (2). After: B under A. Only the final map counts.
        assertEquals(emptyList<ParentProblem>(), parentProblems(mapOf(1L to 2L, 2L to null)))
        assertEquals(emptyList<ParentProblem>(), parentProblems(mapOf(1L to null, 2L to 1L)))
    }

    @Test fun preExistingProblemsAreReportedAlongsideNewOnes() {
        // A cycle 1 ↔ 2 that was already there and an unrelated too-deep chain; both reported, nothing loops.
        val problems = parentProblems(mapOf(1L to 2L, 2L to 1L, 3L to null, 4L to 3L, 5L to 4L, 6L to 5L))
        assertEquals(2, problems.size)
        assertEquals(setOf(1L, 2L), problems.filterIsInstance<ParentProblem.Cycle>().single().foods.toSet())
        assertEquals(listOf(3L, 4L, 5L, 6L), problems.filterIsInstance<ParentProblem.TooDeep>().single().foods)
    }

    @Test fun seedSwapMovesBothAndDetachesNothing() {
        // Before: 1 under 2. The seed puts 2 under 1.
        val plan = planSeedParents(mapOf(1L to 2L, 2L to null), mapOf(1L to null, 2L to 1L), attached = setOf(1L, 2L))
        assertEquals(SeedParentPlan(mapOf(1L to null, 2L to 1L), emptySet()), plan)
    }

    @Test fun seedMoveClosingACycleIsNotMade() {
        // 1 is detached below 2; the seed puts 2 under 1.
        val plan = planSeedParents(mapOf(1L to 2L, 2L to null), mapOf(1L to null, 2L to 1L), attached = setOf(2L))
        assertEquals(SeedParentPlan(mapOf(1L to 2L, 2L to null), setOf(2L)), plan)
    }

    @Test fun seedMoveTooDeepIsNotMade() {
        // 10 ← 11 ← 12 (12 user-created); the seed puts 10 under 1 ← 2, which would put 12 on level 5.
        val current = mapOf(1L to null, 2L to 1L, 10L to null, 11L to 10L, 12L to 11L)
        val plan = planSeedParents(current, mapOf(1L to null, 2L to 1L, 10L to 2L, 11L to 10L), attached = setOf(1L, 2L, 10L, 11L))
        assertEquals(SeedParentPlan(current, setOf(10L)), plan)
    }

    @Test fun foodNoLongerInTheSeedIsDetachedWhereItIs() {
        val plan = planSeedParents(mapOf(1L to null, 2L to 1L), mapOf(1L to null), attached = setOf(1L, 2L))
        assertEquals(SeedParentPlan(mapOf(1L to null, 2L to 1L), setOf(2L)), plan)
    }

    @Test fun problemWithoutCandidatesIsLeftAloneAndTheLoopEnds() {
        // A cycle 1 ↔ 2 of detached foods was already there; the seed's unrelated move of 3 under 4 goes ahead.
        val plan = planSeedParents(
            mapOf(1L to 2L, 2L to 1L, 3L to null, 4L to null),
            mapOf(1L to null, 2L to null, 3L to 4L, 4L to null),
            attached = setOf(3L, 4L),
        )
        assertEquals(SeedParentPlan(mapOf(1L to 2L, 2L to 1L, 3L to 4L, 4L to null), emptySet()), plan)
    }

    @Test fun attachedFoodOnAnOldCycleWithoutAMoveStays() {
        // 1 ↔ 2 was already a cycle and 1 is attached, but its seed parent is where it is: nothing to undo.
        val plan = planSeedParents(mapOf(1L to 2L, 2L to 1L), mapOf(1L to 2L), attached = setOf(1L))
        assertEquals(SeedParentPlan(mapOf(1L to 2L, 2L to 1L), emptySet()), plan)
    }
}
