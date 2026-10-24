package codes.exterkamp.whorl

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
    val hostKeyAlias: String get() = "whorl-machine-$id"

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
         *
         * MOSH_SERVER_NETWORK_TMOUT makes a server that hasn't heard from its client for a day exit, so one left
         * behind (the app uninstalled, the phone lost) doesn't run forever. A new client can't take over an old
         * server anyway; the app reconnects with a fresh one.
         */
        const val DEFAULT_MOSH_SERVER =
            "PATH=\"\$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:\$PATH\" MOSH_SERVER_NETWORK_TMOUT=86400 " +
                "mosh-server new -s -c 256 -l LANG=en_US.UTF-8"

        /** Earlier defaults, moved to the current one on load. */
        private val OLD_DEFAULT_MOSH_SERVERS = setOf(
            "mosh-server new -s -c 256 -l LANG=en_US.UTF-8",
            "PATH=\"\$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:\$PATH\" mosh-server new -s -c 256 -l LANG=en_US.UTF-8",
        )

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
                .takeUnless { it.isBlank() || it in OLD_DEFAULT_MOSH_SERVERS } ?: DEFAULT_MOSH_SERVER,
            startup = o.optString("startup"),
        )
    }
}
