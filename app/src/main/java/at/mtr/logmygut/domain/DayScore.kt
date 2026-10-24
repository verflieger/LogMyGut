package at.mtr.logmygut.domain

/**
 * A day's "bad day" score (SPEC 6.2): the highest symptom severity that day, with a Bristol 6–7
 * counting as 7 and any other bowel movement as 0. Null when the day has neither.
 */
fun dayScore(severities: Collection<Int>, bristolTypes: Collection<Int>): Int? {
    val bristol = bristolTypes.map { if (it >= 6) 7 else 0 }
    return (severities + bristol).maxOrNull()
}

/** Colour band of a [dayScore], matching the severity anchors (0 none, 1–3 mild, 4–6 moderate, 7–10 severe). */
enum class ScoreBand { NONE, MILD, MODERATE, SEVERE;

    companion object {
        fun of(score: Int): ScoreBand = when (score) {
            0 -> NONE
            in 1..3 -> MILD
            in 4..6 -> MODERATE
            else -> SEVERE
        }
    }
}
