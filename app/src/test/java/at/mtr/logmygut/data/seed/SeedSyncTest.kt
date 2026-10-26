package at.mtr.logmygut.data.seed

import at.mtr.logmygut.data.FoodRepository
import at.mtr.logmygut.data.IngredientDraft
import at.mtr.logmygut.data.TestDatabase
import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.data.db.toRow
import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.TagRow
import at.mtr.logmygut.domain.TagSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Seed sync of attached and detached built-in ingredients, and "Reset to default" (SPEC 4). */
@RunWith(RobolectricTestRunner::class)
class SeedSyncTest {
    private lateinit var db: LogMyGutDatabase
    private lateinit var foods: FoodRepository
    private lateinit var seed: SeedFile

    @Before fun setUp() {
        db = TestDatabase.create()
        foods = FoodRepository(db, TestDatabase.tagSuggester, resetFood = { SeedImporter(db).reset(seed, it) })
    }

    @After fun tearDown() = db.close()

    private fun ingredient(name: String, parent: String? = null, tags: List<Tag> = emptyList(), remove: List<Tag> = emptyList()) =
        """{"name": "$name", "unit": "size", "tags": [${tags.joinToString { "\"${it.key}\"" }}]""" +
            (parent?.let { """, "parent": "$it"""" } ?: "") +
            (if (remove.isEmpty()) "" else """, "removeTags": [${remove.joinToString { "\"${it.key}\"" }}]""") + "}"

    /** Makes a seed with these ingredients the one a reset uses, without importing it. */
    private fun useSeed(vararg ingredients: String) {
        seed = SeedFile.parse("""{"version": 1, "tags": [], "dishes": [], "ingredients": [${ingredients.joinToString()}]}""")
    }

    /** Imports a seed with these ingredients; it is also the seed a reset uses afterwards. */
    private suspend fun import(vararg ingredients: String): SeedImporter.Result {
        useSeed(*ingredients)
        return SeedImporter(db).import(seed)
    }

    private suspend fun food(key: String) = db.foodDao().getBySeedKey(key)!!
    private suspend fun named(name: String) = foods.search(name).first { it.name == name }
    private suspend fun tags(id: Long) = foods.getIngredientTags(id).map { it.tag }.toSet()
    private suspend fun rows(id: Long) = db.foodDao().getTags(id).map { it.toRow() }.toSet()

    private suspend fun save(food: FoodEntity, tags: Set<Tag>, parentId: Long? = food.parentId) = foods.saveIngredient(
        IngredientDraft(
            id = food.id, name = food.name, aliases = food.aliases, unit = food.unit, unitLabel = null,
            portionGrams = food.portionGrams, note = food.note, parentId = parentId, tags = tags.map { IngredientTag(it) },
        ),
    )

    private suspend fun create(name: String, parentId: Long? = null, tags: Set<Tag> = emptySet()) = foods.saveIngredient(
        IngredientDraft(
            id = 0, name = name, aliases = emptyList(), unit = FoodUnit.SIZE, unitLabel = null,
            portionGrams = null, note = null, parentId = parentId, tags = tags.map { IngredientTag(it) },
        ),
    )

    @Test fun newSeedTagReachesAnEditedAttachedFood() = runTest {
        import(ingredient("Apfel", tags = listOf(Tag.FRUCTOSE)))
        save(food("Apfel"), setOf(Tag.FRUCTOSE, Tag.SPICY))

        import(ingredient("Apfel", tags = listOf(Tag.FRUCTOSE, Tag.POLYOLS)))

        assertEquals(setOf(Tag.FRUCTOSE, Tag.POLYOLS, Tag.SPICY), tags(food("Apfel").id))
        assertFalse(food("Apfel").tagsDetached)
    }

    @Test fun userRemovalBlocksANewSeedTag() = runTest {
        import(ingredient("Obst", tags = listOf(Tag.GOS)), ingredient("Apfel", parent = "Obst"))
        save(food("Apfel"), emptySet())    // removes the inherited GOS

        import(ingredient("Obst", tags = listOf(Tag.GOS)), ingredient("Apfel", parent = "Obst", tags = listOf(Tag.GOS)))

        assertEquals(emptySet<Tag>(), tags(food("Apfel").id))
        assertTrue(TagRow(Tag.GOS, null, removed = false, TagSource.SEED) in rows(food("Apfel").id))
    }

    @Test fun tagDroppedByTheSeedDisappearsButUserRowsStay() = runTest {
        import(ingredient("Apfel", tags = listOf(Tag.FRUCTOSE, Tag.POLYOLS)))
        save(food("Apfel"), setOf(Tag.FRUCTOSE, Tag.POLYOLS, Tag.SPICY))

        import(ingredient("Apfel", tags = listOf(Tag.FRUCTOSE)))

        assertEquals(setOf(Tag.FRUCTOSE, Tag.SPICY), tags(food("Apfel").id))
    }

    @Test fun detachedFoodKeepsItsTagsAndParent() = runTest {
        import(ingredient("Käse", tags = listOf(Tag.FAT)), ingredient("Wein", tags = listOf(Tag.HISTAMINE)), ingredient("Brie", parent = "Käse", tags = listOf(Tag.GOS)))
        save(food("Brie"), setOf(Tag.GOS, Tag.HISTAMINE), parentId = food("Wein").id)
        val before = rows(food("Brie").id)

        import(ingredient("Käse", tags = listOf(Tag.FAT)), ingredient("Wein", tags = listOf(Tag.HISTAMINE)), ingredient("Brie", parent = "Käse", tags = listOf(Tag.LACTOSE)))

        val brie = food("Brie")
        assertTrue(brie.tagsDetached)
        assertEquals(food("Wein").id, brie.parentId)
        assertEquals(before, rows(brie.id))
        assertEquals(setOf(Tag.GOS, Tag.HISTAMINE), tags(brie.id))
    }

    @Test fun seedMovesAnEditedAttachedFoodAndItsUserRowsStillApply() = runTest {
        import(ingredient("Käse", tags = listOf(Tag.FAT)), ingredient("Wein", tags = listOf(Tag.HISTAMINE)), ingredient("Brie", parent = "Käse", tags = listOf(Tag.GOS)))
        save(food("Brie"), setOf(Tag.FAT, Tag.GOS, Tag.SPICY))

        import(ingredient("Käse", tags = listOf(Tag.FAT)), ingredient("Wein", tags = listOf(Tag.HISTAMINE)), ingredient("Brie", parent = "Wein", tags = listOf(Tag.GOS)))

        val brie = food("Brie")
        assertEquals(food("Wein").id, brie.parentId)
        assertTrue(brie.userModified)
        assertFalse(brie.tagsDetached)
        assertEquals(setOf(Tag.HISTAMINE, Tag.GOS, Tag.SPICY), tags(brie.id))
    }

    @Test fun seedMovesAnUneditedFood() = runTest {
        import(ingredient("Käse", tags = listOf(Tag.FAT)), ingredient("Wein", tags = listOf(Tag.HISTAMINE)), ingredient("Brie", parent = "Käse"))

        val result = import(ingredient("Käse", tags = listOf(Tag.FAT)), ingredient("Wein", tags = listOf(Tag.HISTAMINE)), ingredient("Brie", parent = "Wein"))

        assertEquals(SeedImporter.Result(inserted = 0, updated = 1), result)
        assertEquals(food("Wein").id, food("Brie").parentId)
        assertEquals(setOf(Tag.HISTAMINE), tags(food("Brie").id))
    }

    @Test fun impossibleSeedMoveDetachesWithUnchangedTags() = runTest {
        import(ingredient("A", tags = listOf(Tag.HISTAMINE)), ingredient("B", tags = listOf(Tag.FAT)))
        save(food("B"), setOf(Tag.FAT, Tag.GOS))                     // attached, with a user row
        save(food("A"), setOf(Tag.HISTAMINE), parentId = food("B").id) // A is detached below B

        // B under A would close a loop: B stays where it is, detached, with the tags it had.
        import(ingredient("A", tags = listOf(Tag.HISTAMINE)), ingredient("B", parent = "A", tags = listOf(Tag.SPICY)))

        val b = food("B")
        assertTrue(b.tagsDetached)
        assertEquals(null, b.parentId)
        assertEquals(setOf(Tag.FAT, Tag.GOS), tags(b.id))
        assertTrue(rows(b.id).all { it.source == TagSource.USER })
        assertEquals(b.id, food("A").parentId)
    }

    @Test fun seedSwapDetachesNothing() = runTest {
        import(ingredient("A", tags = listOf(Tag.HISTAMINE)), ingredient("B", parent = "A", tags = listOf(Tag.FAT)))

        import(ingredient("A", parent = "B", tags = listOf(Tag.HISTAMINE)), ingredient("B", tags = listOf(Tag.FAT)))

        val a = food("A")
        val b = food("B")
        assertFalse(a.tagsDetached || b.tagsDetached)
        assertEquals(b.id, a.parentId)
        assertEquals(null, b.parentId)
        assertEquals(setOf(Tag.HISTAMINE, Tag.FAT), tags(a.id))
        assertEquals(setOf(Tag.FAT), tags(b.id))
    }

    @Test fun seedMoveTooDeepWithTheFoodsDescendantsDetaches() = runTest {
        import(ingredient("X"), ingredient("F", tags = listOf(Tag.GOS)), ingredient("G", parent = "F"))
        val h = create("H", parentId = food("G").id)                 // user-created, level 3

        // F under X would put H on level 4.
        import(ingredient("X"), ingredient("F", parent = "X", tags = listOf(Tag.GOS)), ingredient("G", parent = "F"))

        val f = food("F")
        assertTrue(f.tagsDetached)
        assertEquals(null, f.parentId)
        assertEquals(setOf(Tag.GOS), tags(f.id))
        assertFalse(food("G").tagsDetached)
        assertEquals(f.id, food("G").parentId)
        assertEquals(food("G").id, foods.getFood(h)!!.parentId)
    }

    @Test fun newSeedFoodWithAnImpossiblePlacementDetachesWithTheSeedsTags() = runTest {
        import(ingredient("P", tags = listOf(Tag.HISTAMINE)))
        val u1 = create("U1")
        val u2 = create("U2", parentId = u1)
        save(food("P"), setOf(Tag.HISTAMINE), parentId = u2)         // P is detached on level 3

        val result = import(ingredient("P", tags = listOf(Tag.HISTAMINE)), ingredient("N", parent = "P", tags = listOf(Tag.GOS)))

        assertEquals(1, result.inserted)
        val n = food("N")
        assertTrue(n.tagsDetached)
        assertEquals(null, n.parentId)
        assertEquals(setOf(Tag.GOS, Tag.HISTAMINE), tags(n.id))
        assertTrue(rows(n.id).all { it.source == TagSource.USER })
    }

    @Test fun foodDroppedFromTheSeedDetaches() = runTest {
        import(ingredient("Wein", tags = listOf(Tag.HISTAMINE)), ingredient("Rotwein", parent = "Wein", tags = listOf(Tag.GOS)))
        save(food("Rotwein"), setOf(Tag.HISTAMINE, Tag.GOS, Tag.SPICY))

        import(ingredient("Wein", tags = listOf(Tag.HISTAMINE)))

        val rotwein = food("Rotwein")
        assertTrue(rotwein.tagsDetached)
        assertEquals(food("Wein").id, rotwein.parentId)
        assertEquals(setOf(Tag.HISTAMINE, Tag.GOS, Tag.SPICY), tags(rotwein.id))
        assertTrue(rows(rotwein.id).all { it.source == TagSource.USER })
    }

    @Test fun resetOfAFoodNoLongerInTheSeedChangesNothing() = runTest {
        import(ingredient("Wein", tags = listOf(Tag.HISTAMINE)), ingredient("Rotwein", parent = "Wein", tags = listOf(Tag.GOS)))
        import(ingredient("Wein", tags = listOf(Tag.HISTAMINE)))
        val before = food("Rotwein")
        val beforeRows = rows(before.id)

        assertEquals(SeedImporter.ResetResult.NOT_IN_SEED, foods.resetToSeed(before.id))

        assertEquals(before, food("Rotwein"))
        assertEquals(beforeRows, rows(before.id))
    }

    @Test fun resetToAnImpossibleSeedParentChangesNothing() = runTest {
        import(ingredient("A", tags = listOf(Tag.HISTAMINE)), ingredient("B", parent = "A", tags = listOf(Tag.FAT)))
        save(food("B"), setOf(Tag.FAT, Tag.SPICY), parentId = null)   // B detached, top level
        save(food("A"), setOf(Tag.HISTAMINE), parentId = food("B").id) // A detached, below B
        val before = food("B")
        val beforeRows = rows(before.id)

        // B's seed parent A is below B now.
        assertEquals(SeedImporter.ResetResult.PARENT_IMPOSSIBLE, foods.resetToSeed(before.id))

        assertEquals(before, food("B"))
        assertEquals(beforeRows, rows(before.id))
        assertEquals(setOf(Tag.FAT, Tag.SPICY), tags(before.id))
    }

    @Test fun resetOfAnEditedAttachedFoodRestoresTheSeed() = runTest {
        import(ingredient("Apfel", tags = listOf(Tag.FRUCTOSE)))
        save(named("Apfel"), setOf(Tag.SPICY))

        assertEquals(SeedImporter.ResetResult.RESET, foods.resetToSeed(food("Apfel").id))

        assertFalse(food("Apfel").userModified)
        assertEquals(setOf(TagRow(Tag.FRUCTOSE, null, removed = false, TagSource.SEED)), rows(food("Apfel").id))
    }

    @Test fun newFoodsDetachingBelowANewParentKeepTheSeedChainsTags() = runTest {
        import(ingredient("P", tags = listOf(Tag.HISTAMINE)))
        val u = create("U", tags = setOf(Tag.SPICY))
        save(food("P"), setOf(Tag.HISTAMINE, Tag.SPICY), parentId = u)   // P is detached on level 2

        // M would be on level 3 and N on level 4: both new, both detached, their tags resolved along the seed chain.
        import(
            ingredient("P", tags = listOf(Tag.HISTAMINE)),
            ingredient("M", parent = "P", tags = listOf(Tag.GOS)),
            ingredient("N", parent = "M", tags = listOf(Tag.FAT), remove = listOf(Tag.HISTAMINE)),
        )

        val m = food("M")
        val n = food("N")
        assertTrue(m.tagsDetached && n.tagsDetached)
        assertEquals(null, m.parentId)
        assertEquals(null, n.parentId)
        assertEquals(setOf(Tag.GOS, Tag.HISTAMINE, Tag.SPICY), tags(m.id))
        assertEquals(setOf(Tag.FAT, Tag.GOS, Tag.SPICY), tags(n.id))
        assertTrue((rows(m.id) + rows(n.id)).all { it.source == TagSource.USER })
    }

    @Test fun existingProblemWithoutCandidatesDetachesNothingAndEnds() = runTest {
        import(ingredient("A", tags = listOf(Tag.HISTAMINE)), ingredient("B", tags = listOf(Tag.FAT)))
        val u1 = create("U1")
        val u2 = create("U2")
        db.foodDao().setParent(u1, u2)
        db.foodDao().setParent(u2, u1)                               // a cycle no seed move takes part in

        import(ingredient("A", parent = "B", tags = listOf(Tag.HISTAMINE)), ingredient("B", tags = listOf(Tag.FAT)))

        assertTrue(db.foodDao().getAllFoods().none { it.tagsDetached })
        assertEquals(food("B").id, food("A").parentId)
        assertEquals(u2, foods.getFood(u1)!!.parentId)
        assertEquals(u1, foods.getFood(u2)!!.parentId)
    }

    @Test fun resetTheImportWouldDetachAgainChangesNothing() = runTest {
        import(ingredient("A"), ingredient("B", parent = "A", tags = listOf(Tag.FAT)), ingredient("C"))
        save(food("B"), setOf(Tag.FAT, Tag.SPICY), parentId = null)    // B detached, top level
        create("U", parentId = food("B").id)
        val before = food("B")
        val beforeRows = rows(before.id)

        // B under A alone would be fine, but the import also moves A under C, which puts U on level 4.
        useSeed(ingredient("A", parent = "C"), ingredient("B", parent = "A", tags = listOf(Tag.FAT)), ingredient("C"))
        assertEquals(SeedImporter.ResetResult.PARENT_IMPOSSIBLE, foods.resetToSeed(before.id))

        assertEquals(before, food("B"))
        assertEquals(beforeRows, rows(before.id))
        assertEquals(setOf(Tag.FAT, Tag.SPICY), tags(before.id))
    }

    @Test fun resetWithAnUnrelatedExistingProblemSucceeds() = runTest {
        import(ingredient("A", tags = listOf(Tag.HISTAMINE)), ingredient("B", parent = "A", tags = listOf(Tag.FAT)))
        save(food("B"), setOf(Tag.FAT, Tag.SPICY), parentId = null)    // B detached, top level
        val u1 = create("U1")
        val u2 = create("U2")
        db.foodDao().setParent(u1, u2)
        db.foodDao().setParent(u2, u1)

        assertEquals(SeedImporter.ResetResult.RESET, foods.resetToSeed(food("B").id))

        val b = food("B")
        assertFalse(b.tagsDetached)
        assertEquals(food("A").id, b.parentId)
        assertEquals(setOf(Tag.HISTAMINE, Tag.FAT), tags(b.id))
    }
}
