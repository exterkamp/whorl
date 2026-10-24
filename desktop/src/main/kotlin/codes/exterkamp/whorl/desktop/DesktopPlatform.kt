package codes.exterkamp.whorl.desktop

import codes.exterkamp.whorl.Platform
import codes.exterkamp.whorl.Profile
import codes.exterkamp.whorl.SessionStore
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import com.sun.jna.Platform as Jna
import com.sun.jna.platform.win32.Crypt32Util
import com.termux.terminal.TerminalTransport
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import javax.swing.SwingUtilities

/** Where the desktop app keeps its data: %APPDATA%\Whorl on Windows, ~/.config/whorl elsewhere. */
object DataDir {
    val dir: File by lazy {
        System.getenv("WHORL_HOME")?.let { return@lazy File(it).apply { mkdirs() } }
        val base = if (Jna.isWindows()) System.getenv("APPDATA")?.let(::File) ?: File(System.getProperty("user.home"))
        else System.getenv("XDG_CONFIG_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".config")
        File(base, if (Jna.isWindows()) "Whorl" else "whorl").apply { mkdirs() }
    }
}

/**
 * Everything the desktop app persists, in one file. On Windows it's encrypted with DPAPI, so only this Windows
 * user can read the device key and saved passwords; elsewhere it's a plain file only its owner can read.
 */
class DesktopStore(private val file: File) : SessionStore {
    private var data: JSONObject = load()

    @Synchronized
    fun profiles(): List<Profile> {
        val a = data.optJSONArray("profiles") ?: JSONArray()
        return (0 until a.length()).map { Profile.fromJson(a.getJSONObject(it)) }
    }

    @Synchronized
    fun save(profile: Profile) {
        val list = profiles().toMutableList()
        val i = list.indexOfFirst { it.id == profile.id }
        if (i >= 0) list[i] = profile else list.add(profile)
        data.put("profiles", JSONArray().apply { list.forEach { put(it.toJson()) } })
        write()
    }

    @Synchronized
    fun delete(id: String) {
        data.put("profiles", JSONArray().apply { profiles().filterNot { it.id == id }.forEach { put(it.toJson()) } })
        write()
    }

    override var deviceKey: String?
        @Synchronized get() = data.optString("deviceKey").ifEmpty { null }
        @Synchronized set(value) { data.put("deviceKey", value ?: ""); write() }

    override var devicePublicKey: String?
        @Synchronized get() = data.optString("devicePublicKey").ifEmpty { null }
        @Synchronized set(value) { data.put("devicePublicKey", value ?: ""); write() }

    @Synchronized
    override fun moshServerPid(profileId: String): Int = data.optJSONObject("moshPids")?.optInt(profileId, 0) ?: 0

    @Synchronized
    override fun setMoshServerPid(profileId: String, pid: Int) {
        val pids = data.optJSONObject("moshPids") ?: JSONObject()
        if (pid > 0) pids.put(profileId, pid) else pids.remove(profileId)
        data.put("moshPids", pids)
        write()
    }

    /** Terminal font size in points. */
    var fontSize: Int
        @Synchronized get() = data.optInt("fontSize", 14)
        @Synchronized set(value) { data.put("fontSize", value); write() }

    private fun load(): JSONObject {
        if (!file.exists()) return JSONObject()
        return runCatching {
            val bytes = file.readBytes()
            JSONObject(String(if (Jna.isWindows()) Crypt32Util.cryptUnprotectData(bytes) else bytes, Charsets.UTF_8))
        }.getOrElse {
            // Unreadable (another user's file, or damaged): keep it aside rather than overwrite it.
            file.renameTo(File(file.path + ".unreadable-" + System.currentTimeMillis()))
            JSONObject()
        }
    }

    private fun write() {
        val bytes = data.toString().toByteArray(Charsets.UTF_8)
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(if (Jna.isWindows()) Crypt32Util.cryptProtectData(bytes) else bytes)
        if (!Jna.isWindows()) {
            tmp.setReadable(false, false); tmp.setReadable(true, true)
            tmp.setWritable(false, false); tmp.setWritable(true, true)
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

class DesktopPlatform(val desktopStore: DesktopStore = DesktopStore(File(DataDir.dir, if (Jna.isWindows()) "whorl.dat" else "whorl.json"))) : Platform {
    override val store: SessionStore get() = desktopStore

    override val knownHosts: File get() = File(DataDir.dir, "known_hosts")

    override val deviceName: String =
        System.getenv("COMPUTERNAME") ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("desktop")

    override fun post(block: () -> Unit) = SwingUtilities.invokeLater(block)

    override fun moshClient(ip: String, port: String, key: String): TerminalTransport {
        val env = mapOf(
            "MOSH_KEY" to key,
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            // Cygwin: don't warn about Windows-style paths, and keep its runtime quiet.
            "CYGWIN" to "nodosfilewarning",
        )
        return PtyTransport(arrayOf(MoshClient.executable(), ip, port), env, DataDir.dir.path)
    }
}

/**
 * Finds the mosh-client to run: the one bundled with the installed app, else WHORL_MOSH_CLIENT, else one on the
 * PATH (development on Linux).
 *
 * On Windows it's Cygwin's build. The bundle keeps Cygwin's layout, mosh/bin/ for the program and its DLLs and
 * mosh/usr/share/terminfo/, because Cygwin treats the folder above the one holding cygwin1.dll as its root and
 * looks for the terminal description there.
 */
object MoshClient {
    fun executable(): String {
        System.getenv("WHORL_MOSH_CLIENT")?.let { return it }
        val bundled = System.getProperty("compose.application.resources.dir")?.let { dir ->
            File(dir, if (Jna.isWindows()) "mosh/bin/mosh-client.exe" else "mosh/bin/mosh-client").takeIf { it.isFile }
        }
        return bundled?.path ?: if (Jna.isWindows()) "mosh-client.exe" else "mosh-client"
    }
}

/** A program running in a pseudo-terminal: ConPTY on Windows, a Unix pty elsewhere. */
class PtyTransport(
    private val command: Array<String>,
    private val env: Map<String, String>,
    private val directory: String,
) : TerminalTransport {
    @Volatile private var process: PtyProcess? = null

    override fun connect(columns: Int, rows: Int) {
        process = PtyProcessBuilder(command)
            .setEnvironment(System.getenv() + env)
            .setDirectory(directory)
            .setInitialColumns(columns)
            .setInitialRows(rows)
            .setConsole(false)
            .setUseWinConPty(true)
            .start()
    }

    override fun getInputStream(): InputStream = process!!.inputStream

    override fun getOutputStream(): OutputStream = process!!.outputStream

    override fun resize(columns: Int, rows: Int) {
        runCatching { process?.winSize = WinSize(columns, rows) }
    }

    override fun waitFor(): Int = process?.waitFor() ?: 1

    /** destroy() sends SIGTERM on Unix (mosh-client then tells its server to exit) and ends the process on Windows. */
    override fun close() {
        process?.destroy()
    }
}
