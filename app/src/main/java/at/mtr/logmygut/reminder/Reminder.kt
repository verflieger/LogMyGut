package at.mtr.logmygut.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import at.mtr.logmygut.LogMyGutApp
import at.mtr.logmygut.MainActivity
import at.mtr.logmygut.R
import at.mtr.logmygut.data.ReminderSettings
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first

/**
 * Daily reminder (SPEC 3.7). WorkManager persists scheduled work, so the reminder survives reboots.
 * Each run shows the notification and schedules the next one, which keeps it at the chosen wall-clock
 * time across daylight-saving changes (a periodic 24 h request would drift by an hour).
 */
object Reminder {
    const val ChannelId = "daily_reminder"
    private const val WorkName = "daily_reminder"
    private const val NotificationId = 1

    fun createChannel(context: Context) {
        val channel = NotificationChannel(ChannelId, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** (Re)schedules the next reminder, or cancels it when switched off. */
    fun schedule(context: Context, settings: ReminderSettings, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val workManager = try {
            WorkManager.getInstance(context)
        } catch (e: IllegalStateException) {
            // Not initialized, e.g. in JVM tests without the startup provider. Nothing to schedule then.
            Log.w("LogMyGut", "WorkManager unavailable, reminder not scheduled", e)
            return
        }
        if (!settings.enabled) {
            workManager.cancelUniqueWork(WorkName)
            return
        }
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayUntilNext(ZonedDateTime.now(), settings.time))
            .build()
        workManager.enqueueUniqueWork(WorkName, policy, request)
    }

    fun show(context: Context) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.reminder_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NotificationId, notification)
    }
}

/** Time until the next occurrence of [time] strictly after [now], in [now]'s time zone. */
fun delayUntilNext(now: ZonedDateTime, time: LocalTime): Duration {
    var next = now.toLocalDate().atTime(time).atZone(now.zone)
    if (!next.isAfter(now)) next = next.plusDays(1)
    return Duration.between(now, next)
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = (applicationContext as LogMyGutApp).container.settings.reminder.first()
        if (settings.enabled) {
            Reminder.show(applicationContext)
            // Appended, so it doesn't cancel this still-running work.
            Reminder.schedule(applicationContext, settings, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return Result.success()
    }
}
