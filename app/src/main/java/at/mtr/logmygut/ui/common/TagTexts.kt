package at.mtr.logmygut.ui.common

import androidx.annotation.StringRes
import at.mtr.logmygut.R
import at.mtr.logmygut.domain.Tag

/** A tag's display name, and for the info page what it is, where it's typically found and how a doctor can check it. */
data class TagTexts(@StringRes val label: Int, @StringRes val what: Int, @StringRes val foods: Int, @StringRes val test: Int)

fun Tag.texts(): TagTexts = when (this) {
    Tag.LACTOSE -> TagTexts(R.string.tag_lactose, R.string.info_tag_lactose_what, R.string.info_tag_lactose_foods, R.string.info_tag_lactose_test)
    Tag.FRUCTOSE -> TagTexts(R.string.tag_fructose, R.string.info_tag_fructose_what, R.string.info_tag_fructose_foods, R.string.info_tag_fructose_test)
    Tag.POLYOLS -> TagTexts(R.string.tag_polyols, R.string.info_tag_polyols_what, R.string.info_tag_polyols_foods, R.string.info_tag_polyols_test)
    Tag.FRUCTANS -> TagTexts(R.string.tag_fructans, R.string.info_tag_fructans_what, R.string.info_tag_fructans_foods, R.string.info_tag_fructans_test)
    Tag.GOS -> TagTexts(R.string.tag_gos, R.string.info_tag_gos_what, R.string.info_tag_gos_foods, R.string.info_tag_gos_test)
    Tag.GLUTEN -> TagTexts(R.string.tag_gluten, R.string.info_tag_gluten_what, R.string.info_tag_gluten_foods, R.string.info_tag_gluten_test)
    Tag.HISTAMINE -> TagTexts(R.string.tag_histamine, R.string.info_tag_histamine_what, R.string.info_tag_histamine_foods, R.string.info_tag_histamine_test)
    Tag.FAT -> TagTexts(R.string.tag_fat, R.string.info_tag_fat_what, R.string.info_tag_fat_foods, R.string.info_tag_fat_test)
    Tag.CAFFEINE -> TagTexts(R.string.tag_caffeine, R.string.info_tag_caffeine_what, R.string.info_tag_caffeine_foods, R.string.info_tag_caffeine_test)
    Tag.ALCOHOL -> TagTexts(R.string.tag_alcohol, R.string.info_tag_alcohol_what, R.string.info_tag_alcohol_foods, R.string.info_tag_alcohol_test)
    Tag.SPICY -> TagTexts(R.string.tag_spicy, R.string.info_tag_spicy_what, R.string.info_tag_spicy_foods, R.string.info_tag_spicy_test)
    Tag.CARBONATED -> TagTexts(R.string.tag_carbonated, R.string.info_tag_carbonated_what, R.string.info_tag_carbonated_foods, R.string.info_tag_carbonated_test)
}
