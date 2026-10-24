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

    private companion object {
        const val DB = "migration-test"
    }
}
