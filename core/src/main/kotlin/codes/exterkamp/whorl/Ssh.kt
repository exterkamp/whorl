package codes.exterkamp.whorl

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.termux.terminal.TerminalTransport
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.Base64
import java.util.concurrent.Executors

/** What a connection needs from whoever is watching it. Calls come from a background thread and may block. */
interface Prompter {
    fun yesNo(title: String, message: String): Boolean

    /** Null if the user cancelled. */
    fun secret(title: String, message: String): String?

    /** Progress text shown in the terminal. */
    fun status(text: String)

    /** The connection is up, over this route. */
    fun connected(route: Route) {}
}

/** Exit status for a connection that dropped, as opposed to a shell that exited. Same as ssh(1) uses. */
const val EXIT_DROPPED = 255

class ConnectException(message: String) : Exception(message)

object Ssh {

    init {
        // Android sees only the Java 8 classes in JSch's multi-release jar, so ed25519 and curve25519 have to come
        // from Bouncy Castle. JSch picks that by itself today; pin it so a JSch upgrade can't silently change it.
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
    }

    private fun jsch(platform: Platform): JSch = JSch().apply {
        setKnownHosts(platform.knownHosts.apply { parentFile?.mkdirs(); if (!exists()) createNewFile() }.path)
    }

    /**
     * Find which of the machine's addresses answers, open an authenticated SSH session over it, and return the
     * session with the route it took, which mosh needs.
     */
    fun open(platform: Platform, profile: Profile, prompter: Prompter): Pair<Session, Route> {
        val store = platform.store
        val jsch = jsch(platform)
        adoptLegacyHostKeys(jsch, profile)

        if (profile.auth == AuthMethod.DEVICE_KEY) {
            val key = store.deviceKey ?: throw ConnectException("No device key yet. Create one from the key icon on the machine list.")
            jsch.addIdentity("device", key.toByteArray(), store.devicePublicKey?.toByteArray(), null)
        }
        if (profile.addresses.isEmpty()) throw ConnectException("${profile.label} has no addresses.")

        // An address that turns out to be some other machine (the same private IP on another network) is dropped,
        // and the rest are raced again.
        val candidates = profile.addresses.toMutableList()
        while (true) {
            prompter.status(
                if (candidates.size == 1) "Connecting to ${profile.user}@${candidates[0]}…"
                else "Trying ${candidates.joinToString(", ")}…"
            )
            val (route, socket) = try {
                Reach.connect(candidates, profile.port)
            } catch (e: Reach.Unreachable) {
                throw ConnectException(
                    if (e.failures.size == 1) "Can't reach ${e.failures[0].first}: ${e.failures[0].second}"
                    else "Can't reach ${profile.label}:\r\n" + e.failures.joinToString("\r\n") { (a, why) -> "  $a: $why" }
                )
            }
            if (profile.addresses.size > 1) prompter.status("Connected via ${route.via} (${route.address})")

            val session = jsch.getSession(profile.user, route.ip, route.port)
            session.setSocketFactory(Preconnected(socket))
            session.setHostKeyAlias(profile.hostKeyAlias)
            session.setConfig("StrictHostKeyChecking", "ask")
            session.setConfig(
                "PreferredAuthentications",
                if (profile.auth == AuthMethod.DEVICE_KEY) "publickey" else "keyboard-interactive,password",
            )
            session.setServerAliveInterval(30_000)
            session.userInfo = PromptingUserInfo(profile, prompter, rejectChangedKey = candidates.size > 1)
            try {
                session.connect(15_000)
                return session to route
            } catch (e: JSchChangedHostKeyException) {
                socket.close()
                if (candidates.size == 1) throw ConnectException("Host key not accepted.")
                prompter.status("${route.address} answered as a different machine; skipping it.")
                candidates.remove(route.address)
            } catch (e: Exception) {
                socket.close()
                val reason = e.message ?: e.toString()
                throw ConnectException(
                    when {
                        reason.contains("Auth fail", ignoreCase = true) && profile.auth == AuthMethod.DEVICE_KEY ->
                            "Authentication failed. Is this device's public key in ~/.ssh/authorized_keys on ${profile.label}?"
                        reason.contains("Auth fail", ignoreCase = true) -> "Authentication failed."
                        reason.contains("reject HostKey", ignoreCase = true) -> "Host key not accepted."
                        else -> reason
                    }
                )
            }
        }
    }

