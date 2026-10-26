package at.mtr.logmygut.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs migrations against the exported schemas in app/schemas, and checks the data survives. */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LogMyGutDatabase::class.java)

    @Test fun migrate1To2MapsReliefToPainChange() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL("INSERT INTO bowel_movement (id, timestamp, zoneId, bristol, urgent, reliefAfter, note) VALUES (1, 1000, 'Europe/Vienna', 6, 1, 1, 'a')")
            db.execSQL("INSERT INTO bowel_movement (id, timestamp, zoneId, bristol, urgent, reliefAfter, note) VALUES (2, 2000, 'Europe/Vienna', 4, 0, 0, NULL)")
            db.execSQL("INSERT INTO bowel_movement (id, timestamp, zoneId, bristol, urgent, reliefAfter, note) VALUES (3, 3000, 'Europe/Vienna', 3, 0, NULL, NULL)")
        }

        // Validates the resulting schema against 2.json as well.
        val db = helper.runMigrationsAndValidate(DB, 2, true, MIGRATION_1_2)

        val rows = db.query("SELECT id, bristol, urgent, painBefore, painAfter, note FROM bowel_movement ORDER BY id").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(listOf(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getString(4), c.getString(5)))
                }
            }
        }
        assertEquals(
            listOf(
                listOf(1L, 6L, 1L, 1L, "BETTER", "a"),
                listOf(2L, 4L, 0L, 1L, "SAME", null),
                listOf(3L, 3L, 0L, 0L, null, null),
            ),
            rows,
        )
    }

    @Test fun migrate2To3KeepsFoodsAndTags() {
        helper.createDatabase(DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO food (id, kind, name, aliases, unit, builtIn, userModified, hidden, seedKey, searchText) " +
                    "VALUES (1, 'INGREDIENT', 'Balsamico', '[]', 'SIZE', 1, 0, 0, 'Balsamico', 'balsamico')",
            )
            db.execSQL("INSERT INTO food_tag (foodId, tag, minSize) VALUES (1, 'fructose', 'L')")
        }

        val db = helper.runMigrationsAndValidate(DB, 3, true, MIGRATION_2_3)

        db.query("SELECT parentId FROM food WHERE id = 1").use { c -> c.moveToFirst(); assertEquals(true, c.isNull(0)) }
        db.query("SELECT tag, minSize, removed FROM food_tag").use { c ->
            c.moveToFirst()
            assertEquals(listOf("fructose", "L", 0L), listOf(c.getString(0), c.getString(1), c.getLong(2)))
        }
    }

    @Test fun migrate3To4AddsEmptyPortionWeight() {
        helper.createDatabase(DB, 3).use { db ->
            db.execSQL(
                "INSERT INTO food (id, kind, name, aliases, unit, builtIn, userModified, hidden, seedKey, searchText) " +
                    "VALUES (1, 'INGREDIENT', 'Mandeln', '[]', 'SIZE', 1, 0, 0, 'Mandeln', 'mandeln')",
            )
        }

        val db = helper.runMigrationsAndValidate(DB, 4, true, MIGRATION_3_4)

        db.query("SELECT name, portionGrams FROM food").use { c ->
            c.moveToFirst()
            assertEquals("Mandeln", c.getString(0))
            assertEquals(true, c.isNull(1))
        }
    }

    @Test fun migrate4To5MarksDrinksAndKeepsCheckIns() {
        helper.createDatabase(DB, 4).use { db ->
            fun food(id: Int, name: String, unit: String, label: String?) = db.execSQL(
                "INSERT INTO food (id, kind, name, aliases, unit, unitLabel, builtIn, userModified, hidden, searchText) " +
                    "VALUES ($id, 'INGREDIENT', '$name', '[]', '$unit', ${label?.let { "'$it'" } ?: "NULL"}, 0, 0, 0, '$name')",
            )
            food(1, "Kaffee", "COUNT", "Tasse")
            food(2, "Hausbier", "COUNT", "Glas")
            food(3, "Semmel", "COUNT", "Stück")
            food(4, "Joghurt", "SIZE", null)
            db.execSQL("INSERT INTO daily_check_in (date, stress, sleep) VALUES ('2026-10-24', 2, 4)")
        }

        val db = helper.runMigrationsAndValidate(DB, 5, true, MIGRATION_4_5)

        val drinks = db.query("SELECT name FROM food WHERE drink = 1 ORDER BY id").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertEquals(listOf("Kaffee", "Hausbier"), drinks)
        db.query("SELECT stress, sleep FROM daily_check_in WHERE date = '2026-10-24'").use { c ->
            c.moveToFirst()
            assertEquals(listOf(2L, 4L), listOf(c.getLong(0), c.getLong(1)))
        }
        db.execSQL("INSERT INTO daily_check_in (date, stress, sleep) VALUES ('2026-10-25', NULL, 3)")
    }

    @Test fun migrate5To6SplitsTagRowsBySourceAndDetachesEditedIngredients() {
        helper.createDatabase(DB, 5).use { db ->
            fun food(id: Int, kind: String, builtIn: Int, userModified: Int) = db.execSQL(
                "INSERT INTO food (id, kind, name, aliases, unit, builtIn, userModified, hidden, seedKey, searchText) " +
                    "VALUES ($id, '$kind', 'F$id', '[]', 'SIZE', $builtIn, $userModified, 0, ${if (builtIn == 1) "'F$id'" else "NULL"}, 'f$id')",
            )
            food(1, "INGREDIENT", builtIn = 1, userModified = 0)   // follows the seed
            food(2, "INGREDIENT", builtIn = 1, userModified = 1)   // edited built-in ingredient
            food(3, "INGREDIENT", builtIn = 0, userModified = 0)   // user-created
            food(4, "DISH", builtIn = 1, userModified = 1)         // edited built-in dish
            db.execSQL("INSERT INTO food_tag (foodId, tag, minSize, removed) VALUES (1, 'fructose', 'L', 0)")
            db.execSQL("INSERT INTO food_tag (foodId, tag, minSize, removed) VALUES (2, 'histamine', NULL, 1)")
            db.execSQL("INSERT INTO food_tag (foodId, tag, minSize, removed) VALUES (3, 'gos', NULL, 0)")
        }

        val db = helper.runMigrationsAndValidate(DB, 6, true, MIGRATION_5_6)

        val tags = db.query("SELECT foodId, tag, minSize, removed, source FROM food_tag ORDER BY foodId").use { c ->
            buildList { while (c.moveToNext()) add(listOf(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4))) }
        }
        assertEquals(
            listOf(
                listOf(1L, "fructose", "L", 0L, "SEED"),
                listOf(2L, "histamine", null, 1L, "USER"),
                listOf(3L, "gos", null, 0L, "USER"),
            ),
            tags,
        )
        val detached = db.query("SELECT id FROM food WHERE tagsDetached = 1").use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }
        assertEquals(listOf(2L), detached)
    }

    private companion object {
        const val DB = "migration-test"
    }
}
