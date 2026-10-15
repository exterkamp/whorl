package codes.exterkamp.whorl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** What the terminal screen does when a session asks something mid-connect. Called on the main thread. */
interface PromptUi {
    fun askYesNo(title: String, message: String, answer: (Boolean) -> Unit)
    fun askSecret(title: String, message: String, answer: (String?) -> Unit)

    /** This session was reconnected as [next]; show that instead. */
    fun replaced(next: ActiveSession)

    /** Something shown about the session changed, such as the route it took. */
    fun refresh()
}

class ActiveSession(
    val profile: Profile,
    /** Why the app reconnected this on its own ("network changed"); null when the user started it. */
    val reason: String? = null,
) : Prompter {
    val automatic: Boolean get() = reason != null

    /** The latest progress line while connecting, for the header's status line. */
    @Volatile var progress: String = "connecting"
        private set

    lateinit var terminal: TerminalSession
    lateinit var transport: com.termux.terminal.TerminalTransport
    @Volatile var finished = false

    /** How the connection reached the machine; null until it's up. */
    @Volatile var route: Route? = null
        private set

    /** The session that took over when this one was reconnected. Main thread only. */
    var replacedBy: ActiveSession? = null

    /** The screen currently showing this session, if any. */
    @Volatile var ui: PromptUi? = null
    var listener: TerminalSessionClient? = null

    private val main = Handler(Looper.getMainLooper())
    private val uiLock = Object()

    fun attach(ui: PromptUi?) {
        synchronized(uiLock) {
            this.ui = ui
            uiLock.notifyAll()
        }
    }

    /** Wait for a screen to be showing this session, then ask on the main thread. */
    private fun <T> ask(fallback: T, request: (PromptUi, (T) -> Unit) -> Unit): T {
        val deadline = System.currentTimeMillis() + 5 * 60_000
        while (true) {
            val ui = synchronized(uiLock) {
                while (ui == null && System.currentTimeMillis() < deadline) uiLock.wait(1_000)
                ui
            } ?: return fallback
            val future = CompletableFuture<T>()
            main.post { request(ui) { future.complete(it) } }
            return try {
                future.get(5, TimeUnit.MINUTES)
            } catch (e: Exception) {
                fallback
            }
        }
    }

    override fun yesNo(title: String, message: String): Boolean =
        ask(false) { ui, answer -> ui.askYesNo(title, message, answer) }

    override fun secret(title: String, message: String): String? =
        ask<String?>(null) { ui, answer -> ui.askSecret(title, message, answer) }

    override fun status(text: String) {
        terminal.printLocal("\u001b[2m$text\u001b[0m\r\n")
        progress = text.trim().removeSuffix("…").lowercase()
        main.post { ui?.refresh() }
    }

    override fun connected(route: Route) {
        this.route = route
        main.post { ui?.refresh() }
    }
}

/** Live sessions, which outlive any screen showing them. Main thread only. */
object Sessions {
    private val sessions = LinkedHashMap<String, ActiveSession>()

    /** Bumped whenever a session starts, ends or is removed, so Compose screens reading it stay current. */
    var version by mutableIntStateOf(0)
        private set

    fun get(id: String): ActiveSession? = sessions[id]

    fun all(): List<ActiveSession> = sessions.values.toList()

    fun isConnected(profileId: String) = sessions.values.any { it.profile.id == profileId && !it.finished }

    private var restored = false

    /**
     * After the app was killed (an update, or Android reclaiming memory), reconnect whatever was open. With a
     * startup command such as herdr or tmux attach, that lands the user back where they were.
     */
    fun restore(context: Context) {
        if (restored) return
        restored = true
        val store = Store.get(context)
        val ids = store.openSessions
        val profiles = ids.mapNotNull { store.profile(it) }
        store.openSessions = profiles.map { it.id }.toSet()
        // Connect right away at a default size rather than waiting for a terminal view to give it one; the view
        // resizes it when the session is shown. Any password prompt waits until then.
        profiles.filter { it.id !in sessions }.forEach { open(context, it).terminal.updateSize(80, 24) }
    }

    /** The live session for this profile, or a newly started one. */
    fun open(context: Context, profile: Profile): ActiveSession {
        sessions.values.firstOrNull { it.profile.id == profile.id && !it.finished }?.let { return it }
        sessions.values.filter { it.profile.id == profile.id }.forEach { remove(context, it) }
        return start(context, profile, reason = null)
    }

    /**
     * Reconnect [old] as a new session for the same machine, racing its addresses again. The mosh-server it was
     * using is stopped by the new connection (its pid is still stored), and the multiplexer in the run-on-connect
     * command, if any, puts the user back where they were. Whatever screen shows [old] switches to the new one.
     */
    fun reconnect(context: Context, old: ActiveSession, reason: String?): ActiveSession {
        val size = old.terminal.emulator?.let { it.mColumns to it.mRows } ?: (80 to 24)
        val ui = old.ui
        sessions.remove(old.profile.id)
        val next = start(context, old.profile, reason)
        old.replacedBy = next
        old.attach(null)
        old.listener = null
        old.terminal.finishIfRunning()
        next.terminal.updateSize(size.first, size.second)
        ui?.replaced(next)
        return next
    }

