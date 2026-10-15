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

    /**
     * An agent in herdr needs the user, or finished. Tapping it opens the session and switches herdr to that agent.
     * Nothing is shown if the user is already looking at that agent.
     */
    fun agent(context: Context, session: ActiveSession, agent: HerdrAgent, event: HerdrProtocol.Event) {
        if (session.listener != null && agent.focused) return
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Alerts from sessions", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When a program in a session you aren't looking at rings the bell or asks to notify"
            }
        )
        val id = agentNotificationId(session.profile, agent.paneId)
        val open = PendingIntent.getActivity(
            app, id,
            TerminalActivity.intent(app, session.profile)
                .putExtra(TerminalActivity.EXTRA_FOCUS_AGENT, agent.paneId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (heading, category) = when (event) {
            HerdrProtocol.Event.NEEDS_INPUT -> "${agent.name} needs your input" to Notification.CATEGORY_REMINDER
            HerdrProtocol.Event.FINISHED -> "${agent.name} finished" to Notification.CATEGORY_STATUS
        }
        val text = listOfNotNull(agent.title.takeIf { it.isNotBlank() }, session.profile.label).joinToString(" · ")
        manager.notify(
            id,
            Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(heading)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(category)
                .build(),
        )
    }

    /** The agent stopped waiting (answered from another device, say), so its "needs your input" no longer applies. */
    fun clearAgent(context: Context, profile: Profile, paneId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(agentNotificationId(profile, paneId))
    }

    private fun agentNotificationId(profile: Profile, paneId: String) =
        100_000 + ((profile.id + "/" + paneId).hashCode() and 0xFFFFF)

    /** The user is looking at this machine's session now. */
    fun clear(context: Context, profileId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(profileId))
    }

    // Clear of SessionService's ongoing notification, which is 1.
    private fun notificationId(profileId: String) = 1_000 + (profileId.hashCode() and 0xFFFF)
}
