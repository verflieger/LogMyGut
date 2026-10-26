package at.mtr.logmygut.data.backup

import at.mtr.logmygut.data.SettingsRepository.Companion.isValid
import at.mtr.logmygut.data.SettingsSnapshot
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.MaxFoodDepth
import at.mtr.logmygut.domain.MedEffect
import at.mtr.logmygut.domain.PainChange
import at.mtr.logmygut.domain.PortionSize
import at.mtr.logmygut.domain.SymptomType
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.TagSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The backup file (SPEC 7): every table plus the settings, as plain JSON. Its own classes rather than the Room
// entities, so the file format only changes on purpose. Times are ISO-8601 instants, tags use their stable keys,
// other enums their names. Derived columns (a food's search text) are rebuilt on import.

const val BackupFormat = "logmygut-backup"

/**
 * 3: food tag rows carry their `source` (seed or user), foods carry `tagsDetached`. Only version 3 is read: older
 * files are rejected as [BackupException.Reason.TOO_OLD] (a one-time exception, SPEC 7).
 * 2: foods have `drink`; check-in values are optional.
 */
const val BackupVersion = 3

/** The oldest version this app reads. */
const val OldestBackupVersion = 3

@Serializable
data class BackupFile(
    val format: String = BackupFormat,
    val version: Int = BackupVersion,
    val exportedAt: String,
    val appVersion: String? = null,
    val settings: SettingsSnapshot,
    val foods: List<FoodJson>,
    val foodTags: List<FoodTagJson>,
    val dishIngredients: List<DishIngredientJson>,
    val meals: List<MealJson>,
    val mealItems: List<MealItemJson>,
    val mealItemIngredients: List<MealItemIngredientJson>,
    val bowelMovements: List<BowelMovementJson>,
    val symptoms: List<SymptomJson>,
    val medication: List<MedicationJson>,
    val checkIns: List<CheckInJson>,
)

@Serializable
data class FoodJson(
    val id: Long,
    val kind: FoodKind,
    val name: String,
    val aliases: List<String> = emptyList(),
    val unit: FoodUnit,
    val unitLabel: String? = null,
    val note: String? = null,
    val builtIn: Boolean = false,
    val userModified: Boolean = false,
    val hidden: Boolean = false,
    val seedKey: String? = null,
    val lastUsedAt: String? = null,
    val parentId: Long? = null,
    val portionGrams: Double? = null,
    val drink: Boolean = false,
    /** Built-in ingredients only: its tags no longer follow the seed (SPEC 4). */
    val tagsDetached: Boolean = false,
)

@Serializable
data class FoodTagJson(
    val foodId: Long,
    val tag: String,
    val minSize: PortionSize? = null,
    val minCount: Int? = null,
    val removed: Boolean = false,
    val source: TagSource,
)

@Serializable
data class DishIngredientJson(val dishId: Long, val ingredientId: Long)

@Serializable
data class MealJson(val id: Long, val timestamp: String, val zoneId: String, val eatenOut: Boolean = false, val note: String? = null)

@Serializable
data class MealItemJson(val id: Long, val mealId: Long, val foodId: Long, val count: Int? = null, val size: PortionSize? = null)

@Serializable
data class MealItemIngredientJson(val mealItemId: Long, val ingredientId: Long)

@Serializable
data class BowelMovementJson(
    val id: Long,
    val timestamp: String,
    val zoneId: String,
    val bristol: Int,
    val urgent: Boolean = false,
    val painBefore: Boolean = false,
    val painAfter: PainChange? = null,
    val note: String? = null,
)

@Serializable
data class SymptomJson(val id: Long, val timestamp: String, val zoneId: String, val type: SymptomType, val severity: Int, val note: String? = null)

@Serializable
data class MedicationJson(val id: Long, val timestamp: String, val zoneId: String, val name: String, val effect: MedEffect? = null, val note: String? = null)

@Serializable
data class CheckInJson(val date: String, val stress: Int? = null, val sleep: Int? = null)

