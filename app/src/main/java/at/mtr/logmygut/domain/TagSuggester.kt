package at.mtr.logmygut.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class KeywordRulesFile(val version: Int, val rules: List<KeywordRuleJson>)

@Serializable
private data class KeywordRuleJson(
    val keywords: List<String>,
    val tags: List<String>,
    val removes: List<String> = emptyList(),
)

private data class KeywordRule(val keywords: List<String>, val adds: Set<Tag>, val removes: Set<Tag>)

/**
 * Suggests tags for a user-created ingredient from its name (SPEC 3.2), using the keyword rules of every language
 * (`keyword_rules.de.json`, `keyword_rules.en.json`), so a name in either language gets suggestions. All matching
 * rules' tags are unioned first, then all matching rules' `removes` are applied, so "Laktosefreie Milch" ends up
 * without lactose.
 */
class TagSuggester private constructor(private val rules: List<KeywordRule>) {

    fun suggest(foodName: String): Set<Tag> {
        val name = normalizeForSearch(foodName)
        val matching = rules.filter { rule -> rule.keywords.any { it in name } }
        return matching.flatMapTo(mutableSetOf()) { it.adds } - matching.flatMapTo(mutableSetOf()) { it.removes }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Combines the rules of all given keyword rule files. */
        fun fromJson(vararg texts: String): TagSuggester {
            val rules = texts.flatMap { json.decodeFromString<KeywordRulesFile>(it).rules }
            return TagSuggester(
                rules.map { rule ->
                    KeywordRule(
                        keywords = rule.keywords.map(::normalizeForSearch),
                        adds = rule.tags.mapTo(mutableSetOf(), Tag::fromKey),
                        removes = rule.removes.mapTo(mutableSetOf(), Tag::fromKey),
                    )
                },
            )
        }
    }
}
