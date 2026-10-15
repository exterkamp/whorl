package codes.exterkamp.pocketshell

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Protocol { SSH, MOSH }

enum class AuthMethod { DEVICE_KEY, PASSWORD }

/** One way to reach a machine: an IP address or DNS name, with its own port if it differs from the machine's. */
data class Address(val host: String, val port: Int = 0) {
    fun portOr(default: Int) = if (port > 0) port else default

    /** What kind of network this address is on, for the session header: "local", "tailscale", or the host itself. */
    val kind: String
        get() {
            val h = host.lowercase().trimEnd('.')
            val v4 = h.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: -1 }?.takeIf { p -> p.all { it in 0..255 } }
            return when {
                h.endsWith(".ts.net") || h.startsWith("fd7a:115c:a1e0:") -> "tailscale"
                v4 != null && v4[0] == 100 && v4[1] in 64..127 -> "tailscale"
                v4 != null && (v4[0] == 10 || v4[0] == 172 && v4[1] in 16..31 || v4[0] == 192 && v4[1] == 168 ||
                    v4[0] == 169 && v4[1] == 254) -> "local"
                h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || '.' !in h && ':' !in h -> "local"
                else -> host
            }
        }

    /** How it's typed in the editor: host, host:port, or [ipv6]:port. */
    override fun toString() = when {
        port <= 0 -> host
        ':' in host -> "[$host]:$port"
        else -> "$host:$port"
    }

    fun toJson(): JSONObject = JSONObject().put("host", host).put("port", port)

    companion object {
        fun fromJson(o: JSONObject) = Address(o.optString("host"), o.optInt("port", 0))

        /** Parse what the editor shows; null if it isn't a usable address. */
        fun parse(text: String): Address? {
            val t = text.trim()
            if (t.isEmpty() || t.any { it.isWhitespace() }) return null
            fun port(p: String) = p.toIntOrNull()?.takeIf { it in 1..65535 }
            Regex("""\[([^\]]+)](?::(\d+))?""").matchEntire(t)?.let { m ->
                val p = m.groupValues[2]
                return Address(m.groupValues[1], if (p.isEmpty()) 0 else port(p) ?: return null)
            }
            // More than one colon is a bare IPv6 address.
            if (t.count { it == ':' } != 1) return Address(t)
            val host = t.substringBefore(':').ifEmpty { return null }
            return Address(host, port(t.substringAfter(':')) ?: return null)
        }
    }
}

data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** Tried in this order, earlier ones preferred; see [Reach]. E.g. the LAN address, then the Tailscale one. */
    val addresses: List<Address> = emptyList(),
    /** For addresses that don't give their own. */
    val port: Int = 22,
    val user: String = "",
    val protocol: Protocol = Protocol.MOSH,
    val auth: AuthMethod = AuthMethod.DEVICE_KEY,
    /** Empty means ask every time. */
    val password: String = "",
    /** Run on the server to start mosh; edit it when mosh-server lives somewhere the default doesn't look. */
    val moshServer: String = DEFAULT_MOSH_SERVER,
    /** Typed into the shell once connected, e.g. "herdr" or "tmux new -A -s main". Empty for none. */
    val startup: String = "",
) {
    val label: String get() = name.ifBlank { "$user@${addresses.firstOrNull()?.host.orEmpty()}" }

    /** Where trusted host keys are filed in known_hosts. One per machine, since every address reaches the same sshd. */
    val hostKeyAlias: String get() = "pocketshell-machine-$id"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("port", port).put("user", user)
        .put("addresses", JSONArray().apply { addresses.forEach { put(it.toJson()) } })
        .put("protocol", protocol.name).put("auth", auth.name).put("password", password)
        .put("moshServer", moshServer).put("startup", startup)

    companion object {
        /**
         * Looks in ~/.local/bin and Homebrew's prefixes before the system PATH. A non-interactive SSH command
         * usually has neither on its PATH, and ~/.local/bin is where a newer mosh-server goes without root:
         * anything older than 1.4.0 mangles 24-bit color (see native/README.md).
         */
        const val DEFAULT_MOSH_SERVER =
            "PATH=\"\$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:\$PATH\" mosh-server new -s -c 256 -l LANG=en_US.UTF-8"

        /** Saved by versions before 0.2.0; moved to the current default on load. */
        private const val OLD_DEFAULT_MOSH_SERVER = "mosh-server new -s -c 256 -l LANG=en_US.UTF-8"

        fun fromJson(o: JSONObject) = Profile(
            id = o.getString("id"),
            name = o.optString("name"),
            addresses = o.optJSONArray("addresses")?.let { a -> (0 until a.length()).map { Address.fromJson(a.getJSONObject(it)) } }
                // Saved before machines had more than one address.
                ?: listOfNotNull(o.optString("host").takeIf { it.isNotBlank() }?.let { Address(it) }),
            port = o.optInt("port", 22),
            user = o.optString("user"),
            protocol = Protocol.valueOf(o.optString("protocol", Protocol.MOSH.name)),
            auth = AuthMethod.valueOf(o.optString("auth", AuthMethod.DEVICE_KEY.name)),
            password = o.optString("password"),
            moshServer = o.optString("moshServer")
                .takeUnless { it.isBlank() || it == OLD_DEFAULT_MOSH_SERVER } ?: DEFAULT_MOSH_SERVER,
            startup = o.optString("startup"),
        )
    }
}

