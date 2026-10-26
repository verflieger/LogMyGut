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

/**
 * v5 → v6: tag rows belong to the seed or the user (SPEC 4). Unchanged built-in foods follow the seed; edited built-in
 * ingredients keep their rows as user rows and stop following the seed (tagsDetached), as before; user-created foods
 * only have user rows (the SQL form of `FoodEntity.tagsFollowSeed`). The primary key changes, so the tag table is
 * rebuilt.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `food` ADD COLUMN `tagsDetached` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `food` SET `tagsDetached` = 1 WHERE `builtIn` = 1 AND `userModified` = 1 AND `kind` = 'INGREDIENT'")

        db.execSQL(
            "CREATE TABLE `food_tag_new` (`foodId` INTEGER NOT NULL, `tag` TEXT NOT NULL, `minSize` TEXT, `minCount` INTEGER, " +
                "`removed` INTEGER NOT NULL DEFAULT 0, `source` TEXT NOT NULL, PRIMARY KEY(`foodId`, `tag`, `source`), " +
                "FOREIGN KEY(`foodId`) REFERENCES `food`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            """
            INSERT INTO food_tag_new (foodId, tag, minSize, minCount, removed, source)
            SELECT t.foodId, t.tag, t.minSize, t.minCount, t.removed,
                   CASE WHEN f.builtIn = 1 AND f.userModified = 0 THEN 'SEED' ELSE 'USER' END
            FROM food_tag t JOIN food f ON f.id = t.foodId
            """,
        )
        db.execSQL("DROP TABLE `food_tag`")
        db.execSQL("ALTER TABLE `food_tag_new` RENAME TO `food_tag`")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
