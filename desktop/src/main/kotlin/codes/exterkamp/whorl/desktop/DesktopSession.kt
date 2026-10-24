package codes.exterkamp.whorl.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import codes.exterkamp.whorl.EXIT_DROPPED
import codes.exterkamp.whorl.HerdrAgent
import codes.exterkamp.whorl.HerdrLink
import codes.exterkamp.whorl.HerdrProtocol
import codes.exterkamp.whorl.MoshTransport
import codes.exterkamp.whorl.Profile
import codes.exterkamp.whorl.Prompter
import codes.exterkamp.whorl.Protocol
import codes.exterkamp.whorl.Route
import codes.exterkamp.whorl.SshTransport
import com.termux.terminal.ByteQueue
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalEmulatorClient
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalTransport
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/** A question the connection is waiting on the user to answer. */
sealed class PendingPrompt(val title: String, val message: String) {
    class YesNo(title: String, message: String, val answer: CompletableFuture<Boolean>) : PendingPrompt(title, message)
    class Secret(title: String, message: String, val answer: CompletableFuture<String?>) : PendingPrompt(title, message)
}

/**
 * One connection to a machine, shown in a tab: the desktop counterpart of Android's TerminalSession plus
 * ActiveSession. The emulator is only touched on the UI (Swing) thread; I/O happens on background threads.
 */