/** Everything the app persists. All of it lives in one encrypted preferences file. */
class Store private constructor(private val prefs: SharedPreferences) {

    fun profiles(): List<Profile> {
        val array = JSONArray(prefs.getString(KEY_PROFILES, "[]"))
        return (0 until array.length()).map { Profile.fromJson(array.getJSONObject(it)) }
    }

    fun profile(id: String): Profile? = profiles().firstOrNull { it.id == id }

    fun save(profile: Profile) {
        val list = profiles().toMutableList()
        val index = list.indexOfFirst { it.id == profile.id }
        if (index >= 0) list[index] = profile else list.add(profile)
        writeProfiles(list)
    }

    fun delete(id: String) = writeProfiles(profiles().filterNot { it.id == id })

    private fun writeProfiles(list: List<Profile>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    /** OpenSSH-format private key used for every DEVICE_KEY profile, or null before one is made. */
    var deviceKey: String?
        get() = prefs.getString(KEY_DEVICE_KEY, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_KEY, value).apply()

    var devicePublicKey: String?
        get() = prefs.getString(KEY_DEVICE_PUBLIC_KEY, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_PUBLIC_KEY, value).apply()

    /** Profiles whose sessions were open, so they can be reconnected after the app is killed. */
    var openSessions: Set<String>
        get() = prefs.getStringSet(KEY_OPEN_SESSIONS, emptySet())!!.toSet()
        set(value) = prefs.edit().putStringSet(KEY_OPEN_SESSIONS, value).apply()

    /** The mosh-server last started for a profile, so a reconnect can stop it instead of orphaning it. */
    fun moshServerPid(profileId: String): Int = prefs.getInt(KEY_MOSH_PID + profileId, 0)

    fun setMoshServerPid(profileId: String, pid: Int) {
        prefs.edit().apply { if (pid > 0) putInt(KEY_MOSH_PID + profileId, pid) else remove(KEY_MOSH_PID + profileId) }.apply()
    }

    var fontSize: Int
        get() = prefs.getInt(KEY_FONT_SIZE, 0)
        set(value) = prefs.edit().putInt(KEY_FONT_SIZE, value).apply()

    companion object {
        private const val KEY_PROFILES = "profiles"
        private const val KEY_DEVICE_KEY = "device_key"
        private const val KEY_DEVICE_PUBLIC_KEY = "device_public_key"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_OPEN_SESSIONS = "open_sessions"
        private const val KEY_MOSH_PID = "mosh_pid:"

        @Volatile private var instance: Store? = null

        fun get(context: Context): Store = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val masterKey = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                val prefs = EncryptedSharedPreferences.create(
                    app, "pocketshell", masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                Store(prefs).also { instance = it }
            }
        }
    }
}
