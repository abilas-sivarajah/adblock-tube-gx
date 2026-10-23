package de.abilas.gxtube

import de.abilas.gxtube.cast.CastDiscovery
import de.abilas.gxtube.cast.CastProtocol
import de.abilas.gxtube.data.VideoItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Streaming-Protokoll zu GX Tube Desktop (siehe PROTOCOL.md im Desktop-Repo). */
class CastProtocolTest {

    private val rick = VideoItem(id = "dQw4w9WgXcQ", title = "Never Gonna Give You Up", channelName = "Rick Astley", durationSec = 213)

    private fun obj(text: String) = CastProtocol.json.parseToJsonElement(text).jsonObject

    @Test
    fun commandsHaveTheExpectedShape() {
        val hello = obj(CastProtocol.hello("Pixel 8", "abc", "GX Tube 1.0"))
        assertEquals("hello", hello["type"]!!.jsonPrimitive.content)
        assertEquals(1, hello["protocol"]!!.jsonPrimitive.int)

        val play = obj(CastProtocol.play(rick, listOf(rick, rick.copy(id = "jNQXAC9IVRw")), 5000))
        assertEquals("dQw4w9WgXcQ", play["video"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(213, play["video"]!!.jsonObject["durationSec"]!!.jsonPrimitive.int)
        assertEquals(2, play["queue"]!!.jsonArray.size)
        assertEquals(5000L, play["startMs"]!!.jsonPrimitive.long)

        val single = obj(CastProtocol.play(rick, emptyList(), null))
        assertNull(single["queue"])
        assertNull(single["startMs"])

        assertEquals(100, obj(CastProtocol.volume(250))["value"]!!.jsonPrimitive.int)
        assertEquals("true", obj(CastProtocol.add(rick, next = true))["next"]!!.jsonPrimitive.content)
        assertEquals("pause", obj(CastProtocol.simple("pause"))["type"]!!.jsonPrimitive.content)

        assertNull(obj(CastProtocol.fullscreen(null))["on"])
        assertEquals("true", obj(CastProtocol.fullscreen(true))["on"]!!.jsonPrimitive.content)
        assertEquals(2528732444L, obj(CastProtocol.display(2528732444L))["id"]!!.jsonPrimitive.long)
    }

    @Test
    fun stateAndQueueFromThePcAreRead() {
        val state = CastProtocol.parse(
            """{"type":"state","video":{"id":"dQw4w9WgXcQ","title":"Rick","channelName":"RA","thumbnail":"https://i.ytimg.com/x.jpg","durationSec":213,"isLive":false},
               "status":"playing","positionMs":61234,"durationMs":213040,"bufferedMs":90000,"volume":80,"muted":false,"speed":1.5,
               "isLive":false,"error":null,"segments":[{"startMs":10000,"endMs":25000,"category":"sponsor","label":"Sponsor","uuid":"u1"}],
               "queueIndex":0,"queueLength":3,"hasNext":true,"hasPrevious":false,"autoplay":true,"neuesFeld":42}""",
        ) as CastProtocol.Incoming.State
        val s = state.state
        assertEquals("dQw4w9WgXcQ", s.video!!.id)
        assertTrue(s.isPlaying)
        assertEquals(61234L, s.positionMs)
        assertEquals(1.5f, s.speed)
        assertEquals(1, s.segments.size)
        assertTrue(s.hasNext)
        assertNull("ältere Desktop-Version: kein Vollbild-Knopf", s.fullscreen)

        val withWindow = CastProtocol.parse(
            """{"type":"state","status":"playing","fullscreen":true,"displayId":2528732444,
               "displays":[{"id":2528732444,"name":"DELL U2415","primary":true,"width":1920,"height":1200},{"id":7,"name":"","primary":false}]}""",
        ) as CastProtocol.Incoming.State
        assertEquals(true, withWindow.state.fullscreen)
        assertEquals(2528732444L, withWindow.state.displayId)
        assertEquals(2, withWindow.state.displays.size)
        assertEquals("DELL U2415", withWindow.state.displays[0].name)

        val idle = CastProtocol.parse("""{"type":"state","video":null,"status":"idle"}""") as CastProtocol.Incoming.State
        assertNull(idle.state.video)
        assertFalse(idle.state.playWhenReady)

        val queue = CastProtocol.parse("""{"type":"queue","items":[{"id":"a1234567890"},{"id":"b1234567890","autoplay":true}],"index":1,"autoplay":false}""")
            as CastProtocol.Incoming.Queue
        assertEquals(2, queue.queue.items.size)
        assertTrue(queue.queue.items[1].autoplay)
        assertEquals(1, queue.queue.index)

        val welcome = CastProtocol.parse("""{"type":"welcome","protocol":1,"id":"pc","name":"Wohnzimmer","version":"0.1.3","app":"GX Tube Desktop"}""")
            as CastProtocol.Incoming.Welcome
        assertEquals("Wohnzimmer", welcome.info.name)

        assertEquals(CastProtocol.Incoming.Pong, CastProtocol.parse("""{"type":"pong"}"""))
        assertNull(CastProtocol.parse("""{"type":"unbekannt"}"""))
        assertNull(CastProtocol.parse("kein json"))
    }

    @Test
    fun discoveryReplyIsRead() {
        val reply = """{"type":"device","protocol":1,"app":"GX Tube Desktop","id":"5f0c","name":"Büro","port":8770,"version":"0.1.9"}""".toByteArray()
        val d = CastProtocol.parseDiscovery(reply, reply.size, "192.168.178.23")!!
        assertEquals("Büro", d.name)
        assertEquals("192.168.178.23:8770", d.address)
        val noId = """{"type":"device","name":"x","port":8765}""".toByteArray()
        assertNull(CastProtocol.parseDiscovery(noId, noId.size, "1.2.3.4"))
        val other = """{"type":"discover"}""".toByteArray()
        assertNull(CastProtocol.parseDiscovery(other, other.size, "1.2.3.4"))
    }

    @Test
    fun broadcastAddressOfTheWifi() {
        fun bc(ip: String, prefix: Int) = CastDiscovery.broadcastOf(InetAddress.getByName(ip) as Inet4Address, prefix)?.hostAddress
        assertEquals("192.168.178.255", bc("192.168.178.23", 24))
        assertEquals("10.0.255.255", bc("10.0.3.7", 16))
        assertEquals("172.16.7.255", bc("172.16.4.1", 22))
        assertNull(bc("192.168.1.1", 32))
    }

    /**
     * Zusammenspiel mit einem laufenden GX Tube Desktop (nur wenn GXTUBE_DESKTOP=host:port:suchport gesetzt ist):
     * finden per UDP, verbinden, Video schicken, Lautstärke ändern.
     */
    @Test
    fun talksToARunningDesktop() = runBlocking {
        val target = System.getenv("GXTUBE_DESKTOP")
        assumeTrue(!target.isNullOrBlank())
        val (host, port, discoveryPort) = target!!.split(':')

        val found = CastDiscovery.discover(listOf(InetAddress.getByName(host)), timeoutMs = 1500, port = discoveryPort.toInt())
        println("[Suche] $found")
        assertEquals(1, found.size)
        assertEquals(port.toInt(), found[0].port)

        val inbox = LinkedBlockingQueue<CastProtocol.Incoming>()
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val ws = client.newWebSocket(
            Request.Builder().url("ws://$host:$port${CastProtocol.PATH}").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(CastProtocol.hello("JUnit-Handy", "junit", "GX Tube Test"))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    CastProtocol.parse(text)?.let { inbox.put(it) }
                }
            },
        )
        fun <T> waitFor(what: String, pick: (CastProtocol.Incoming) -> T?): T {
            val end = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < end) {
                val m = inbox.poll(500, TimeUnit.MILLISECONDS) ?: continue
                pick(m)?.let { return it }
            }
            throw AssertionError("Keine Antwort: $what")
        }

