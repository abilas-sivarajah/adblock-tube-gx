package de.abilas.gxtube.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Findet GX-Tube-PCs im WLAN: UDP-Broadcast an Port 8766, jeder PC antwortet direkt. */
object CastDiscovery {

    /** Broadcast-Adressen des aktuellen WLANs/LANs (z. B. 192.168.178.255) plus 255.255.255.255. */
    fun broadcastAddresses(context: Context): List<InetAddress> {
        val out = linkedSetOf<InetAddress>()
        runCatching {
            val cm = context.getSystemService<ConnectivityManager>() ?: return@runCatching
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!local) continue
                val link = cm.getLinkProperties(network) ?: continue
                for (la in link.linkAddresses) {
                    val addr = la.address as? Inet4Address ?: continue
                    broadcastOf(addr, la.prefixLength)?.let { out += it }
                }
            }
        }
        out += InetAddress.getByName("255.255.255.255")
        return out.toList()
    }

    internal fun broadcastOf(address: Inet4Address, prefix: Int): InetAddress? {
        if (prefix !in 8..30) return null
        val ip = address.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
        val mask = -1 shl (32 - prefix)
        val bc = ip or mask.inv()
        return InetAddress.getByAddress(byteArrayOf((bc ushr 24).toByte(), (bc ushr 16).toByte(), (bc ushr 8).toByte(), bc.toByte()))
    }

    /**
     * Schickt die Suchanfrage mehrmals und sammelt Antworten, bis [timeoutMs] um ist.
     * [onFound] wird für jeden neuen PC sofort aufgerufen (Liste füllt sich live).
     */
    suspend fun discover(
        targets: List<InetAddress>,
        timeoutMs: Long = 2500,
        port: Int = CastProtocol.DISCOVERY_PORT,
        onFound: (CastDevice) -> Unit = {},
    ): List<CastDevice> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, CastDevice>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 250
            val packet = CastProtocol.discoverPacket
            val buf = ByteArray(2048)
            val end = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L
            var sends = 0
            while (System.currentTimeMillis() < end) {
                val now = System.currentTimeMillis()
                if (sends < 3 && now >= nextSend) {
                    for (t in targets) runCatching { socket.send(DatagramPacket(packet, packet.size, t, port)) }
                    sends++
                    nextSend = now + 700
                }
                try {
                    val reply = DatagramPacket(buf, buf.size)
                    socket.receive(reply)
                    val host = reply.address.hostAddress ?: continue
                    val device = CastProtocol.parseDiscovery(reply.data, reply.length, host) ?: continue
                    if (found.put(device.id, device) == null) onFound(device)
                } catch (_: SocketTimeoutException) {
                    // weiter warten
                }
            }
        }
        found.values.toList()
    }
}
