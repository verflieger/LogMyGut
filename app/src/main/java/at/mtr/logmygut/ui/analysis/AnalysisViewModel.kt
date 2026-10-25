package at.mtr.logmygut.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import at.mtr.logmygut.data.AnalysisData
import at.mtr.logmygut.data.AnalysisRepository
import at.mtr.logmygut.data.MealSummary
import at.mtr.logmygut.data.SettingsRepository
import at.mtr.logmygut.domain.AnalysisParams
import at.mtr.logmygut.domain.MedEffect
import at.mtr.logmygut.domain.SymptomType
import at.mtr.logmygut.domain.TriggerResult
import at.mtr.logmygut.domain.analyzeTriggers
import at.mtr.logmygut.domain.dailyMax
import at.mtr.logmygut.domain.dayScore
import at.mtr.logmygut.domain.lastDays
import at.mtr.logmygut.domain.movingMedian
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Day ranges offered for the trend charts (SPEC 6.2). */
val TrendRanges = listOf(7, 30, 90)

/** Weeks shown in the calendar heatmap, independent of the range. */
const val HeatmapWeeks = 26

/** Trend series for the chosen range; maps are keyed by day and only hold days with data. */
data class TrendData(
    val days: List<LocalDate>,
    val bristol: Map<LocalDate, List<Int>>,
    val bristolMedian: Map<LocalDate, Double>,
    val symptomMax: Map<SymptomType, Map<LocalDate, Int>>,
    val bowelCounts: Map<LocalDate, Int>,
    /** Day scores for the heatmap's [HeatmapWeeks] weeks, not just the range. */
    val dayScores: Map<LocalDate, Int>,
    val antibioticDays: Set<LocalDate>,
    val today: LocalDate,
)

data class TriggerState(val results: List<TriggerResult>, val meals: Map<Long, MealSummary>, val params: AnalysisParams)

class AnalysisViewModel(private val repository: AnalysisRepository, settings: SettingsRepository) : ViewModel() {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val data = MutableStateFlow<AnalysisData?>(null)

    private val _range = MutableStateFlow(30)
    val range: StateFlow<Int> = _range.asStateFlow()

    private val _symptom = MutableStateFlow(SymptomType.BLOATING)
    val symptom: StateFlow<SymptomType> = _symptom.asStateFlow()

    val trends: StateFlow<TrendData?> = combine(data.filterNotNull(), _range) { d, days -> trendsOf(d, days) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val triggers: StateFlow<TriggerState?> = combine(data.filterNotNull(), settings.analysis) { d, params ->
        TriggerState(analyzeTriggers(d.meals, d.tagsOf, d.symptoms, d.bowelMovements, d.medication, params), d.mealSummaries, params)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Reloads the journal; called whenever the screen is shown, so new entries and settings appear. */
    fun refresh() { viewModelScope.launch { data.value = repository.load() } }

    fun setRange(days: Int) { _range.value = days }
    fun setSymptom(type: SymptomType) { _symptom.value = type }

    private fun trendsOf(d: AnalysisData, rangeDays: Int): TrendData {
        val today = LocalDate.now(zone)
        val days = lastDays(today, rangeDays)
        val first = days.first()
        val date = { t: Instant -> t.atZone(zone).toLocalDate() }
        val bristolByDay = d.bowelMovements.groupBy({ date(it.time) }, { it.bristol })
        val symptomsByDay = d.symptoms.groupBy { it.type }.mapValues { (_, entries) -> entries.groupBy({ date(it.time) }, { it.severity }) }
        val inRange = { day: LocalDate -> day >= first && day <= today }

        val heatmapStart = today.minusWeeks(HeatmapWeeks.toLong())
        val severityByDay = d.symptoms.groupBy({ date(it.time) }, { it.severity })
        val scores = (severityByDay.keys + bristolByDay.keys).filter { it > heatmapStart && it <= today }
            .associateWith { dayScore(severityByDay[it].orEmpty(), bristolByDay[it].orEmpty())!! }

        return TrendData(
            days = days,
            bristol = bristolByDay.filterKeys(inRange),
            // Uses the days before the range too, so the median starts with a full window.
            bristolMedian = movingMedian(bristolByDay, days),
            symptomMax = SymptomType.entries.associateWith { type -> dailyMax(symptomsByDay[type].orEmpty()).filterKeys(inRange) },
            bowelCounts = bristolByDay.filterKeys(inRange).mapValues { it.value.size },
            dayScores = scores,
            antibioticDays = d.medication.filter { it.effect == MedEffect.ANTIBIOTIC }.mapTo(mutableSetOf()) { date(it.time) },
            today = today,
        )
    }
}
