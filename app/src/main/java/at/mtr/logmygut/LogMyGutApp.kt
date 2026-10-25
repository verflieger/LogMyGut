package at.mtr.logmygut

import android.app.Application
import android.content.res.Configuration
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
        container.refreshSearchTextInBackground()
        container.importSeedInBackground()
        Reminder.createChannel(this)
        container.keepReminderScheduled()
    }

    /** A language change (phone or per-app setting) renames the built-in foods. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        container.importSeedInBackground()
    }
}
