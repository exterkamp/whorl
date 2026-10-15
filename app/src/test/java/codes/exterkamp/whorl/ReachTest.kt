package codes.exterkamp.whorl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.Collections

class ReachTest {
    private val lan = Address("192.168.1.174")
    private val tailscale = Address("100.105.102.51")
    private val dns = Address("devbox.example.com")

    /** Each address connects after the given delay, or fails then if the delay is negative. */
    private fun race(vararg plan: Pair<Address, Long>, discarded: MutableList<Address> = mutableListOf()): Pair<Address, Address> {
        val delays = plan.toMap()
        return Reach.race(
            plan.map { it.first }, 100,
            attempt = { a ->
                val d = delays.getValue(a)
                Thread.sleep(kotlin.math.abs(d))
                if (d < 0) throw IOException("no answer")
                a
            },
            discard = { discarded += it },
        )
    }

    @Test
    fun firstAddressWinsWhenBothAnswer() {
        val discarded = Collections.synchronizedList(mutableListOf<Address>())
        assertEquals(lan, race(lan to 20L, tailscale to 5L, discarded = discarded).first)
    }

    @Test
    fun laterAddressWinsWhenFirstIsSlowerThanItsHeadStart() {
        // The LAN address hangs (out of range); Tailscale starts after the 100 ms head start and wins.
        val started = System.currentTimeMillis()
        assertEquals(tailscale, race(lan to 2_000L, tailscale to 10L).first)
        assertTrue(System.currentTimeMillis() - started < 1_000)
    }

    @Test
    fun aFastFailureStartsTheNextAtOnce() {
        val started = System.currentTimeMillis()
        assertEquals(tailscale, race(lan to -5L, tailscale to 5L, dns to 5L).first)
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 90)
    }

    @Test
    fun allFailingListsEveryReason() {
        val e = runCatching { race(lan to -5L, tailscale to -10L) }.exceptionOrNull()
        assertTrue(e is Reach.Unreachable)
        assertEquals(listOf(lan, tailscale), (e as Reach.Unreachable).failures.map { it.first })
    }

    @Test
    fun aLateSecondConnectionIsDiscarded() {
        val discarded = Collections.synchronizedList(mutableListOf<Address>())
        assertEquals(lan, race(lan to 0L, tailscale to 0L, discarded = discarded).first)
        Thread.sleep(200)
        assertTrue(discarded.isEmpty() || discarded == listOf(tailscale))
    }

    @Test
    fun realSocketsSkipARefusedPort() {
        ServerSocket(0).use { open ->
            val closedPort = ServerSocket(0).use { it.localPort }
            val (route, socket) = Reach.connect(
                listOf(Address("127.0.0.1", closedPort), Address("127.0.0.1", open.localPort)), 22,
            )
            socket.close()
            assertEquals(open.localPort, route.port)
            assertEquals("127.0.0.1", route.ip)
            assertTrue(Reach.probe(route))
        }
    }

    /** Against docker/test-server, with an unroutable documentation address listed first. */
    @Test
    fun realRaceAgainstTestServer() {
        val host = System.getenv("TEST_SSH_HOST")
        assumeTrue("TEST_SSH_HOST not set", host != null)
        val (route, socket) = Reach.connect(listOf(Address("192.0.2.1"), Address(host!!)), 22)
        socket.close()
        assertEquals(host, route.ip)
    }

    @Test
    fun parsesAddresses() {
        assertEquals(Address("host.lan"), Address.parse(" host.lan "))
        assertEquals(Address("10.0.0.2", 2222), Address.parse("10.0.0.2:2222"))
        assertEquals(Address("fe80::1"), Address.parse("fe80::1"))
        assertEquals(Address("fe80::1", 22), Address.parse("[fe80::1]:22"))
        assertNull(Address.parse("host:notaport"))
        assertNull(Address.parse("two words"))
        assertNull(Address.parse(":22"))
        assertEquals("[fe80::1]:22", Address("fe80::1", 22).toString())
    }

    @Test
    fun namesTheKindOfNetwork() {
        assertEquals("local", lan.kind)
        assertEquals("local", Address("10.1.2.3").kind)
        assertEquals("local", Address("devbox").kind)
        assertEquals("tailscale", tailscale.kind)
        assertEquals("tailscale", Address("devbox.tail1234.ts.net").kind)
        assertEquals("devbox.example.com", dns.kind)
        assertEquals("172.32.0.1", Address("172.32.0.1").kind)
    }
}
