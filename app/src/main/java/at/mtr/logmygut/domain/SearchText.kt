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
