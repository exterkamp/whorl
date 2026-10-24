package codes.exterkamp.whorl.desktop

import codes.exterkamp.whorl.Address
import codes.exterkamp.whorl.AuthMethod
import codes.exterkamp.whorl.Profile
import codes.exterkamp.whorl.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DesktopStoreTest {
    private val dir = Files.createTempDirectory("whorl-store").toFile()

    @Test
    fun savesAndReloads() {
        val file = File(dir, "whorl.dat")
        val profile = Profile(
            name = "box", addresses = listOf(Address("192.168.1.2"), Address("box.tail.ts.net", 2222)), user = "me",
            protocol = Protocol.SSH, auth = AuthMethod.PASSWORD, password = "s3cret", startup = "herdr",
        )
        DesktopStore(file).apply {
            save(profile)
            deviceKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n"
            devicePublicKey = "ssh-ed25519 AAAA whorl@test"
            setMoshServerPid(profile.id, 4242)
            fontSize = 16
        }
        val again = DesktopStore(file)
        assertEquals(listOf(profile), again.profiles())
        assertEquals("ssh-ed25519 AAAA whorl@test", again.devicePublicKey)
        assertEquals(4242, again.moshServerPid(profile.id))
        assertEquals(16, again.fontSize)
        // The secrets never sit in the file as plain text (DPAPI on Windows), or at least it's private elsewhere.
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        if (com.sun.jna.Platform.isWindows()) assertFalse("password in the clear", raw.contains("s3cret"))
        else assertFalse("readable by others", Files.getPosixFilePermissions(file.toPath()).any { it.name.startsWith("OTHERS") })

        again.delete(profile.id)
        again.setMoshServerPid(profile.id, 0)
        assertEquals(emptyList<Profile>(), DesktopStore(file).profiles())
        assertEquals(0, DesktopStore(file).moshServerPid(profile.id))
    }

    @Test
    fun unreadableFileIsSetAsideNotOverwritten() {
        val file = File(dir, "broken.dat").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val store = DesktopStore(file)
        assertNull(store.deviceKey)
        assertEquals(1, dir.listFiles()!!.count { it.name.startsWith("broken.dat.unreadable-") })
    }
}
