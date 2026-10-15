package codes.exterkamp.exterm

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Where a session actually connected: which of the machine's addresses answered, and at what IP. */
data class Route(val address: Address, val ip: String, val port: Int) {
    val via: String get() = address.kind
}

/**
 * Picks which of a machine's addresses to use by connecting to the SSH port on them, the way browsers race IPv4
 * against IPv6 ("happy eyeballs"). Apps can't send ICMP pings without root, and an open SSH port is the thing that
 * matters anyway.
 *
 * Addresses start in the order they're listed, each [HEAD_START_MS] after the one before it, or at once when every
 * earlier one has already failed. The first to connect wins. So a LAN address listed first wins whenever it answers,
 * and one that is out of reach costs only the head start before the next one is tried.
 */
object Reach {
    const val HEAD_START_MS = 300L
    const val CONNECT_TIMEOUT_MS = 5_000
    const val PROBE_TIMEOUT_MS = 3_000

    class Unreachable(val failures: List<Pair<Address, String>>) : IOException(
        failures.joinToString("\r\n") { (address, reason) -> "$address: $reason" }
    )

    /** Connect to whichever address answers first, preferring earlier ones. The caller owns the socket. */
    fun connect(addresses: List<Address>, defaultPort: Int): Pair<Route, Socket> =
        race(addresses, HEAD_START_MS, attempt = { open(it, it.portOr(defaultPort), CONNECT_TIMEOUT_MS) }, discard = { it.second.close() })
            .let { (address, result) -> Route(address, result.first, address.portOr(defaultPort)) to result.second }

    /** Whether this route's SSH port still answers. */
    fun probe(route: Route): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(route.ip, route.port), PROBE_TIMEOUT_MS) }
        true
    } catch (e: IOException) {
        false
    }

    /** Resolve and connect, trying each IP a DNS name resolves to in turn. Returns the IP that answered. */
    private fun open(address: Address, port: Int, timeout: Int): Pair<String, Socket> {
        val ips = try {
            InetAddress.getAllByName(address.host)
        } catch (e: UnknownHostException) {
            throw IOException("can't resolve")
        }
        var last: IOException? = null
        for (ip in ips) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(ip, port), timeout)
                return ip.hostAddress!! to socket
            } catch (e: IOException) {
                socket.close()
                last = e
            }
        }
        throw IOException(describe(last))
    }

    private fun describe(e: IOException?): String = when {
        e is SocketTimeoutException -> "no answer"
        e is java.net.ConnectException && e.message.orEmpty().contains("refused", ignoreCase = true) -> "connection refused"
        e is java.net.ConnectException || e is NoRouteToHostException -> "unreachable"
        else -> e?.message ?: "failed"
    }

    /**
     * The race itself, separate from sockets so it can be tested. [attempt] blocks until it connects or throws;
     * [discard] cleans up a result that finished after the winner.
     */
    fun <T> race(
        candidates: List<Address>,
        headStartMs: Long,
        attempt: (Address) -> T,
        discard: (T) -> Unit,
    ): Pair<Address, T> {
        require(candidates.isNotEmpty())
        val lock = Object()
        val failed = BooleanArray(candidates.size)
        val errors = arrayOfNulls<String>(candidates.size)
        var winner: Pair<Address, T>? = null
        var finished = 0
        val start = System.currentTimeMillis()

        candidates.forEachIndexed { i, address ->
            Thread({
                synchronized(lock) {
                    val startAt = start + i * headStartMs
                    while (winner == null && (0 until i).any { !failed[it] }) {
                        val wait = startAt - System.currentTimeMillis()
                        if (wait <= 0) break
                        lock.wait(wait)
                    }
                    if (winner != null) {
                        finished++
                        lock.notifyAll()
                        return@Thread
                    }
                }
                val result = runCatching { attempt(address) }
                synchronized(lock) {
                    result.onSuccess { if (winner == null) winner = address to it else discard(it) }
                    result.onFailure {
                        failed[i] = true
                        errors[i] = it.message ?: it.toString()
                    }
                    finished++
                    lock.notifyAll()
                }
            }, "Reach-$i").apply { isDaemon = true }.start()
        }

        synchronized(lock) {
            while (winner == null && finished < candidates.size) lock.wait()
            return winner ?: throw Unreachable(candidates.indices.map { candidates[it] to (errors[it] ?: "failed") })
        }
    }
}