    /**
     * Host keys used to be filed under the host name as typed. Copy any found under one of this machine's
     * addresses to its per-machine alias, so upgrading doesn't ask to trust a known machine again.
     */
    private fun adoptLegacyHostKeys(jsch: JSch, profile: Profile) {
        val repo = jsch.hostKeyRepository
        if (!repo.getHostKey(profile.hostKeyAlias, null).isNullOrEmpty()) return
        for (address in profile.addresses) {
            val port = address.portOr(profile.port)
            val legacy = if (port == 22) address.host else "[${address.host}]:$port"
            val keys = repo.getHostKey(legacy, null)
            if (keys.isNullOrEmpty()) continue
            keys.forEach { repo.add(HostKey(profile.hostKeyAlias, Base64.getDecoder().decode(it.key)), null) }
            return
        }
    }

    /** Hands JSch the socket that won the race instead of letting it dial again. */
    private class Preconnected(private val socket: Socket) : SocketFactory {
        override fun createSocket(host: String, port: Int): Socket = socket
        override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
        override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
    }

    /** Generate a fresh ed25519 key and make it the device key. */
    fun generateDeviceKey(platform: Platform) = storeDeviceKey(platform, newKey(deviceComment(platform)))

    /** Use an existing private key (OpenSSH or PEM) as the device key. */
    fun importDeviceKey(platform: Platform, privateKey: String, passphrase: String?) =
        storeDeviceKey(platform, loadKey(privateKey, passphrase, deviceComment(platform)))

    private fun deviceComment(platform: Platform) = "whorl@${platform.deviceName.replace(' ', '-')}"

    private fun storeDeviceKey(platform: Platform, key: Pair<String, String>) {
        val store = platform.store
        store.deviceKey = key.first
        store.devicePublicKey = key.second
    }

    /** A new ed25519 key as (unencrypted private key, authorized_keys line). */
    fun newKey(comment: String): Pair<String, String> = export(KeyPair.genKeyPair(JSch(), KeyPair.ED25519), comment)

    /** Parse and decrypt a pasted private key into the same shape as [newKey]. */
    fun loadKey(privateKey: String, passphrase: String?, comment: String): Pair<String, String> {
        val pair = try {
            KeyPair.load(JSch(), (privateKey.trim() + "\n").toByteArray(), null)
        } catch (e: Exception) {
            throw ConnectException("That doesn't look like a private key.")
        }
        if (pair.isEncrypted && !pair.decrypt(passphrase.orEmpty())) {
            throw ConnectException(if (passphrase.isNullOrEmpty()) "That key needs its passphrase." else "Wrong passphrase.")
        }
        return export(pair, comment)
    }

    private fun export(pair: KeyPair, comment: String): Pair<String, String> {
        val private = when (pair.keyType) {
            // JSch can read EdDSA keys but only writes the legacy PEM formats, which have no EdDSA encoding.
            KeyPair.ED25519, KeyPair.ED448 -> openSshPrivateKey(pair, comment)
            else -> ByteArrayOutputStream().also { pair.writePrivateKey(it) }.toString()
        }
        val public = ByteArrayOutputStream().also { pair.writePublicKey(it, comment) }.toString().trim()
        pair.dispose()
        return private to public
    }

    /** An unencrypted "openssh-key-v1" file, the format ssh-keygen writes, for an EdDSA key. */
    private fun openSshPrivateKey(pair: KeyPair, comment: String): String {
        // The agent encoding is: string type, string public, string private(+public), string comment.
        val agent = java.nio.ByteBuffer.wrap(pair.forSSHAgent())
        fun next() = ByteArray(agent.int).also { agent.get(it) }
        val type = next()
        val publicKey = next()
        val privateKey = next()

        val out = ByteArrayOutputStream()
        fun u32(v: Int) = out.write(java.nio.ByteBuffer.allocate(4).putInt(v).array())
        fun bytes(b: ByteArray) { u32(b.size); out.write(b) }

        val check = java.security.SecureRandom().nextInt()
        val section = ByteArrayOutputStream().also { s ->
            val body = ByteArrayOutputStream()
            fun bu32(v: Int) = body.write(java.nio.ByteBuffer.allocate(4).putInt(v).array())
            fun bbytes(b: ByteArray) { bu32(b.size); body.write(b) }
            bu32(check); bu32(check)
            bbytes(type); bbytes(publicKey); bbytes(privateKey); bbytes(comment.toByteArray())
            var pad = 1
            while (body.size() % 8 != 0) body.write(pad++)
            s.write(body.toByteArray())
        }.toByteArray()

        out.write("openssh-key-v1\u0000".toByteArray())
        bytes("none".toByteArray())
        bytes("none".toByteArray())
        bytes(ByteArray(0))
        u32(1)
        bytes(pair.publicKeyBlob)
        bytes(section)

        val base64 = java.util.Base64.getEncoder().encodeToString(out.toByteArray())
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n" + base64.chunked(70).joinToString("\n") +
            "\n-----END OPENSSH PRIVATE KEY-----\n"
    }
}