        val welcome = waitFor("welcome") { it as? CastProtocol.Incoming.Welcome }
        println("[Verbunden] ${welcome.info}")
        assertEquals(found[0].id, welcome.info.id)

        ws.send(CastProtocol.play(rick, emptyList(), null))
        val loading = waitFor("state mit Video") { m -> (m as? CastProtocol.Incoming.State)?.state?.takeIf { it.video?.id == rick.id } }
        println("[Zustand] ${loading.status} ${loading.video?.title}")
        val queue = waitFor("queue") { m -> (m as? CastProtocol.Incoming.Queue)?.queue?.takeIf { q -> q.items.any { it.id == rick.id } } }
        assertNotNull(queue)

        ws.send(CastProtocol.add(rick.copy(id = "jNQXAC9IVRw", title = "Me at the zoo"), next = false))
        waitFor("queue mit 2") { m -> (m as? CastProtocol.Incoming.Queue)?.queue?.takeIf { it.items.size == 2 } }

        ws.send(CastProtocol.volume(35))
        waitFor("volume 35") { m -> (m as? CastProtocol.Incoming.State)?.state?.takeIf { it.volume == 35 } }

        // Vollbild und Bildschirme (GX Tube Desktop ab 0.1.15)
        ws.send(CastProtocol.fullscreen(true))
        val full = waitFor("Vollbild an") { m -> (m as? CastProtocol.Incoming.State)?.state?.takeIf { it.fullscreen == true } }
        println("[Vollbild] an, Bildschirme: ${full.displays.map { it.name }}, aktuell ${full.displayId}")
        assertTrue(full.displays.isNotEmpty())
        assertEquals(full.displayId, full.displays.first { it.id == full.displayId }.id)
        ws.send(CastProtocol.fullscreen(null))
        waitFor("Vollbild aus (umgeschaltet)") { m -> (m as? CastProtocol.Incoming.State)?.state?.takeIf { it.fullscreen == false } }

        ws.send(CastProtocol.simple("stop"))
        waitFor("idle") { m -> (m as? CastProtocol.Incoming.State)?.state?.takeIf { it.video == null && it.status == "idle" } }
        ws.close(1000, null)
        client.dispatcher.executorService.shutdown()
    }
}
