package codes.exterkamp.whorl

import com.termux.terminal.TerminalTransport
import java.io.File

/**
 * What the shared connection code needs from the app it runs in (Android or desktop): somewhere to keep keys and
 * host keys, a UI thread, and a way to start the bundled mosh-client.
 */
interface Platform {
    val store: SessionStore

    /** OpenSSH-format known_hosts file for trusted host keys; created if missing. */
    val knownHosts: File

    /** Names this device in the comment of keys it generates: "whorl@<deviceName>". */
    val deviceName: String

    /** Run [block] on the UI thread. */
    fun post(block: () -> Unit)

    /**
     * A terminal transport running the bundled mosh-client in a pty, pointed at a mosh-server listening on
     * [ip]:[port] with session key [key]. It isn't connected yet.
     */
    fun moshClient(ip: String, port: String, key: String): TerminalTransport
}

/** The persistent state connections read and write. */
interface SessionStore {
    /** OpenSSH-format private key used for every DEVICE_KEY profile, or null before one is made. */
    var deviceKey: String?
    var devicePublicKey: String?

    /** The mosh-server last started for a profile, so a reconnect can stop it instead of orphaning it. */
    fun moshServerPid(profileId: String): Int
    fun setMoshServerPid(profileId: String, pid: Int)
}
