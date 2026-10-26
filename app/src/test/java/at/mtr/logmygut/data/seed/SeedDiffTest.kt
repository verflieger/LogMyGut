package at.mtr.logmygut.data.seed

import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.PortionSize
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.TagRow
import at.mtr.logmygut.domain.TagSource
import at.mtr.logmygut.domain.Threshold
import at.mtr.logmygut.data.seed.SeedDifference as D
import org.junit.Assert.assertEquals
import org.junit.Test

/** "Änderungen gegenüber Standard" (SPEC 2): how a stored built-in food differs from its seed entry. */
class SeedDiffTest {
    private val seed = SeedFile.parse(
        """
        {"version": 1, "tags": [],
         "ingredients": [
           {"name": "Milch", "aliases": ["Kuhmilch"], "unit": "size", "tags": ["lactose"], "grams": 200, "drink": true,
            "note": "Vollmilch", "en": {"name": "Milk", "aliases": ["Cow's milk"], "note": "Whole milk"}},
           {"name": "Joghurt", "parent": "Milch", "unit": "size", "tags": ["histamine"], "grams": 150,
            "en": {"name": "Yoghurt"}},
           {"name": "Apfel", "unit": "size", "tags": ["fructose"], "doseTags": {"polyols": "L"}, "grams": 150,
            "en": {"name": "Apple"}},
           {"name": "Zwiebel", "unit": "count", "unitLabel": "Stück", "tags": ["fructans"], "grams": 80,
            "en": {"name": "Onion"}}
         ],
         "dishes": [
           {"name": "Zwiebelsuppe", "unit": "size", "ingredients": ["Zwiebel", "Milch"], "en": {"name": "Onion soup"}}
         ]}
        """,
    )

    private fun ingredient(key: String) = seed.ingredients.first { it.key == key }

    /** The food as the importer stores it in [language], unedited. */
    private fun stored(key: String, language: String = "de"): FoodEntity {
        val food: SeedFood = seed.ingredients.firstOrNull { it.key == key } ?: seed.dishes.first { it.key == key }
        val text = food.searchableText(language)
        return when (food) {
            is SeedIngredient -> FoodEntity(
                kind = FoodKind.INGREDIENT, name = text.name, aliases = text.aliases, unit = food.unit, unitLabel = food.unitLabel,
                note = text.note, builtIn = true, seedKey = key, portionGrams = food.grams, drink = food.drink,
            )
            is SeedDish -> FoodEntity(
                kind = FoodKind.DISH, name = text.name, aliases = text.aliases, unit = food.unit, unitLabel = food.unitLabel,
                builtIn = true, seedKey = key, portionGrams = food.grams, drink = food.drink,
            )
        }
    }

    private fun seedRows(key: String) = ingredient(key).let { i ->
        i.tags.map { TagRow(it.tag, it.threshold, removed = false, TagSource.SEED) } +
            i.removedTags.map { TagRow(it, null, removed = true, TagSource.SEED) }
    }

    private fun user(tag: Tag, threshold: Threshold? = null, removed: Boolean = false) = TagRow(tag, threshold, removed, TagSource.USER)

    private val milkTags = listOf(IngredientTag(Tag.LACTOSE))
    private val milkRef = FoodRef("Milch", "Milch")

    private fun diff(food: StoredBuiltIn, language: String = "de") = seedDifferences(food, seed, language)

    @Test fun anUneditedFoodHasNoDifferences() {
        assertEquals(emptyList<D>(), diff(StoredBuiltIn(stored("Milch").copy(userModified = true), seedRows("Milch"))))
        assertEquals(
            emptyList<D>(),
            diff(StoredBuiltIn(stored("Joghurt", "en"), seedRows("Joghurt"), milkTags, FoodRef("Milch", "Milk")), "en"),
        )
        val soup = StoredBuiltIn(stored("Zwiebelsuppe"), recipe = listOf(FoodRef("Zwiebel", "Zwiebel"), milkRef))
        assertEquals(emptyList<D>(), diff(soup))
    }

    @Test fun fieldsAreComparedWithTheSeedInTheAppsLanguage() {
        val milk = stored("Milch", "en").copy(
            name = "My milk", note = "Lactose-free?", portionGrams = 250.0, drink = false,
            aliases = listOf("Milk", "Kuhmilch", "Milch", "Moo"),
        )
        assertEquals(
            listOf(D.Renamed("Milk"), D.Aliases, D.Note, D.PortionWeight, D.UnmarkedDrink),
            diff(StoredBuiltIn(milk, seedRows("Milch")), "en"),
        )
        // The German food against the English seed texts: only the name; its aliases and note are the seed's.
        assertEquals(listOf(D.Renamed("Milk")), diff(StoredBuiltIn(stored("Milch"), seedRows("Milch")), "en"))
        assertEquals(listOf(D.MarkedDrink), diff(StoredBuiltIn(stored("Apfel").copy(drink = true), seedRows("Apfel"))))
    }

    @Test fun anEditedFoodAfterALanguageSwitchKeepsItsSeedNote() {
        // Edited in German, then the app switched to English: the importer renames it but keeps the German note.
        val milk = stored("Milch").copy(userModified = true, name = "Milk", aliases = listOf("Kuhmilch", "Cow's milk", "Milch"))
        assertEquals(emptyList<D>(), diff(StoredBuiltIn(milk, seedRows("Milch")), "en"))
        // And the other way round; a note of the user's own still counts.
        val english = stored("Milch", "en").copy(userModified = true)
        assertEquals(listOf(D.Renamed("Milch")), diff(StoredBuiltIn(english, seedRows("Milch")), "de"))
        assertEquals(listOf(D.Note), diff(StoredBuiltIn(milk.copy(note = "Vollmilch, 3,5 %"), seedRows("Milch")), "en"))
    }