/** Why a file can't be imported. [message] is a technical detail in English, for the error dialog's small print. */
class BackupException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { NOT_A_BACKUP, TOO_NEW, TOO_OLD, INVALID }
}

internal val BackupJson = Json {
    explicitNulls = false
    encodeDefaults = true
    prettyPrint = false
}

/**
 * Reads and checks a backup. The format and version are read first, so a newer or older app's file is reported as
 * such even if its structure differs. Everything else is validated before anything is written.
 */
fun parseBackup(text: String): BackupFile {
    val header = try {
        BackupJson.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        throw BackupException(BackupException.Reason.NOT_A_BACKUP, "Not JSON: ${e.message}")
    }
    val format = runCatching { header["format"]?.jsonPrimitive?.content }.getOrNull()
    if (format != BackupFormat) throw BackupException(BackupException.Reason.NOT_A_BACKUP, "format is '$format'")
    val version = runCatching { header["version"]?.jsonPrimitive?.int }.getOrNull()
        ?: throw BackupException(BackupException.Reason.INVALID, "version missing")
    if (version > BackupVersion) throw BackupException(BackupException.Reason.TOO_NEW, "version $version, this app reads up to $BackupVersion")
    if (version < OldestBackupVersion) throw BackupException(BackupException.Reason.TOO_OLD, "version $version, this app reads from $OldestBackupVersion")

    val backup = try {
        BackupJson.decodeFromString(BackupFile.serializer(), text)
    } catch (e: SerializationException) {
        throw BackupException(BackupException.Reason.INVALID, e.message ?: "unreadable")
    } catch (e: IllegalArgumentException) {
        throw BackupException(BackupException.Reason.INVALID, e.message ?: "unreadable")
    }
    backup.validate()
    return backup
}