    private fun start(context: Context, profile: Profile, reason: String?): ActiveSession {
        val app = context.applicationContext
        val active = ActiveSession(profile, reason)
        val transport = when (profile.protocol) {
            Protocol.SSH -> SshTransport(app, profile, active)
            Protocol.MOSH -> MoshTransport(app, profile, active)
        }
        active.transport = transport
        active.terminal = TerminalSession(transport, 5000, Forwarder(app, active))
        active.terminal.mSessionName = profile.id
        sessions[profile.id] = active
        Store.get(app).let { it.openSessions = it.openSessions + profile.id }
        SessionService.update(app)
        version++
        return active
    }

    /** The user ended this session: stop it and don't bring it back on the next launch. */
    fun remove(context: Context, session: ActiveSession) {
        session.terminal.finishIfRunning()
        // Already reconnected: the new session owns the machine's slot, its mosh-server and its place in the list.
        if (session.replacedBy != null) return
        (session.transport as? MoshTransport)?.forgetServer()
        Store.get(context).let { it.openSessions = it.openSessions - session.profile.id }
        sessions.remove(session.profile.id)
        SessionService.update(context.applicationContext)
        version++
    }

    /** Hands terminal events to whichever screen is attached, and tracks when sessions end. */
    /** Sessions worth reconnecting without being asked: dropped, or an automatic reconnect that hasn't got through. */
    fun stalled(): List<ActiveSession> = sessions.values.filter {
        it.finished && it.replacedBy == null &&
            (it.terminal.exitStatus == EXIT_DROPPED || it.automatic && it.route == null)
    }

    /** Live sessions and the route each one is using. */
    fun routed(): List<Pair<ActiveSession, Route>> =
        sessions.values.filter { !it.finished }.mapNotNull { s -> s.route?.let { s to it } }

    fun isCurrent(session: ActiveSession) = sessions[session.profile.id] === session

    private class Forwarder(private val app: Context, private val active: ActiveSession) : TerminalSessionClient {
        override fun onTextChanged(s: TerminalSession) { active.listener?.onTextChanged(s) }
        override fun onTitleChanged(s: TerminalSession) { active.listener?.onTitleChanged(s) }
        override fun onSessionFinished(s: TerminalSession) {
            active.finished = true
            // Replaced by a reconnect: the new session carries on, and nothing about this one matters any more.
            if (active.replacedBy != null) return
            if (s.exitStatus == 0) {
                // The shell exited normally (the user typed exit): nothing to reattach to later.
                (active.transport as? MoshTransport)?.forgetServer()
                Store.get(app).let { it.openSessions = it.openSessions - active.profile.id }
            }
            SessionService.update(app)
            version++
            active.listener?.onSessionFinished(s)
            if (s.exitStatus == EXIT_DROPPED) Reconnector.dropped(app)
        }
        override fun onCopyTextToClipboard(s: TerminalSession, text: String) { active.listener?.onCopyTextToClipboard(s, text) }
        override fun onPasteTextFromClipboard(s: TerminalSession?) { active.listener?.onPasteTextFromClipboard(s) }
        // With the session on screen a bell just buzzes; otherwise it, and any notification a program asks for,
        // becomes an Android notification.
        override fun onBell(s: TerminalSession) {
            val listener = active.listener
            if (listener != null) listener.onBell(s)
            else if (active.replacedBy == null) Alerts.post(app, active, title = null, body = null)
        }
        override fun onNotification(s: TerminalSession, title: String?, body: String?) {
            if (active.listener == null && active.replacedBy == null) Alerts.post(app, active, title, body)
        }
        override fun onColorsChanged(s: TerminalSession) { active.listener?.onColorsChanged(s) }
        override fun onTerminalCursorStateChange(state: Boolean) { active.listener?.onTerminalCursorStateChange(state) }
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { Log.e(tag, message.orEmpty()) }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag, message.orEmpty()) }
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "", e) }
    }
}

/** Keeps the process alive while any session is connected, so Android doesn't kill mosh in the background. */
class SessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Reconnector.watch(this)
    }

    override fun onDestroy() {
        Reconnector.unwatch(this)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val live = Sessions.all().filterNot { it.finished }
        if (live.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val text = live.joinToString(", ") { it.profile.label }
        val notification = notification(if (live.size == 1) "Connected" else "${live.size} sessions", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun notification(title: String, text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Active sessions", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "sessions"
        private const val NOTIFICATION_ID = 1

        fun update(context: Context) {
            val live = Sessions.all().any { !it.finished }
            val intent = Intent(context, SessionService::class.java)
            if (live) context.startForegroundService(intent) else context.stopService(intent)
        }
    }
}
