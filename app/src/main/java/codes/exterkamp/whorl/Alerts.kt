package codes.exterkamp.whorl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Turns a terminal's bell, or a notification a program asks for (OSC 9, 777 or 99), into an Android notification
 * when nobody is looking at that session: the app is in the background, the screen is off, or another session is
 * showing. So an agent in herdr or tmux that finishes or wants input reaches the phone in a pocket.
 *
 * Over mosh only the bell gets through (mosh-server drops the notification codes), so a bell's notification
 * shows the terminal's title, which programs like Claude Code and multiplexers set to something useful.
 */
object Alerts {
    private const val CHANNEL = "alerts"

    /** Bells closer together than this make one notification, not a buzz each. */
    private const val QUIET_MS = 5_000L

    private val lastPosted = HashMap<String, Long>()

    /** Main thread only, like the terminal events that call it. */
    fun post(context: Context, session: ActiveSession, title: String?, body: String?) {
        val id = session.profile.id
        val now = SystemClock.elapsedRealtime()
        val quiet = (lastPosted[id] ?: Long.MIN_VALUE / 2) > now - QUIET_MS
        if (quiet && body == null) return
        lastPosted[id] = now

        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Alerts from sessions", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When a program in a session you aren't looking at rings the bell or asks to notify"
            }
        )
        val open = PendingIntent.getActivity(
            app, notificationId(id),
            TerminalActivity.intent(app, session.profile).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val terminalTitle = session.terminal.title?.takeIf { it.isNotBlank() }
        val text = body ?: terminalTitle ?: "bell"
        val heading = listOfNotNull(session.profile.label, title?.takeIf { it.isNotBlank() }).joinToString(" · ")
        manager.notify(
            notificationId(id),
            Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(heading)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setOnlyAlertOnce(quiet)
                .build(),
        )
    }

    /** The user is looking at this machine's session now. */
    fun clear(context: Context, profileId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(profileId))
    }

    // Clear of SessionService's ongoing notification, which is 1.
    private fun notificationId(profileId: String) = 1_000 + (profileId.hashCode() and 0xFFFF)
}