    @Test fun aliasesInAnyCaseAndOrderAreUnchanged() {
        val milk = stored("Milch")
        val reordered = milk.copy(aliases = milk.aliases.reversed().map { it.uppercase() })
        assertEquals(emptyList<D>(), diff(StoredBuiltIn(reordered, seedRows("Milch"))))
        assertEquals(listOf(D.Aliases), diff(StoredBuiltIn(milk.copy(aliases = milk.aliases.drop(1)), seedRows("Milch"))))
    }

    @Test fun unitAndUnitLabel() {
        val onion = stored("Zwiebel")
        assertEquals(listOf(D.UnitChanged), diff(StoredBuiltIn(onion.copy(unitLabel = "Scheibe"), seedRows("Zwiebel"))))
        assertEquals(listOf(D.UnitChanged), diff(StoredBuiltIn(onion.copy(unit = FoodUnit.SIZE, unitLabel = null), seedRows("Zwiebel"))))
    }

    @Test fun tagsOfAnAttachedFoodAreItsUserRowsAgainstItsSeedRows() {
        val rows = seedRows("Apfel") + user(Tag.FRUCTOSE, removed = true) + user(Tag.LACTOSE) +
            user(Tag.POLYOLS, Threshold.MinSize(PortionSize.M)) + user(Tag.GLUTEN, Threshold.MinSize(PortionSize.L))
        assertEquals(
            listOf(
                D.TagAdded(Tag.LACTOSE, null),
                D.TagRemoved(Tag.FRUCTOSE),
                D.TagThreshold(Tag.POLYOLS, Threshold.MinSize(PortionSize.M)),
                D.TagAdded(Tag.GLUTEN, Threshold.MinSize(PortionSize.L)),
            ),
            diff(StoredBuiltIn(stored("Apfel").copy(userModified = true), rows)),
        )
    }

    @Test fun anInheritedTagRemovedOrAlwaysOnAnAttachedChild() {
        val yoghurt = stored("Joghurt")
        val removed = seedRows("Joghurt") + user(Tag.LACTOSE, removed = true)
        assertEquals(listOf(D.TagRemoved(Tag.LACTOSE)), diff(StoredBuiltIn(yoghurt, removed, milkTags, milkRef)))
        val dosed = listOf(IngredientTag(Tag.LACTOSE, Threshold.MinSize(PortionSize.L)))
        val always = seedRows("Joghurt") + user(Tag.LACTOSE)
        assertEquals(listOf(D.TagThreshold(Tag.LACTOSE, null)), diff(StoredBuiltIn(yoghurt, always, dosed, milkRef)))
    }

    @Test fun aDetachedFoodIsComparedWithWhatTheSeedGives() {
        // Moved from Milch to a user's own parent without lactose: its own list now adds gluten.
        val yoghurt = stored("Joghurt").copy(userModified = true, tagsDetached = true)
        val rows = listOf(user(Tag.HISTAMINE), user(Tag.GLUTEN))
        assertEquals(
            listOf(D.Parent("Meine Basis", "Milch"), D.TagRemoved(Tag.LACTOSE), D.TagAdded(Tag.GLUTEN, null), D.Detached),
            diff(StoredBuiltIn(yoghurt, rows, parentTags = emptyList(), parent = FoodRef(null, "Meine Basis"))),
        )
        // Detached at its seed parent with the same tags: only the flag.
        val same = listOf(user(Tag.HISTAMINE))
        assertEquals(listOf(D.Detached), diff(StoredBuiltIn(yoghurt.copy(userModified = false), same, milkTags, milkRef)))
        // Without a parent where the seed has one.
        assertEquals(
            listOf(D.Parent(null, "Milch"), D.Detached),
            diff(StoredBuiltIn(yoghurt, listOf(user(Tag.HISTAMINE), user(Tag.LACTOSE)))),
        )
    }

    @Test fun recipeAddedAndRemovedIngredients() {
        val soup = stored("Zwiebelsuppe", "en").copy(userModified = true)
        val recipe = listOf(FoodRef("Zwiebel", "Zwiebel"), FoodRef("Apfel", "Apfel"), FoodRef(null, "Omas Brühe"))
        assertEquals(
            listOf(D.RecipeAdded(listOf("Apfel", "Omas Brühe")), D.RecipeRemoved(listOf("Milk"))),
            diff(StoredBuiltIn(soup, recipe = recipe), "en"),
        )
    }

    @Test fun aFoodNoLongerInTheSeed() {
        val gone = stored("Apfel").copy(seedKey = "Birne", name = "Birne", tagsDetached = true)
        assertEquals(listOf(D.NotInSeed, D.Detached), diff(StoredBuiltIn(gone, listOf(user(Tag.FRUCTOSE)))))
        val goneDish = stored("Zwiebelsuppe").copy(seedKey = "Gulasch", userModified = true)
        assertEquals(listOf(D.NotInSeed), diff(StoredBuiltIn(goneDish)))
    }
}