private class PromptingUserInfo(
    private val profile: Profile,
    private val prompter: Prompter,
    /** Refuse a changed host key without asking, because another address may still reach the right machine. */
    private val rejectChangedKey: Boolean,
) : UserInfo, UIKeyboardInteractive {
    private var password: String? = null
    private var triedSaved = false

    override fun promptYesNo(message: String): Boolean {
        if (rejectChangedKey && message.contains("IDENTIFICATION HAS CHANGED", ignoreCase = true)) return false
        // JSch names the host by its known_hosts alias, an internal id; show the machine's name instead.
        return prompter.yesNo("Host key", message.replace(profile.hostKeyAlias, profile.label))
    }

    override fun showMessage(message: String) = prompter.status(message)

    override fun getPassphrase(): String? = null
    override fun promptPassphrase(message: String) = false

    override fun getPassword(): String? = password

    override fun promptPassword(message: String): Boolean {
        password = nextPassword(message) ?: return false
        return true
    }

    override fun promptKeyboardInteractive(
        destination: String, name: String, instruction: String, prompt: Array<String>, echo: BooleanArray,
    ): Array<String>? {
        // The common case is a single "Password:" prompt; anything else (2FA codes) gets asked as-is.
        return Array(prompt.size) { i ->
            if (prompt.size == 1 && prompt[i].contains("password", ignoreCase = true)) {
                nextPassword(prompt[i]) ?: return null
            } else {
                prompter.secret(instruction.ifBlank { destination }, prompt[i]) ?: return null
            }
        }
    }

    private fun nextPassword(message: String): String? {
        if (!triedSaved && profile.password.isNotEmpty()) {
            triedSaved = true
            return profile.password
        }
        return prompter.secret("Password", message)
    }
}

/** Runs the whole session over an SSH shell channel. */
class SshTransport(
    private val platform: Platform,
    private val profile: Profile,
    private val prompter: Prompter,
) : TerminalTransport {
    // JSch writes to the socket on resize and disconnect, which can't happen on the main thread.
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var session: Session? = null
    @Volatile private var channel: ChannelShell? = null
    @Volatile private var closed = false

    override fun connect(columns: Int, rows: Int) {
        val (session, route) = Ssh.open(platform, profile, prompter)
        this.session = session
        val channel = session.openChannel("shell") as ChannelShell
        channel.setPtyType("xterm-256color", columns, rows, 0, 0)
        channel.setEnv("LANG", "en_US.UTF-8")
        channel.setEnv("COLORTERM", "truecolor")
        this.channel = channel
        channel.connect(10_000)
        prompter.connected(route)
        typeStartup(channel.outputStream, profile)
    }

    override fun getInputStream(): InputStream = channel!!.inputStream

    override fun getOutputStream(): OutputStream = channel!!.outputStream

    override fun resize(columns: Int, rows: Int) {
        // Rejected once closed, which is fine: there's nothing left to resize.
        runCatching { io.execute { runCatching { channel?.setPtySize(columns, rows, 0, 0) } } }
    }

    override fun waitFor(): Int {
        val channel = channel ?: return 1
        while (!channel.isClosed && session?.isConnected == true) Thread.sleep(200)
        // No exit status means the connection dropped rather than the shell exiting.
        return when {
            channel.exitStatus >= 0 -> channel.exitStatus
            closed -> 1
            else -> EXIT_DROPPED
        }
    }

    // Called on disconnect and again when the session cleans up.
    override fun close() {
        closed = true
        runCatching {
            io.execute {
                channel?.disconnect()
                session?.disconnect()
            }
        }
        io.shutdown()
    }
}

/**
 * Starts mosh-server over SSH, then hands the terminal to the bundled mosh-client. SSH is only used for the
 * handshake; everything after that is mosh's own UDP protocol.
 */
