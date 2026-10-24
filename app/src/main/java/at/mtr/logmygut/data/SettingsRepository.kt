package at.mtr.logmygut.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("settings")

/** Daily reminder (SPEC 3.7). */
data class ReminderSettings(val enabled: Boolean = true, val time: LocalTime = DefaultReminderTime)

val DefaultReminderTime: LocalTime = LocalTime.of(21, 0)

/** App settings in DataStore. Analysis parameters join in milestone 5. */
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

    companion object {
        private val ReminderEnabled = booleanPreferencesKey("reminder_enabled")
        private val ReminderMinuteOfDay = intPreferencesKey("reminder_minute_of_day")
        private val PermissionAsked = booleanPreferencesKey("notification_permission_asked")

        fun create(context: Context) = SettingsRepository(context.settingsStore)
    }
}