class DesktopSession(
    val profile: Profile,
    private val platform: DesktopPlatform,
    /** Told about herdr agent events; the app turns them into notifications. */
    private val alerts: (DesktopSession, HerdrAgent, HerdrProtocol.Event) -> Unit = { _, _, _ -> },
) : TerminalOutput(), Prompter, TerminalEmulatorClient {

    enum class State { CONNECTING, CONNECTED, FINISHED }

    var state by mutableStateOf(State.CONNECTING)
        private set
    var exitStatus = 0
        private set

    /** Bumped whenever the screen content changes, so the terminal view redraws. */
    var revision by mutableIntStateOf(0)
        private set

    /** The latest progress line while connecting, for the tab header. */
    var progress by mutableStateOf("connecting")
        private set
    var route by mutableStateOf<Route?>(null)
        private set
    var title by mutableStateOf<String?>(null)
        private set
    var prompt by mutableStateOf<PendingPrompt?>(null)
        private set

    /** herdr's agents on this machine; changes bump [herdrRevision]. */
    var herdrRevision by mutableIntStateOf(0)
        private set
    val herdr: HerdrLink = HerdrLink(platform, profile, object : HerdrLink.Listener {
        override fun agentEvent(agent: HerdrAgent, event: HerdrProtocol.Event) = alerts(this@DesktopSession, agent, event)
        override fun agentUnblocked(paneId: String) {}
        override fun changed() { herdrRevision++ }
    })

    var emulator: TerminalEmulator? = null
        private set

    /** Called on the UI thread for a bell or an OSC notification; set by the app. */
    var onBell: () -> Unit = {}
    var onClipboard: (String) -> Unit = {}

    private val transport: TerminalTransport = when (profile.protocol) {
        Protocol.SSH -> SshTransport(platform, profile, this)
        Protocol.MOSH -> MoshTransport(platform, profile, this)
    }
    private val fromRemote = ByteQueue(256 * 1024)
    private val toRemote = ByteQueue(16 * 1024)
    private val drainScheduled = AtomicBoolean(false)
    private val readBuffer = ByteArray(64 * 1024)

    /** Start with this terminal size; the first call from the terminal view. */
    fun start(columns: Int, rows: Int) {
        if (emulator != null) return
        emulator = TerminalEmulator(this, columns, rows, TRANSCRIPT_ROWS, this)
        Thread({
            try {
                transport.connect(columns, rows)
            } catch (e: Exception) {
                runCatching { transport.close() }
                printLocal("\r\n\u001b[31m${e.message ?: e}\u001b[0m\r\n")
                SwingUtilities.invokeLater { finish(1) }
                return@Thread
            }
            SwingUtilities.invokeLater { state = State.CONNECTED }
            startIo()
        }, "whorl-connect").apply { isDaemon = true }.start()
    }

    private fun startIo() {
        Thread({
            try {
                transport.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0 || !fromRemote.write(buffer, 0, n)) break
                        scheduleDrain()
                    }
                }
            } catch (_: IOException) {
            }
        }, "whorl-read").apply { isDaemon = true }.start()
        Thread({
            val buffer = ByteArray(16 * 1024)
            try {
                transport.outputStream.use { out ->
                    while (true) {
                        val n = toRemote.read(buffer, true)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        out.flush()
                    }
                }
            } catch (_: IOException) {
            }
        }, "whorl-write").apply { isDaemon = true }.start()
        Thread({
            val code = transport.waitFor()
            SwingUtilities.invokeLater { finish(code) }
        }, "whorl-wait").apply { isDaemon = true }.start()
    }

    /** Feed whatever arrived into the emulator, on the UI thread; bursts coalesce into one redraw. */
    private fun scheduleDrain() {
        if (drainScheduled.compareAndSet(false, true)) SwingUtilities.invokeLater { drain() }
    }

    private fun drain() {
        drainScheduled.set(false)
        val emulator = emulator ?: return
        var any = false
        while (true) {
            val n = fromRemote.read(readBuffer, false)
            if (n <= 0) break
            emulator.append(readBuffer, n)
            any = true
        }
        if (any) {
            val t = emulator.title
            if (t != title) title = t
            revision++
        }
    }

    private fun finish(code: Int) {
        if (state == State.FINISHED) return
        exitStatus = code
        state = State.FINISHED
        herdr.stop()
        // Show anything still queued from the remote end before closing the queues (a closed queue reads nothing).
        drain()
        toRemote.close()
        fromRemote.close()
        val message = when (code) {
            0 -> "[session ended]"
            EXIT_DROPPED -> "[connection lost]"
            else -> "[exited with status $code]"
        }
        val note = "\r\n\u001b[2m$message\u001b[0m\r\n".toByteArray(StandardCharsets.UTF_8)
        emulator?.append(note, note.size)
        revision++
    }

    val dropped: Boolean get() = state == State.FINISHED && exitStatus == EXIT_DROPPED

    /** End the session at the user's request. */
    fun close() {
        herdr.stop()
        (transport as? MoshTransport)?.forgetServer()
        runCatching { transport.close() }
    }

    fun resize(columns: Int, rows: Int) {
        val emulator = emulator ?: return start(columns, rows)
        if (columns == emulator.mColumns && rows == emulator.mRows) return
        emulator.resize(columns, rows)
        if (state == State.CONNECTED) transport.resize(columns, rows)
        revision++
    }

    /** Text the user typed or pasted, to the remote end. */
    fun send(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        write(bytes, 0, bytes.size)
    }

    /** Text shown in the terminal that didn't come from the remote end (connection progress). */
    private fun printLocal(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        fromRemote.write(bytes, 0, bytes.size)
        scheduleDrain()
    }

    // TerminalOutput: what the emulator sends towards the remote end, and its notifications.

    override fun write(data: ByteArray, offset: Int, count: Int) {
        if (state != State.FINISHED) toRemote.write(data, offset, count)
    }

    override fun titleChanged(oldTitle: String?, newTitle: String?) {
        title = newTitle
    }

    override fun onCopyTextToClipboard(text: String) = onClipboard(text)
    override fun onPasteTextFromClipboard() {}
    override fun onBell() = onBell.invoke()
    override fun onColorsChanged() { revision++ }

    // TerminalEmulatorClient
    override fun onTerminalCursorStateChange(state: Boolean) { revision++ }
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) = System.err.println("$tag: $message")
    override fun logWarn(tag: String?, message: String?) {}

    // Prompter: called from the connect thread, which waits for the answer.

    override fun yesNo(title: String, message: String): Boolean {
        val answer = CompletableFuture<Boolean>()
        SwingUtilities.invokeLater { prompt = PendingPrompt.YesNo(title, message, answer) }
        return try { answer.get(10, TimeUnit.MINUTES) } catch (_: Exception) { false } finally { clearPrompt() }
    }

    override fun secret(title: String, message: String): String? {
        val answer = CompletableFuture<String?>()
        SwingUtilities.invokeLater { prompt = PendingPrompt.Secret(title, message, answer) }
        return try { answer.get(10, TimeUnit.MINUTES) } catch (_: Exception) { null } finally { clearPrompt() }
    }

    private fun clearPrompt() = SwingUtilities.invokeLater { prompt = null }

    override fun status(text: String) {
        printLocal("\u001b[2m$text\u001b[0m\r\n")
        SwingUtilities.invokeLater { progress = text.trim().removeSuffix("…").lowercase() }
    }

    override fun connected(route: Route) {
        SwingUtilities.invokeLater { this.route = route }
        herdr.start()
    }

    companion object {
        const val TRANSCRIPT_ROWS = 5000
    }
}
