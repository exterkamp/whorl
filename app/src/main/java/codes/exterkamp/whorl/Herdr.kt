package codes.exterkamp.whorl

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.jcraft.jsch.ChannelDirectStreamLocal
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** A coding agent that herdr is tracking in one of its panes. */
data class HerdrAgent(
    val paneId: String,
    /** "claude", "codex"… */
    val kind: String,
    val status: Status,
    /** The terminal title the agent set, which for Claude Code is what it's working on. */
    val title: String,
    val workspaceId: String,
    val focused: Boolean,
    /** Goes up each time the agent finishes a turn, so a finish isn't missed between two polls. */
    val completions: Long,
) {
    enum class Status { WORKING, BLOCKED, DONE, IDLE, UNKNOWN }

    val name: String get() = kind.replaceFirstChar { it.uppercase() }
    val label: String get() = title.ifBlank { name }
}

/** herdr's socket API: one JSON request per connection, answered with one JSON line. */
object HerdrProtocol {
    enum class Event { NEEDS_INPUT, FINISHED }

    fun request(id: String, method: String, params: JSONObject = JSONObject()): String =
        JSONObject().put("id", id).put("method", method).put("params", params).toString() + "\n"

    /** The result of a request, or an IOException carrying herdr's error message. */
    fun result(line: String): JSONObject {
        val response = JSONObject(line)
        response.optJSONObject("error")?.let { throw IOException(it.optString("message", "herdr error")) }
        return response.getJSONObject("result")
    }

    fun parseAgents(result: JSONObject): List<HerdrAgent> {
        val agents = result.optJSONArray("agents") ?: JSONArray()
        return (0 until agents.length()).map { i ->
            val a = agents.getJSONObject(i)
            HerdrAgent(
                paneId = a.getString("pane_id"),
                kind = a.optString("agent", "agent"),
                status = when (a.optString("agent_status")) {
                    "working" -> HerdrAgent.Status.WORKING
                    "blocked" -> HerdrAgent.Status.BLOCKED
                    "done" -> HerdrAgent.Status.DONE
                    "idle" -> HerdrAgent.Status.IDLE
                    else -> HerdrAgent.Status.UNKNOWN
                },
                title = a.optString("terminal_title_stripped").trim(),
                workspaceId = a.optString("workspace_id"),
                focused = a.optBoolean("focused"),
                completions = a.optLong("completion_seq", 0),
            )
        }
    }

    /**
     * What happened between two polls that's worth a notification: an agent that started waiting on the user, or
     * one that finished a turn. Agents seen for the first time have nothing to compare against and are skipped.
     */
    fun events(before: List<HerdrAgent>, after: List<HerdrAgent>): List<Pair<HerdrAgent, Event>> {
        val previous = before.associateBy { it.paneId }
        return after.mapNotNull { agent ->
            val old = previous[agent.paneId] ?: return@mapNotNull null
            when {
                agent.status == HerdrAgent.Status.BLOCKED && old.status != HerdrAgent.Status.BLOCKED ->
                    agent to Event.NEEDS_INPUT
                agent.completions > old.completions ||
                    agent.status == HerdrAgent.Status.DONE && old.status != HerdrAgent.Status.DONE ->
                    agent to Event.FINISHED
                else -> null
            }
        }
    }

    /** "3 agents · 1 needs you", for the session card and the terminal header. */
    fun summary(agents: List<HerdrAgent>): String? {
        if (agents.isEmpty()) return null
        val waiting = agents.count { it.status == HerdrAgent.Status.BLOCKED }
        val working = agents.count { it.status == HerdrAgent.Status.WORKING }
        val count = if (agents.size == 1) "1 agent" else "${agents.size} agents"
        return when {
            waiting > 0 -> "$count · $waiting needs you"
            working > 0 -> "$count · $working working"
            else -> count
        }
    }

    /** A one-character status for menus, which can't be colored. */
    fun glyph(status: HerdrAgent.Status) = when (status) {
        HerdrAgent.Status.BLOCKED -> "⚠"
        HerdrAgent.Status.WORKING -> "◐"
        HerdrAgent.Status.DONE -> "✓"
        HerdrAgent.Status.IDLE -> "○"
        HerdrAgent.Status.UNKNOWN -> "·"
    }

    fun describe(status: HerdrAgent.Status) = when (status) {
        HerdrAgent.Status.BLOCKED -> "needs you"
        HerdrAgent.Status.WORKING -> "working"
        HerdrAgent.Status.DONE -> "done"
        HerdrAgent.Status.IDLE -> "idle"
        HerdrAgent.Status.UNKNOWN -> "unknown"
    }

    /**
     * The socket of the herdr server for the default session, as the server would find it. Prints nothing when
     * herdr isn't running there.
     */
    const val FIND_SOCKET =
        "p=\"\${HERDR_SOCKET_PATH:-\${XDG_CONFIG_HOME:-\$HOME/.config}/herdr/herdr.sock}\"; [ -S \"\$p\" ] && printf %s \"\$p\""
}

/**
 * Keeps an eye on the herdr server on a machine with an open session, over a second SSH connection of its own so
 * it works for mosh sessions too. It polls herdr's agent list, raises an alert when an agent needs the user or
 * finishes, and can switch herdr to an agent. Machines without herdr just never become [available].
 */
