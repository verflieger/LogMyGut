package at.mtr.logmygut.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import at.mtr.logmygut.domain.AnalysisParams
import at.mtr.logmygut.domain.DefaultMinExposures
import at.mtr.logmygut.domain.DefaultSymptomThreshold
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.TagWindow
import java.time.Duration
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("settings")

/** Daily reminder (SPEC 3.7). */
data class ReminderSettings(val enabled: Boolean = true, val time: LocalTime = DefaultReminderTime)

val DefaultReminderTime: LocalTime = LocalTime.of(21, 0)

/** App settings in DataStore. */
class SettingsRepository(private val store: DataStore<Preferences>) {

    val reminder: Flow<ReminderSettings> = store.data.map { prefs ->
        ReminderSettings(
            enabled = prefs[ReminderEnabled] ?: true,
            time = prefs[ReminderMinuteOfDay]?.let { LocalTime.of(it / 60, it % 60) } ?: DefaultReminderTime,
        )
    }

    /** Whether the app already asked for the notification permission once on its own. */
    val notificationPermissionAsked: Flow<Boolean> = store.data.map { it[PermissionAsked] ?: false }

    suspend fun setReminderEnabled(enabled: Boolean) { store.edit { it[ReminderEnabled] = enabled } }

    suspend fun setReminderTime(time: LocalTime) { store.edit { it[ReminderMinuteOfDay] = time.hour * 60 + time.minute } }

    suspend fun markNotificationPermissionAsked() { store.edit { it[PermissionAsked] = true } }

    /** Analysis parameters (SPEC 5, 6.3). A window that doesn't parse falls back to its default. */
    val analysis: Flow<AnalysisParams> = store.data.map { prefs ->
        AnalysisParams(
            windows = Tag.entries.mapNotNull { tag -> prefs[windowKey(tag)]?.let(::parseWindow)?.let { tag to it } }.toMap(),
            symptomThreshold = prefs[SymptomThreshold] ?: DefaultSymptomThreshold,
            minExposures = prefs[MinExposures] ?: DefaultMinExposures,
        )
    }

    suspend fun setTagWindow(tag: Tag, window: TagWindow) {
        store.edit { it[windowKey(tag)] = "${window.from.toMinutes()}-${window.to.toMinutes()}" }
    }

    suspend fun setSymptomThreshold(value: Int) {
        require(value in 1..10) { "Threshold must be 1..10" }
        store.edit { it[SymptomThreshold] = value }
    }

    suspend fun setMinExposures(value: Int) {
        require(value >= 1) { "Minimum exposures must be >= 1" }
        store.edit { it[MinExposures] = value }
    }

    /** Back to the defaults from SPEC 4.3 and 5. */
    suspend fun resetAnalysis() {
        store.edit { prefs ->
            Tag.entries.forEach { prefs.remove(windowKey(it)) }
            prefs.remove(SymptomThreshold)
            prefs.remove(MinExposures)
        }
    }

    companion object {
        private val ReminderEnabled = booleanPreferencesKey("reminder_enabled")
        private val ReminderMinuteOfDay = intPreferencesKey("reminder_minute_of_day")
        private val PermissionAsked = booleanPreferencesKey("notification_permission_asked")
        private val SymptomThreshold = intPreferencesKey("analysis_symptom_threshold")
        private val MinExposures = intPreferencesKey("analysis_min_exposures")

        /** Stored as "fromMinutes-toMinutes", keyed by the tag's stable key. */
        private fun windowKey(tag: Tag) = stringPreferencesKey("analysis_window_${tag.key}")

        private fun parseWindow(text: String): TagWindow? {
            val (from, to) = text.split("-").takeIf { it.size == 2 }?.map { it.toLongOrNull() ?: return null } ?: return null
            return runCatching { TagWindow(Duration.ofMinutes(from), Duration.ofMinutes(to)) }.getOrNull()
        }

        fun create(context: Context) = SettingsRepository(context.settingsStore)
    }
}
