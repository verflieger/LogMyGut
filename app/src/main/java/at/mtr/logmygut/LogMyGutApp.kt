package at.mtr.logmygut

import android.app.Application
import at.mtr.logmygut.reminder.Reminder

/**
 * Process-wide entry point. Holds the [AppContainer] (manual dependency injection):
 * screens get their dependencies from `(application as LogMyGutApp).container`.
 */
class LogMyGutApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.importSeedInBackground()
        Reminder.createChannel(this)
        container.keepReminderScheduled()
    }
}