class HerdrLink(private val app: Context, private val session: ActiveSession) {
    /** The agents as of the last poll; empty when herdr isn't reachable. */
    @Volatile var agents: List<HerdrAgent> = emptyList()
        private set

    /** herdr is running on the machine and answering. */
    @Volatile var available = false
        private set

    @Volatile private var running = true
    @Volatile private var ssh: Session? = null
    @Volatile private var socket: String? = null
    private val ids = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var started = false

    /** Once the session is up; later calls do nothing. */
    fun start() {
        if (started) return
        started = true
        thread(name = "herdr-${session.profile.id}", isDaemon = true) { run() }
    }

    fun stop() {
        running = false
        thread(isDaemon = true) { runCatching { ssh?.disconnect() } }
        update(false, emptyList())
    }

    /** An agent to switch to once herdr answers, asked for before it did (from a notification, at launch). */
    @Volatile private var pendingFocus: String? = null

    /** Switch herdr to this agent's pane, now or as soon as herdr is reachable. */
    fun focus(paneId: String) {
        if (!available) {
            pendingFocus = paneId
            return
        }
        thread(isDaemon = true) {
            runCatching { call("agent.focus", JSONObject().put("target", paneId)) }
            poll()
        }
    }

    private fun run() {
        var backoff = RETRY_MS
        while (running) {
            try {
                val (connection, _) = Ssh.open(app, session.profile, Silent)
                ssh = connection
                backoff = RETRY_MS
                var previous: List<HerdrAgent>? = null
                while (running && connection.isConnected) {
                    socket = socket ?: exec(connection, HerdrProtocol.FIND_SOCKET).ifBlank { null }
                    if (socket == null) {
                        // herdr may not have started yet (it's often the run-on-connect command); look again later.
                        update(false, emptyList())
                        sleep(LOOK_AGAIN_MS)
                        continue
                    }
                    val now = try {
                        HerdrProtocol.parseAgents(call("agent.list"))
                    } catch (e: IOException) {
                        // The server went away, or moved; find it again.
                        socket = null
                        continue
                    }
                    previous?.let { before ->
                        HerdrProtocol.events(before, now).forEach { (agent, event) ->
                            main.post { Alerts.agent(app, session, agent, event) }
                        }
                        now.filter { it.status != HerdrAgent.Status.BLOCKED }.forEach { agent ->
                            if (before.any { it.paneId == agent.paneId && it.status == HerdrAgent.Status.BLOCKED }) {
                                main.post { Alerts.clearAgent(app, session.profile, agent.paneId) }
                            }
                        }
                    }
                    previous = now
                    update(true, now)
                    val pane = pendingFocus
                    if (pane != null) {
                        pendingFocus = null
                        runCatching { call("agent.focus", JSONObject().put("target", pane)) }
                        continue
                    }
                    sleep(POLL_MS)
                }
            } catch (e: Exception) {
                // Unreachable, or the connection dropped; try again after a pause.
            }
            runCatching { ssh?.disconnect() }
            ssh = null
            socket = null
            update(false, emptyList())
            sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_RETRY_MS)
        }
    }

    private fun poll() {
        runCatching { update(true, HerdrProtocol.parseAgents(call("agent.list"))) }
    }

    private fun update(available: Boolean, agents: List<HerdrAgent>) {
        if (available == this.available && agents == this.agents) return
        this.available = available
        this.agents = agents
        main.post {
            Sessions.changed()
            session.ui?.refresh()
        }
    }

    /** One request over a fresh channel to herdr's socket. */
    private fun call(method: String, params: JSONObject = JSONObject()): JSONObject {
        val connection = ssh ?: throw IOException("not connected")
        val path = socket ?: throw IOException("no herdr socket")
        val channel = connection.openChannel("direct-streamlocal@openssh.com") as ChannelDirectStreamLocal
        channel.socketPath = path
        try {
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(CALL_TIMEOUT_MS)
            output.write(HerdrProtocol.request("whorl:${ids.incrementAndGet()}", method, params).toByteArray())
            output.flush()
            val line = ByteArrayOutputStream()
            val deadline = System.currentTimeMillis() + CALL_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                line.write(b)
            }
            if (line.size() == 0) throw IOException("no answer from herdr")
            return HerdrProtocol.result(line.toString(Charsets.UTF_8.name()))
        } finally {
            channel.disconnect()
        }
    }

    private fun exec(connection: Session, command: String): String {
        val channel = connection.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val output = ByteArrayOutputStream()
        channel.outputStream = output
        channel.connect(CALL_TIMEOUT_MS)
        val deadline = System.currentTimeMillis() + CALL_TIMEOUT_MS
        while (!channel.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(50)
        channel.disconnect()
        return output.toString(Charsets.UTF_8.name()).trim()
    }

    private fun sleep(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (running && System.currentTimeMillis() < until) Thread.sleep(200)
    }

    /** No one to ask: the session already trusted the host key, and a password must be saved for this to work. */
    private object Silent : Prompter {
        override fun yesNo(title: String, message: String) = false
        override fun secret(title: String, message: String): String? = null
        override fun status(text: String) {}
        override fun connected(route: Route) {}
    }

    companion object {
        private const val POLL_MS = 4_000L
        private const val LOOK_AGAIN_MS = 20_000L
        private const val RETRY_MS = 5_000L
        private const val MAX_RETRY_MS = 60_000L
        private const val CALL_TIMEOUT_MS = 5_000
    }
}
