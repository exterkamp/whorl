package codes.exterkamp.conch

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/** The escape codes programs use to ask the terminal for a desktop notification. */
class NotificationCodesTest {
    private val received = mutableListOf<Pair<String?, String>>()

    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {}
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
        override fun onNotification(title: String?, body: String) { received += title to body }
    }

    private val client = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TerminalSessionClient::class.java)) { _, _, _ -> null }
        as TerminalSessionClient

    private fun feed(text: String): List<Pair<String?, String>> {
        val emulator = TerminalEmulator(output, 80, 24, 100, client)
        val bytes = text.toByteArray()
        emulator.append(bytes, bytes.size)
        return received
    }

    @Test fun iterm2() = assertEquals(listOf(null to "build done"), feed("\u001b]9;build done\u0007"))

    @Test fun iterm2WithStringTerminator() = assertEquals(listOf(null to "hi"), feed("\u001b]9;hi\u001b\\"))

    @Test fun conEmuProgressIsNotANotification() = assertEquals(emptyList<Any>(), feed("\u001b]9;4;1;50\u0007"))

    @Test fun rxvt() = assertEquals(listOf("Claude" to "needs your input"), feed("\u001b]777;notify;Claude;needs your input\u0007"))

    @Test fun kitty() = assertEquals(listOf(null to "done"), feed("\u001b]99;;done\u0007"))

    @Test fun textAroundItStillPrints() {
        assertEquals(listOf(null to "x"), feed("before\u001b]9;x\u0007after"))
    }
}
