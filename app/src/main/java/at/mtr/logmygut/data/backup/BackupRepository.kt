package at.mtr.logmygut.data.backup

import android.database.SQLException
import at.mtr.logmygut.data.SettingsRepository
import at.mtr.logmygut.data.SettingsSnapshot
import at.mtr.logmygut.data.db.BowelMovementEntity
import at.mtr.logmygut.data.db.DailyCheckInEntity
import at.mtr.logmygut.data.db.DatabaseContent
import at.mtr.logmygut.data.db.DishIngredientEntity
import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.data.db.FoodTagEntity
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.data.db.MealEntity
import at.mtr.logmygut.data.db.MealItemEntity
import at.mtr.logmygut.data.db.MealItemIngredientEntity
import at.mtr.logmygut.data.db.MedicationIntakeEntity
import at.mtr.logmygut.data.db.SymptomEntryEntity
import at.mtr.logmygut.data.seed.searchTextFor
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.isDrinkUnit
import java.time.Instant
import java.time.LocalDate

/** Export and import of all data (SPEC 7). */
class BackupRepository(
    db: LogMyGutDatabase,
    private val settings: SettingsRepository,
    private val appVersion: String? = null,
    /** Runs after a successful restore, e.g. to rename built-in foods to the current language. */
    private val afterRestore: suspend () -> Unit = {},
) {
    private val dao = db.backupDao()

    /** The whole database and the settings as backup JSON. */
    suspend fun export(now: Instant = Instant.now()): String =
        BackupJson.encodeToString(BackupFile.serializer(), dao.getAll().toBackup(now, appVersion, settings.snapshot()))

    /** Parses and validates [text]; throws [BackupException]. Nothing is written. */
    fun read(text: String): BackupFile = parseBackup(text)

    /**
     * Replaces all data with [backup] (from [read]). The database is replaced in one transaction, so a failure
     * leaves it as it was; the settings are written afterwards in one edit.
     */
    suspend fun restore(backup: BackupFile) {
        try {
            dao.replaceAll(backup.toContent())
        } catch (e: SQLException) {
            throw BackupException(BackupException.Reason.INVALID, e.message ?: "database rejected the data")
        }
        settings.restore(backup.settings)
        afterRestore()
    }
}

private fun DatabaseContent.toBackup(now: Instant, appVersion: String?, settings: SettingsSnapshot) = BackupFile(
    exportedAt = now.toString(),
    appVersion = appVersion,
    settings = settings,
    foods = foods.map {
        FoodJson(
            id = it.id, kind = it.kind, name = it.name, aliases = it.aliases, unit = it.unit, unitLabel = it.unitLabel,
            note = it.note, builtIn = it.builtIn, userModified = it.userModified, hidden = it.hidden, seedKey = it.seedKey,
            lastUsedAt = it.lastUsedAt?.toString(), parentId = it.parentId, portionGrams = it.portionGrams, drink = it.drink,
        )
    },
    foodTags = foodTags.map { FoodTagJson(it.foodId, it.tag.key, it.minSize, it.minCount, it.removed) },
    dishIngredients = dishIngredients.map { DishIngredientJson(it.dishId, it.ingredientId) },
    meals = meals.map { MealJson(it.id, it.timestamp.toString(), it.zoneId, it.eatenOut, it.note) },
    mealItems = mealItems.map { MealItemJson(it.id, it.mealId, it.foodId, it.count, it.size) },
    mealItemIngredients = mealItemIngredients.map { MealItemIngredientJson(it.mealItemId, it.ingredientId) },
    bowelMovements = bowelMovements.map {
        BowelMovementJson(it.id, it.timestamp.toString(), it.zoneId, it.bristol, it.urgent, it.painBefore, it.painAfter, it.note)
    },
    symptoms = symptoms.map { SymptomJson(it.id, it.timestamp.toString(), it.zoneId, it.type, it.severity, it.note) },
    medication = medication.map { MedicationJson(it.id, it.timestamp.toString(), it.zoneId, it.name, it.effect, it.note) },
    checkIns = checkIns.map { CheckInJson(it.date.toString(), it.stress, it.sleep) },
)

/** Assumes a validated backup. */
private fun BackupFile.toContent() = DatabaseContent(
    foods = foods.map {
        FoodEntity(
            id = it.id, kind = it.kind, name = it.name, aliases = it.aliases, unit = it.unit, unitLabel = it.unitLabel,
            note = it.note, builtIn = it.builtIn, userModified = it.userModified, hidden = it.hidden, seedKey = it.seedKey,
            searchText = searchTextFor(it.name, it.aliases), lastUsedAt = it.lastUsedAt?.let(Instant::parse),
            parentId = it.parentId, portionGrams = it.portionGrams,
            drink = if (version >= 2) it.drink else isDrinkUnit(it.unit, it.unitLabel),
        )
    },
    foodTags = foodTags.map { FoodTagEntity(it.foodId, Tag.fromKey(it.tag), it.minSize, it.minCount, it.removed) },
    dishIngredients = dishIngredients.map { DishIngredientEntity(it.dishId, it.ingredientId) },
    meals = meals.map { MealEntity(it.id, Instant.parse(it.timestamp), it.zoneId, it.eatenOut, it.note) },
    mealItems = mealItems.map { MealItemEntity(it.id, it.mealId, it.foodId, it.count, it.size) },
    mealItemIngredients = mealItemIngredients.map { MealItemIngredientEntity(it.mealItemId, it.ingredientId) },
    bowelMovements = bowelMovements.map {
        BowelMovementEntity(it.id, Instant.parse(it.timestamp), it.zoneId, it.bristol, it.urgent, it.painBefore, it.painAfter, it.note)
    },
    symptoms = symptoms.map { SymptomEntryEntity(it.id, Instant.parse(it.timestamp), it.zoneId, it.type, it.severity, it.note) },
    medication = medication.map { MedicationIntakeEntity(it.id, Instant.parse(it.timestamp), it.zoneId, it.name, it.effect, it.note) },
    checkIns = checkIns.map { DailyCheckInEntity(LocalDate.parse(it.date), it.stress, it.sleep) },
)
