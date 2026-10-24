package at.mtr.logmygut.domain

import java.text.Normalizer

/**
 * Normalizes text for case- and umlaut-insensitive matching:
 * "Käse" and "kase" both become "kase", "Grüße" becomes "grusse".
 */
fun normalizeForSearch(text: String): String {
    val lower = text.trim().lowercase().replace("ß", "ss")
    return Normalizer.normalize(lower, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}

/**
 * The alias that matched [normalizedQuery] when the name itself doesn't, e.g. "Tomate" for Paradeiser.
 * Prefers exact, then prefix, then substring matches.
 */
fun matchedAlias(name: String, aliases: List<String>, normalizedQuery: String): String? {
    if (normalizedQuery.isEmpty() || normalizedQuery in normalizeForSearch(name)) return null
    return aliases
        .map { it to normalizeForSearch(it) }
        .filter { (_, n) -> normalizedQuery in n }
        .minByOrNull { (_, n) -> if (n == normalizedQuery) 0 else if (n.startsWith(normalizedQuery)) 1 else 2 }
        ?.first
}
