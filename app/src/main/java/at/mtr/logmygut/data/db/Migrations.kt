package at.mtr.logmygut.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import at.mtr.logmygut.domain.DrinkUnitLabels

/**
 * v1 → v2: bowel movements record pain before + how it changed afterwards instead of a single
 * "relief" flag. Old values map as: relief yes → BETTER, relief no → SAME, not applicable → no pain.
 * SQLite can't change columns in place, so the table is rebuilt (Room's recommended pattern).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE `bowel_movement_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                "`zoneId` TEXT NOT NULL, `bristol` INTEGER NOT NULL, `urgent` INTEGER NOT NULL, `painBefore` INTEGER NOT NULL, " +
                "`painAfter` TEXT, `note` TEXT)",
        )
        db.execSQL(
            """
            INSERT INTO bowel_movement_new (id, timestamp, zoneId, bristol, urgent, painBefore, painAfter, note)
            SELECT id, timestamp, zoneId, bristol, urgent,
                   CASE WHEN reliefAfter IS NULL THEN 0 ELSE 1 END,
                   CASE reliefAfter WHEN 1 THEN 'BETTER' WHEN 0 THEN 'SAME' ELSE NULL END,
                   note
            FROM bowel_movement
            """,
        )
        db.execSQL("DROP TABLE bowel_movement")
        db.execSQL("ALTER TABLE bowel_movement_new RENAME TO bowel_movement")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bowel_movement_timestamp` ON `bowel_movement` (`timestamp`)")
    }
}

/** v2 → v3: food hierarchy (SPEC 4.4). Existing foods have no parent; existing tags are not removals. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `food` ADD COLUMN `parentId` INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_food_parentId` ON `food` (`parentId`)")
        db.execSQL("ALTER TABLE `food_tag` ADD COLUMN `removed` INTEGER NOT NULL DEFAULT 0")
    }
}

/** v3 → v4: portion weight per food; the seed import fills it for built-in foods. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `food` ADD COLUMN `portionGrams` REAL")
    }
}

/**
 * v4 → v5: drinks and a check-in in two halves.
 * - Foods counted in glasses, cups or cans are drinks; this also covers edited and user-created foods,
 *   which the seed import doesn't touch.
 * - Stress and sleep become optional; SQLite can't drop NOT NULL in place, so the table is rebuilt.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `food` ADD COLUMN `drink` INTEGER NOT NULL DEFAULT 0")
        val labels = DrinkUnitLabels.joinToString(", ") { "'$it'" }
        db.execSQL("UPDATE `food` SET `drink` = 1 WHERE `unit` = 'COUNT' AND `unitLabel` IN ($labels)")

        db.execSQL("CREATE TABLE `daily_check_in_new` (`date` TEXT NOT NULL, `stress` INTEGER, `sleep` INTEGER, PRIMARY KEY(`date`))")
        db.execSQL("INSERT INTO `daily_check_in_new` (date, stress, sleep) SELECT date, stress, sleep FROM `daily_check_in`")
        db.execSQL("DROP TABLE `daily_check_in`")
        db.execSQL("ALTER TABLE `daily_check_in_new` RENAME TO `daily_check_in`")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
