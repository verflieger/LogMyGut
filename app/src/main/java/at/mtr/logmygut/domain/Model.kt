package at.mtr.logmygut.domain

import java.time.Duration

/**
 * Core enums and value types (incl. the tags' analysis properties) shared by the database, seed import and analysis.
 * Each enum has a stable [key] that is what gets persisted, so enum constants can be renamed safely.
 */

/**
 * Food tags with their analysis properties (SPEC 4.3, 6.3). Display order is enum order.
 * [defaultWindow] is the time after a meal in which an outcome counts (editable in the settings); a meal taken with
 * medication of type [offsetBy] is listed separately instead of counting as an exposure.
 */
enum class Tag(val key: String, val defaultWindow: TagWindow, val offsetBy: MedEffect? = null) {
    LACTOSE("lactose", TagWindow.of(minutes = 30, hours = 8), offsetBy = MedEffect.ENZYME),
    FRUCTOSE("fructose", TagWindow.of(minutes = 30, hours = 8)),
    POLYOLS("polyols", TagWindow.of(minutes = 30, hours = 8)),
    FRUCTANS("fructans", TagWindow.of(minutes = 120, hours = 24)),
    GOS("gos", TagWindow.of(minutes = 120, hours = 24)),
    GLUTEN("gluten", TagWindow.of(minutes = 120, hours = 48)),
    HISTAMINE("histamine", TagWindow.of(minutes = 15, hours = 4)),
    FAT("fat", TagWindow.of(minutes = 60, hours = 6)),
    CAFFEINE("caffeine", TagWindow.of(minutes = 0, hours = 2)),
    ALCOHOL("alcohol", TagWindow.of(minutes = 60, hours = 24)),
    SPICY("spicy", TagWindow.of(minutes = 60, hours = 24)),
    CARBONATED("carbonated", TagWindow.of(minutes = 0, hours = 3));

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun fromKey(key: String): Tag = byKey[key] ?: throw IllegalArgumentException("Unknown tag '$key'")
    }
}

/** Time after a meal in which an outcome counts for a tag, both ends inclusive. */
data class TagWindow(val from: Duration, val to: Duration) {
    init { require(!from.isNegative && from < to) { "Window must satisfy 0 <= from < to, was $from..$to" } }

    companion object {
        fun of(minutes: Long, hours: Long) = TagWindow(Duration.ofMinutes(minutes), Duration.ofHours(hours))
    }
}

enum class FoodKind { INGREDIENT, DISH }

enum class FoodUnit { COUNT, SIZE }

/**
 * Built-in count unit labels. They are stored as these German words, which act as language-neutral keys: the UI
 * shows them translated, and [unitLabelKey] maps a label typed in German or English back to its key. Any other
 * label is the user's own and stays as typed.
 */
val UnitLabelKeys = listOf("Stück", "Scheibe", "Glas", "Tasse", "Dose", "Flasche", "Kugel", "Portion")

/** Count units that mean a drink. Foods counted this way are drinks unless set otherwise (SPEC 3.2). */
val DrinkUnitLabels = listOf("Glas", "Tasse", "Dose", "Flasche")

private val unitLabelSpellings: Map<String, String> = mapOf(
    "Stück" to listOf("stück", "stk", "piece", "pieces", "pc", "pcs"),
    "Scheibe" to listOf("scheibe", "scheiben", "slice", "slices"),
    "Glas" to listOf("glas", "gläser", "glass", "glasses"),
    "Tasse" to listOf("tasse", "tassen", "cup", "cups"),
    "Dose" to listOf("dose", "dosen", "can", "cans", "tin", "tins"),
    "Flasche" to listOf("flasche", "flaschen", "bottle", "bottles"),
    "Kugel" to listOf("kugel", "kugeln", "scoop", "scoops"),
    "Portion" to listOf("portion", "portionen", "portions", "serving", "servings"),
).flatMap { (key, spellings) -> spellings.map { it to key } }.toMap()

/** How a typed unit label is stored: the built-in key it names in any language, otherwise trimmed as typed. */
fun unitLabelKey(label: String): String = label.trim().let { unitLabelSpellings[it.lowercase()] ?: it }

fun isDrinkUnit(unit: FoodUnit, unitLabel: String?): Boolean =
    unit == FoodUnit.COUNT && unitLabel?.let(::unitLabelKey) in DrinkUnitLabels

/** Ordered: S < M < L. */
/** [factor] relative to M, matching the seed portion weights (SPEC 4.5): S = ½, L = 2×. */
enum class PortionSize(val factor: Double) { S(0.5), M(1.0), L(2.0) }

enum class SymptomType { BLOATING, PAIN, GAS, NAUSEA, FATIGUE, HEADACHE }

enum class MedEffect { ANTIDIARRHEAL, ENZYME, ANTIBIOTIC, PAINKILLER, OTHER }

/** How abdominal pain changed after a bowel movement (Rome IV: pain related to defecation). */
enum class PainChange { BETTER, SAME, WORSE }
