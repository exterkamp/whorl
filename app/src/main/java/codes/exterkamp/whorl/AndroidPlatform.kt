package codes.exterkamp.whorl

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.termux.terminal.ProcessTransport
import com.termux.terminal.TerminalTransport
import java.io.File

/** The shared connection code's view of the Android app. */
class AndroidPlatform private constructor(private val app: Context) : Platform {
    private val main = Handler(Looper.getMainLooper())

    override val store: SessionStore get() = Store.get(app)

    override val knownHosts: File get() = File(app.filesDir, "known_hosts")

    override val deviceName: String get() = Build.MODEL

    override fun post(block: () -> Unit) {
        main.post(block)
    }

    /** mosh-client ships as a "native library" so Android extracts it somewhere it can be executed. */
    override fun moshClient(ip: String, port: String, key: String): TerminalTransport {
        val executable = File(app.applicationInfo.nativeLibraryDir, "libmoshclient.so").path
        val env = arrayOf(
            "MOSH_KEY=$key",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "HOME=${app.filesDir.path}",
            "TMPDIR=${app.cacheDir.path}",
        )
        return ProcessTransport(executable, app.filesDir.path, arrayOf(executable, ip, port), env)
    }

    companion object {
        @Volatile private var instance: AndroidPlatform? = null

        fun get(context: Context): AndroidPlatform =
            instance ?: synchronized(this) { instance ?: AndroidPlatform(context.applicationContext).also { instance = it } }
    }
}