class MoshTransport(
    private val platform: Platform,
    private val profile: Profile,
    private val prompter: Prompter,
) : TerminalTransport {
    @Volatile private var client: TerminalTransport? = null
    @Volatile private var closed = false

    override fun connect(columns: Int, rows: Int) {
        val (session, route) = Ssh.open(platform, profile, prompter)
        val store = platform.store
        val (port, key) = try {
            stopPreviousServer(session, store.moshServerPid(profile.id))
            prompter.status("Starting mosh-server…")
            startServer(session).also { (_, _, pid) -> store.setMoshServerPid(profile.id, pid) }
                .let { (port, key, _) -> port to key }
        } finally {
            session.disconnect()
        }
        if (closed) throw ConnectException("Cancelled")

        prompter.status("mosh: ${route.ip}:$port\r\n")
        val client = platform.moshClient(route.ip, port, key)
        this.client = client
        client.connect(columns, rows)
        prompter.connected(route)
        typeStartup(client.outputStream, profile)
    }

    /**
     * A mosh session can't be resumed by a new client, so the server left behind when the app was killed is
     * stopped rather than left to linger. Only a process that is still a mosh-server is touched.
     */
    private fun stopPreviousServer(session: Session, pid: Int) {
        if (pid <= 0) return
        runCatching {
            exec(session, "case \"$(ps -o comm= -p $pid)\" in mosh-server*) kill $pid ;; esac")
        }
        platform.store.setMoshServerPid(profile.id, 0)
    }

    private fun exec(session: Session, command: String) {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.connect(10_000)
        val deadline = System.currentTimeMillis() + 5_000
        while (!channel.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(50)
        channel.disconnect()
    }

    /** Returns port, key and the detached server's pid (0 if it didn't say). */
    private fun startServer(session: Session): Triple<String, String, Int> {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(profile.moshServer)
        // mosh-server refuses to start without a UTF-8 locale; ask the server for one.
        channel.setEnv("LANG", "en_US.UTF-8")
        val output = ByteArrayOutputStream()
        channel.outputStream = output
        channel.setErrStream(output, true)
        channel.connect(10_000)
        val deadline = System.currentTimeMillis() + 20_000
        var match: MatchResult? = null
        while (System.currentTimeMillis() < deadline && !closed) {
            match = parseMoshConnect(output.toString())
            if (match != null || channel.isClosed) break
            Thread.sleep(100)
        }
        match = match ?: parseMoshConnect(output.toString())
        // The "detached, pid = N" line follows MOSH CONNECT; give it a moment to arrive.
        val pidDeadline = System.currentTimeMillis() + 2_000
        while (match != null && MOSH_PID.find(output.toString()) == null && !channel.isClosed &&
            System.currentTimeMillis() < pidDeadline) Thread.sleep(50)
        channel.disconnect()
        if (match == null) {
            val text = output.toString().trim().ifEmpty { "(no output)" }
            throw ConnectException("mosh-server didn't start. It said:\r\n$text\r\n\r\nCheck the mosh-server command in this host's settings.")
        }
        val pid = MOSH_PID.find(output.toString())?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return Triple(match.groupValues[1], match.groupValues[2], pid)
    }

    override fun getInputStream(): InputStream = client!!.inputStream

    override fun getOutputStream(): OutputStream = client!!.outputStream

    override fun resize(columns: Int, rows: Int) {
        client?.resize(columns, rows)
    }

    override fun waitFor(): Int = client?.waitFor() ?: 1

    override fun close() {
        closed = true
        client?.close()
    }

    /** Called when the user ends the session: the client tells the server to exit, so forget its pid. */
    fun forgetServer() = platform.store.setMoshServerPid(profile.id, 0)

    companion object {
        private val MOSH_CONNECT = Regex("""MOSH CONNECT (\d+) ([A-Za-z0-9/+]{22})""")
        private val MOSH_PID = Regex("""detached, pid = (\d+)""")

        fun parseMoshConnect(output: String): MatchResult? = MOSH_CONNECT.find(output)
    }
}

/** Type the profile's startup command into a freshly connected shell. */
private fun typeStartup(out: OutputStream, profile: Profile) {
    val command = profile.startup.trim()
    if (command.isEmpty()) return
    out.write((command + "\r").toByteArray())
    out.flush()
}