/** Checks values and references; throws [BackupException] with the first problem found. */
private fun BackupFile.validate() {
    fun check(ok: Boolean, problem: () -> String) {
        if (!ok) throw BackupException(BackupException.Reason.INVALID, problem())
    }
    fun checkInstant(text: String, what: String) = check(runCatching { Instant.parse(text) }.isSuccess) { "$what: bad time '$text'" }
    fun checkZone(text: String, what: String) = check(runCatching { ZoneId.of(text) }.isSuccess) { "$what: bad zone '$text'" }
    fun <T, K> unique(rows: List<T>, what: String, key: (T) -> K) =
        check(rows.map(key).toSet().size == rows.size) { "$what: duplicate entries" }

    check(settings.isValid()) { "settings: invalid values" }

    unique(foods, "foods") { it.id }
    unique(foods.mapNotNull { it.seedKey }, "food seed keys") { it }
    val foodById = foods.associateBy { it.id }
    fun isKind(id: Long, kind: FoodKind) = foodById[id]?.kind == kind
    foods.forEach { f ->
        check(f.name.isNotBlank()) { "food ${f.id}: empty name" }
        check(!f.tagsDetached || (f.builtIn && f.kind == FoodKind.INGREDIENT)) { "food ${f.id}: detached but not a built-in ingredient" }
        f.lastUsedAt?.let { checkInstant(it, "food ${f.id}") }
        f.parentId?.let { parent ->
            check(f.kind == FoodKind.INGREDIENT && isKind(parent, FoodKind.INGREDIENT)) { "food ${f.id}: bad parent $parent" }
        }
        // The hierarchy must end within MaxFoodDepth levels (this also rules out cycles).
        var depth = 1
        var current = f.parentId
        while (current != null) {
            depth++
            check(depth <= MaxFoodDepth) { "food ${f.id}: hierarchy too deep or circular" }
            current = foodById[current]?.parentId
        }
    }

    unique(foodTags, "food tags") { Triple(it.foodId, it.tag, it.source) }
    foodTags.forEach { t ->
        check(isKind(t.foodId, FoodKind.INGREDIENT)) { "food tag: unknown ingredient ${t.foodId}" }
        check(t.source != TagSource.SEED || foodById.getValue(t.foodId).let { it.builtIn && !it.tagsDetached }) {
            "food tag ${t.foodId}/${t.tag}: seed row on a food that doesn't follow the seed"
        }
        check(Tag.entries.any { it.key == t.tag }) { "food tag: unknown tag '${t.tag}'" }
        check(t.minSize == null || t.minCount == null) { "food tag ${t.foodId}/${t.tag}: two thresholds" }
        check(!t.removed || (t.minSize == null && t.minCount == null)) { "food tag ${t.foodId}/${t.tag}: removal with a threshold" }
        check(t.minCount == null || t.minCount >= 1) { "food tag ${t.foodId}/${t.tag}: bad count" }
    }

    unique(dishIngredients, "dish ingredients") { it }
    dishIngredients.forEach { d ->
        check(isKind(d.dishId, FoodKind.DISH) && isKind(d.ingredientId, FoodKind.INGREDIENT)) { "dish ingredient ${d.dishId}/${d.ingredientId}: bad reference" }
    }

    unique(meals, "meals") { it.id }
    meals.forEach { m ->
        checkInstant(m.timestamp, "meal ${m.id}")
        checkZone(m.zoneId, "meal ${m.id}")
    }
    val mealIds = meals.mapTo(mutableSetOf()) { it.id }

    unique(mealItems, "meal items") { it.id }
    mealItems.forEach { i ->
        check(i.mealId in mealIds) { "meal item ${i.id}: unknown meal ${i.mealId}" }
        check(i.foodId in foodById) { "meal item ${i.id}: unknown food ${i.foodId}" }
        check((i.count == null) != (i.size == null)) { "meal item ${i.id}: needs exactly one of count and size" }
        check(i.count == null || i.count >= 1) { "meal item ${i.id}: bad count" }
    }
    val itemById = mealItems.associateBy { it.id }

    unique(mealItemIngredients, "meal item ingredients") { it }
    mealItemIngredients.forEach { r ->
        val item = itemById[r.mealItemId]
        check(item != null && isKind(item.foodId, FoodKind.DISH)) { "meal item ingredient: item ${r.mealItemId} is not a dish item" }
        check(isKind(r.ingredientId, FoodKind.INGREDIENT)) { "meal item ingredient: unknown ingredient ${r.ingredientId}" }
    }

    unique(bowelMovements, "bowel movements") { it.id }
    bowelMovements.forEach { b ->
        checkInstant(b.timestamp, "bowel movement ${b.id}")
        checkZone(b.zoneId, "bowel movement ${b.id}")
        check(b.bristol in 1..7) { "bowel movement ${b.id}: Bristol ${b.bristol}" }
        check(b.painBefore == (b.painAfter != null)) { "bowel movement ${b.id}: pain after must be set exactly when pain before" }
    }

    unique(symptoms, "symptoms") { it.id }
    symptoms.forEach { s ->
        checkInstant(s.timestamp, "symptom ${s.id}")
        checkZone(s.zoneId, "symptom ${s.id}")
        check(s.severity in 0..10) { "symptom ${s.id}: severity ${s.severity}" }
    }

    unique(medication, "medication") { it.id }
    medication.forEach { m ->
        checkInstant(m.timestamp, "medication ${m.id}")
        checkZone(m.zoneId, "medication ${m.id}")
        check(m.name.isNotBlank()) { "medication ${m.id}: empty name" }
    }

    unique(checkIns, "check-ins") { it.date }
    checkIns.forEach { c ->
        check(runCatching { LocalDate.parse(c.date) }.isSuccess) { "check-in: bad date '${c.date}'" }
        check(c.stress != null || c.sleep != null) { "check-in ${c.date}: empty" }
        check(listOfNotNull(c.stress, c.sleep).all { it in 1..5 }) { "check-in ${c.date}: values must be 1..5" }
    }
}
