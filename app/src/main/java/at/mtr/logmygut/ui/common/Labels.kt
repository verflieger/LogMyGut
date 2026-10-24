package at.mtr.logmygut.ui.common

import androidx.annotation.StringRes
import at.mtr.logmygut.R
import at.mtr.logmygut.domain.MealLabel
import at.mtr.logmygut.domain.MedEffect
import at.mtr.logmygut.domain.PainChange
import at.mtr.logmygut.domain.SymptomType
import at.mtr.logmygut.domain.Tag

// German display labels for domain enums. Kept in one place so all screens use the same words.

@StringRes fun Tag.label(): Int = when (this) {
    Tag.LACTOSE -> R.string.tag_lactose
    Tag.FRUCTOSE -> R.string.tag_fructose
    Tag.POLYOLS -> R.string.tag_polyols
    Tag.FRUCTANS -> R.string.tag_fructans
    Tag.GOS -> R.string.tag_gos
    Tag.GLUTEN -> R.string.tag_gluten
    Tag.HISTAMINE -> R.string.tag_histamine
    Tag.FAT -> R.string.tag_fat
    Tag.CAFFEINE -> R.string.tag_caffeine
    Tag.ALCOHOL -> R.string.tag_alcohol
    Tag.SPICY -> R.string.tag_spicy
    Tag.CARBONATED -> R.string.tag_carbonated
}

@StringRes fun SymptomType.label(): Int = when (this) {
    SymptomType.BLOATING -> R.string.symptom_bloating
    SymptomType.PAIN -> R.string.symptom_pain
    SymptomType.GAS -> R.string.symptom_gas
    SymptomType.NAUSEA -> R.string.symptom_nausea
    SymptomType.FATIGUE -> R.string.symptom_fatigue
    SymptomType.HEADACHE -> R.string.symptom_headache
}

@StringRes fun MedEffect.label(): Int = when (this) {
    MedEffect.ANTIDIARRHEAL -> R.string.med_effect_antidiarrheal
    MedEffect.ENZYME -> R.string.med_effect_enzyme
    MedEffect.ANTIBIOTIC -> R.string.med_effect_antibiotic
    MedEffect.PAINKILLER -> R.string.med_effect_painkiller
    MedEffect.OTHER -> R.string.med_effect_other
}

@StringRes fun PainChange.label(): Int = when (this) {
    PainChange.BETTER -> R.string.pain_better
    PainChange.SAME -> R.string.pain_same
    PainChange.WORSE -> R.string.pain_worse
}

@StringRes fun MealLabel.label(): Int = when (this) {
    MealLabel.BREAKFAST -> R.string.meal_label_breakfast
    MealLabel.LUNCH -> R.string.meal_label_lunch
    MealLabel.SNACK -> R.string.meal_label_snack
    MealLabel.DINNER -> R.string.meal_label_dinner
}

@StringRes fun bristolDescription(type: Int): Int = when (type) {
    1 -> R.string.bristol_1
    2 -> R.string.bristol_2
    3 -> R.string.bristol_3
    4 -> R.string.bristol_4
    5 -> R.string.bristol_5
    6 -> R.string.bristol_6
    7 -> R.string.bristol_7
    else -> throw IllegalArgumentException("Bristol type must be 1..7, was $type")
}

@StringRes fun bristolGroup(type: Int): Int = when (type) {
    1, 2 -> R.string.bristol_group_constipation
    3, 4, 5 -> R.string.bristol_group_normal
    else -> R.string.bristol_group_diarrhea
}

@StringRes fun severityLabel(severity: Int): Int = when (severity) {
    0 -> R.string.severity_none
    in 1..3 -> R.string.severity_mild
    in 4..6 -> R.string.severity_moderate
    else -> R.string.severity_severe
}
