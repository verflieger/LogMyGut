package at.mtr.logmygut.domain

/**
 * Core enums shared by the database, seed import and analysis.
 * Each has a stable [key] that is what gets persisted, so enum constants can be renamed safely.
 */

enum class Tag(val key: String) {
    LACTOSE("lactose"),
    FRUCTOSE("fructose"),
    POLYOLS("polyols"),
    FRUCTANS("fructans"),
    GOS("gos"),
    GLUTEN("gluten"),
    HISTAMINE("histamine"),
    FAT("fat"),
    CAFFEINE("caffeine"),
    ALCOHOL("alcohol"),
    SPICY("spicy"),
    CARBONATED("carbonated");

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun fromKey(key: String): Tag = byKey[key] ?: throw IllegalArgumentException("Unknown tag '$key'")
    }
}

enum class FoodKind { INGREDIENT, DISH }

enum class FoodUnit { COUNT, SIZE }

/** Ordered: S < M < L. */
/** [factor] relative to M, matching the seed portion weights (SPEC 4.5): S = ½, L = 2×. */
enum class PortionSize(val factor: Double) { S(0.5), M(1.0), L(2.0) }

enum class SymptomType { BLOATING, PAIN, GAS, NAUSEA, FATIGUE, HEADACHE }

enum class MedEffect { ANTIDIARRHEAL, ENZYME, ANTIBIOTIC, PAINKILLER, OTHER }

/** How abdominal pain changed after a bowel movement (Rome IV: pain related to defecation). */
enum class PainChange { BETTER, SAME, WORSE }
