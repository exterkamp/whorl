package codes.exterkamp.whorl.desktop

import codes.exterkamp.whorl.MoshTransport
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The real thing, minus SSH: start a mosh-server on this machine, then talk to it through the mosh-client the
 * app would use, running in a pty (ConPTY on Windows). On Windows CI this is the bundled Cygwin mosh-client, so
 * it proves the bundle's DLLs and terminfo, and that Cygwin and ConPTY get along.
 *
 * Off unless WHORL_MOSH_E2E is set. WHORL_MOSH_SERVER names the mosh-server (default: on the PATH);
 * WHORL_MOSH_CLIENT the client (default: as the app finds it).
 */
class MoshEndToEndTest {
    @Test
    fun typesThroughMosh() {
        assumeTrue("set WHORL_MOSH_E2E to run", System.getenv("WHORL_MOSH_E2E") != null)
        val server = ProcessBuilder(System.getenv("WHORL_MOSH_SERVER") ?: "mosh-server", "new", "-i", "127.0.0.1", "-p", "60001:60010")
            .redirectErrorStream(true)
            .apply { environment()["LANG"] = "C.UTF-8" }
            .start()
        val greeting = server.inputStream.bufferedReader().readText()
        server.waitFor(10, TimeUnit.SECONDS)
        val connect = MoshTransport.parseMoshConnect(greeting)
        assertNotNull("mosh-server said:\n$greeting", connect)
        val (port, key) = connect!!.groupValues[1] to connect.groupValues[2]

        val home = Files.createTempDirectory("whorl-mosh").toFile()
        val client = DesktopPlatform(DesktopStore(File(home, "store.json"))).moshClient("127.0.0.1", port, key)
        client.connect(100, 30)
        val output = ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                val buffer = ByteArray(8192)
                while (true) {
                    val n = client.inputStream.read(buffer)
                    if (n < 0) break
                    synchronized(output) { output.write(buffer, 0, n) }
                }
            }
        }.apply { isDaemon = true; start() }

        fun seen() = synchronized(output) { output.toString(Charsets.UTF_8.name()) }
        fun waitFor(text: String, seconds: Int): Boolean {
            val deadline = System.currentTimeMillis() + seconds * 1000L
            while (System.currentTimeMillis() < deadline) {
                if (seen().contains(text)) return true
                Thread.sleep(200)
            }
            return false
        }

        // Wait for the shell, then have it compute something we can only see if the round trip worked.
        Thread.sleep(3000)
        client.outputStream.write("echo whorl-\$((40+2))\r".toByteArray())
        client.outputStream.flush()
        val ok = waitFor("whorl-42", 30)
        // Leave: exit the shell, and the client goes with it.
        client.outputStream.write("exit\r".toByteArray())
        client.outputStream.flush()
        client.waitFor()
        reader.join(2000)
        assertTrue("never saw whorl-42; the terminal showed:\n" + seen().replace("\u001b", "<ESC>"), ok)
    }
}
