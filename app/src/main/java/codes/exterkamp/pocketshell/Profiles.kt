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

data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
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
    val label: String get() = name.ifBlank { "$user@$host" }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("host", host).put("port", port).put("user", user)
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
            host = o.optString("host"),
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
