package codes.exterkamp.exterm

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Reconnects sessions without being asked, after the phone changes networks or a connection drops.
 *
 * mosh follows the phone between networks but not the server: a session started on a machine's LAN address keeps
 * sending to it after the phone leaves home. So when the network changes, every live session's address is probed,
 * and one that stopped answering is reconnected over whichever address does answer now. A dropped SSH connection
 * is reconnected the same way. Nothing is retried in a loop: an attempt happens on a drop or a network change, and
 * only when some address of the machine is reachable.
 */
object Reconnector {
    /** Networks come up in steps (link, address, VPN); wait for them to settle before probing. */
    private const val SETTLE_MS = 2_000L
    private const val AFTER_DROP_MS = 1_000L

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    private var app: Context? = null
    private val check = Runnable { check() }

    /** Called while any session is live. Main thread. */
    fun watch(context: Context) {
        if (callbacks.isNotEmpty()) return
        app = context.applicationContext
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        fun callback() = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = schedule(SETTLE_MS)
            override fun onLost(network: Network) = schedule(SETTLE_MS)
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = schedule(SETTLE_MS)
        }
        // The default network alone misses Wi-Fi to mobile handovers under a VPN such as Tailscale, which stays the
        // default throughout, so also watch the networks underneath it.
        callbacks += callback().also { connectivity.registerDefaultNetworkCallback(it) }
        callbacks += callback().also {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), it,
            )
        }
    }

    fun unwatch(context: Context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        callbacks.forEach { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callbacks.clear()
    }

    /** A session's connection dropped. Main thread. */
    fun dropped(context: Context) {
        app = context.applicationContext
        schedule(AFTER_DROP_MS)
    }

    // Network callbacks arrive on a binder thread; everything else here runs on the main thread.
    private fun schedule(delay: Long) {
        main.post {
            main.removeCallbacks(check)
            main.postDelayed(check, delay)
        }
    }

    private fun check() {
        val context = app ?: return
        val routed = Sessions.routed()
        val stalled = Sessions.stalled()
        if (routed.isEmpty() && stalled.isEmpty()) return
        worker.execute {
            val moved = routed.filter { (session, route) -> !Reach.probe(route) && reachable(session.profile) }
                .map { it.first to "${it.second.via} stopped answering" }
            val retry = stalled.filter { reachable(it.profile) }.map { it to (it.reason ?: "connection dropped") }
            main.post {
                (moved + retry)
                    .filter { (session, _) -> Sessions.isCurrent(session) && session.replacedBy == null }
                    .forEach { (session, reason) -> Sessions.reconnect(context, session, reason) }
            }
        }
    }

    private fun reachable(profile: Profile): Boolean = try {
        Reach.connect(profile.addresses, profile.port).second.close()
        true
    } catch (e: IOException) {
        false
    }
}
