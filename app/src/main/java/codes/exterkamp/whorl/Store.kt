package codes.exterkamp.whorl

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray

/** Everything the app persists. All of it lives in one encrypted preferences file. */
class Store private constructor(private val prefs: SharedPreferences) : SessionStore {

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
    override var deviceKey: String?
        get() = prefs.getString(KEY_DEVICE_KEY, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_KEY, value).apply()

    override var devicePublicKey: String?
        get() = prefs.getString(KEY_DEVICE_PUBLIC_KEY, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_PUBLIC_KEY, value).apply()

    /** Profiles whose sessions were open, so they can be reconnected after the app is killed. */
    var openSessions: Set<String>
        get() = prefs.getStringSet(KEY_OPEN_SESSIONS, emptySet())!!.toSet()
        set(value) = prefs.edit().putStringSet(KEY_OPEN_SESSIONS, value).apply()

    /** The mosh-server last started for a profile, so a reconnect can stop it instead of orphaning it. */
    override fun moshServerPid(profileId: String): Int = prefs.getInt(KEY_MOSH_PID + profileId, 0)

    override fun setMoshServerPid(profileId: String, pid: Int) {
        prefs.edit().apply { if (pid > 0) putInt(KEY_MOSH_PID + profileId, pid) else remove(KEY_MOSH_PID + profileId) }.apply()
    }

    var fontSize: Int
        get() = prefs.getInt(KEY_FONT_SIZE, 0)
        set(value) = prefs.edit().putInt(KEY_FONT_SIZE, value).apply()

    /** The terminal goes light in light mode too. Off by default: most TUIs (herdr, Claude Code) assume dark. */
    var terminalFollowsSystem: Boolean
        get() = prefs.getBoolean(KEY_TERMINAL_FOLLOWS_SYSTEM, false)
        set(value) = prefs.edit().putBoolean(KEY_TERMINAL_FOLLOWS_SYSTEM, value).apply()

    companion object {
        private const val KEY_PROFILES = "profiles"
        private const val KEY_DEVICE_KEY = "device_key"
        private const val KEY_DEVICE_PUBLIC_KEY = "device_public_key"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_TERMINAL_FOLLOWS_SYSTEM = "terminal_follows_system"
        private const val KEY_OPEN_SESSIONS = "open_sessions"
        private const val KEY_MOSH_PID = "mosh_pid:"

        @Volatile private var instance: Store? = null

        fun get(context: Context): Store = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val masterKey = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                val prefs = EncryptedSharedPreferences.create(
                    app, "whorl", masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                Store(prefs).also { instance = it }
            }
        }
    }
}
