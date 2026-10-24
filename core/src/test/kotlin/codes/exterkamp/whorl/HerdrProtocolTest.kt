package codes.exterkamp.whorl

import codes.exterkamp.whorl.HerdrAgent.Status
import codes.exterkamp.whorl.HerdrProtocol.Event
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class HerdrProtocolTest {
    // Trimmed from a real `agent.list` answer.
    private val listing = """{"id":"cli:agent:list","result":{"agents":[
        {"agent":"claude","agent_status":"idle","focused":false,"pane_id":"w3:p1","tab_id":"w3:t1",
         "terminal_title":"✳ Port status dashboard for agents","terminal_title_stripped":"Port status dashboard for agents","workspace_id":"w3"},
        {"agent":"claude","agent_status":"working","focused":true,"pane_id":"w6:p1",
         "terminal_title_stripped":"Mosh client for Android","workspace_id":"w6"},
        {"agent":"codex","agent_status":"blocked","completion_seq":128,"focused":false,"pane_id":"wA:p1",
         "terminal_title_stripped":"","workspace_id":"wA"}],"type":"agent_list"}}"""

    private val agents = HerdrProtocol.parseAgents(HerdrProtocol.result(listing))

    @Test fun parsesTheAgentList() {
        assertEquals(listOf("w3:p1", "w6:p1", "wA:p1"), agents.map { it.paneId })
        assertEquals(listOf(Status.IDLE, Status.WORKING, Status.BLOCKED), agents.map { it.status })
        assertEquals("Mosh client for Android", agents[1].label)
        assertEquals("Codex", agents[2].label)
        assertEquals(128L, agents[2].completions)
        assertEquals(true, agents[1].focused)
    }

    @Test fun errorsBecomeExceptions() {
        assertThrows(IOException::class.java) {
            HerdrProtocol.result("""{"id":"2","error":{"code":"invalid_request","message":"missing field"}}""")
        }
    }

    @Test fun requestsAreOneJsonLine() {
        val line = HerdrProtocol.request("whorl:1", "agent.focus", JSONObject().put("target", "w6:p1"))
        assertEquals('\n', line.last())
        val json = JSONObject(line)
        assertEquals("agent.focus", json.getString("method"))
        assertEquals("w6:p1", json.getJSONObject("params").getString("target"))
    }

    @Test fun blockingIsAnEventOnce() {
        val before = agents.map { it.copy(status = Status.WORKING) }
        assertEquals(listOf("wA:p1" to Event.NEEDS_INPUT), HerdrProtocol.events(before, agents).map { it.first.paneId to it.second })
        assertEquals(emptyList<Any>(), HerdrProtocol.events(agents, agents))
    }

    @Test fun aFinishBetweenPollsIsCaughtByItsCount() {
        // Working, then finished and back to idle before the next poll: only the completion count shows it.
        val before = listOf(agents[0].copy(status = Status.WORKING, completions = 4))
        val after = listOf(agents[0].copy(status = Status.IDLE, completions = 5))
        assertEquals(listOf(Event.FINISHED), HerdrProtocol.events(before, after).map { it.second })
    }

    @Test fun newAgentsHaveNothingToCompare() {
        assertEquals(emptyList<Any>(), HerdrProtocol.events(emptyList(), agents))
    }

    @Test fun summaries() {
        assertEquals("3 agents · 1 needs you", HerdrProtocol.summary(agents))
        assertEquals("1 agent · 1 working", HerdrProtocol.summary(listOf(agents[1])))
        assertEquals("1 agent", HerdrProtocol.summary(listOf(agents[0])))
        assertNull(HerdrProtocol.summary(emptyList()))
    }
}
