package codes.exterkamp.whorl

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class SshTest {

    @Test
    fun parsesMoshConnectLine() {
        val output = "\r\nMOSH CONNECT 60001 4NeCCgvZFe2RnPgrcU1PQw\r\n\r\nmosh-server (mosh 1.4.0) [build mosh 1.4.0]\r\n"
        val match = MoshTransport.parseMoshConnect(output)!!
        assertEquals("60001", match.groupValues[1])
        assertEquals("4NeCCgvZFe2RnPgrcU1PQw", match.groupValues[2])
        assertNull(MoshTransport.parseMoshConnect("bash: mosh-server: command not found"))
    }

    @Test
    fun generatedKeyRoundTrips() {
        val (private, public) = Ssh.newKey("test@device")
        assertTrue(private.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertTrue(public.startsWith("ssh-ed25519 ") && public.endsWith(" test@device"))
        // What import does with a pasted key must give back the same public key.
        assertEquals(public, Ssh.loadKey(private, null, "test@device").second)
    }

    /**
     * Against a real sshd with mosh installed (docker/test-server). Skipped unless TEST_SSH_HOST is set;
     * see the README for how to run it.
     */
    @Test
    fun keyLoginAndMoshServerHandshake() {
        val host = System.getenv("TEST_SSH_HOST")
        assumeTrue("TEST_SSH_HOST not set", host != null)

        val (private, public) = Ssh.newKey("whorl-test")

        // Install the new key using the test account's password.
        session(host!!) { setPassword("test") }.use { exec(it, "mkdir -p ~/.ssh && echo '$public' >> ~/.ssh/authorized_keys") }

        // Now sign in with only the key, the way a DEVICE_KEY profile does.
        session(host) {
            setConfig("PreferredAuthentications", "publickey")
        }.also { (jsch, _) -> jsch.addIdentity("device", private.toByteArray(), public.toByteArray(), null) }.use {
            val output = exec(it, Profile.DEFAULT_MOSH_SERVER)
            val match = MoshTransport.parseMoshConnect(output)
            assertNotNull("mosh-server output was: $output", match)
            exec(it, "pkill -u test mosh-server || true")
        }
    }

    private fun session(host: String, configure: Session.() -> Unit): Pair<JSch, Session> {
        val jsch = JSch()
        val session = jsch.getSession("test", host, 22)
        session.setConfig("StrictHostKeyChecking", "no")
        session.configure()
        return jsch to session
    }

    private fun <T> Pair<JSch, Session>.use(block: (Session) -> T): T {
        second.connect(10_000)
        try {
            return block(second)
        } finally {
            second.disconnect()
        }
    }

    private fun exec(session: Session, command: String): String {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val out = ByteArrayOutputStream()
        channel.outputStream = out
        channel.setErrStream(out, true)
        channel.connect(10_000)
        while (!channel.isClosed) Thread.sleep(50)
        channel.disconnect()
        return out.toString()
    }
}
