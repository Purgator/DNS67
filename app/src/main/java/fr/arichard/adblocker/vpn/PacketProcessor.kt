package fr.arichard.adblocker.vpn

import android.net.VpnService
import android.util.Log
import fr.arichard.adblocker.core.BlocklistManager
import fr.arichard.adblocker.core.Prefs
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Handles raw IPv4 packets read from the TUN device.
 *
 * Only DNS traffic ever reaches the TUN (the VPN routes nothing but the virtual DNS
 * server address). UDP port 53 packets are parsed; queries for blocked domains are
 * answered locally with 0.0.0.0 / ::, everything else is forwarded to the real
 * upstream resolver through a protected socket. TCP packets get a RST so clients
 * fail fast instead of hanging.
 */
class PacketProcessor(
    private val vpnService: VpnService,
    private val tunOutput: FileOutputStream,
    private val prefs: Prefs,
) {

    private val executor = ThreadPoolExecutor(
        1, 32, 30L, TimeUnit.SECONDS, SynchronousQueue(),
        ThreadPoolExecutor.DiscardPolicy() // under a burst, dropped queries are simply retried by clients
    )

    @Volatile private var cachedUpstreamSpec: String? = null
    @Volatile private var cachedUpstream: InetAddress? = null
    @Volatile private var cachedUpstream2Spec: String? = null
    @Volatile private var cachedUpstream2: InetAddress? = null

    fun close() {
        executor.shutdownNow()
    }

    fun handlePacket(packet: ByteArray, length: Int) {
        if (length < PacketCraft.IP4_HEADER) return
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < PacketCraft.IP4_HEADER || length < ihl) return

        when (packet[9].toInt() and 0xFF) {
            PacketCraft.PROTO_UDP -> handleUdp(packet, length, ihl)
            PacketCraft.PROTO_TCP -> PacketCraft.buildTcpRst(packet, length, ihl)?.let { writeToTun(it) }
        }
    }

    private fun handleUdp(packet: ByteArray, length: Int, ihl: Int) {
        if (length < ihl + PacketCraft.UDP_HEADER) return
        val dstPort = PacketCraft.u16(packet, ihl + 2)
        if (dstPort != DNS_PORT) return

        val srcPort = PacketCraft.u16(packet, ihl)
        val dnsOffset = ihl + PacketCraft.UDP_HEADER
        val dnsLength = minOf(length, ihl + PacketCraft.u16(packet, ihl + 4)) - dnsOffset
        if (dnsLength < PacketCraft.DNS_HEADER) return

        val clientIp = packet.copyOfRange(12, 16)
        val serverIp = packet.copyOfRange(16, 20)

        val domain = PacketCraft.extractQueryDomain(packet, dnsOffset, dnsLength)
        AdBlockVpnService.queriesTotal.incrementAndGet()

        if (domain != null && BlocklistManager.isBlocked(domain)) {
            AdBlockVpnService.queriesBlocked.incrementAndGet()
            BlocklistManager.recordBlocked(domain)
            val response = PacketCraft.buildBlockedDnsResponse(packet, dnsOffset, dnsLength)
            if (response != null) {
                writeToTun(
                    PacketCraft.buildUdpPacket(serverIp, DNS_PORT, clientIp, srcPort, response, response.size)
                )
            }
            // If no response could be synthesized (malformed query), drop rather than leak.
            return
        }

        val query = packet.copyOfRange(dnsOffset, dnsOffset + dnsLength)
        forward(query, clientIp, srcPort, serverIp)
    }

    private fun forward(query: ByteArray, clientIp: ByteArray, clientPort: Int, serverIp: ByteArray) {
        executor.execute {
            try {
                DatagramSocket().use { socket ->
                    vpnService.protect(socket)
                    val primary = resolveUpstream(prefs.upstreamDns, Prefs.DEFAULT_UPSTREAM, true)
                    val secondary = resolveUpstream(prefs.upstreamDns2, Prefs.DEFAULT_UPSTREAM2, false)

                    val sentPrimary = trySend(socket, query, primary)
                    if (!sentPrimary && !trySend(socket, query, secondary)) return@execute

                    val buf = ByteArray(RESPONSE_BUFFER)
                    val response = DatagramPacket(buf, buf.size)
                    try {
                        socket.soTimeout = PRIMARY_TIMEOUT_MS
                        socket.receive(response)
                    } catch (e: SocketTimeoutException) {
                        // Primary silent: fall back to the secondary resolver.
                        if (!sentPrimary || !trySend(socket, query, secondary)) return@execute
                        socket.soTimeout = SECONDARY_TIMEOUT_MS
                        socket.receive(response)
                    }

                    val len = response.length
                    if (len < PacketCraft.DNS_HEADER || len > MAX_TUN_PAYLOAD) return@execute
                    writeToTun(PacketCraft.buildUdpPacket(serverIp, DNS_PORT, clientIp, clientPort, buf, len))
                }
            } catch (e: Exception) {
                // Timeouts and transient network errors: the client will retry on its own.
                Log.d(TAG, "DNS forward failed: ${e.message}")
            }
        }
    }

    private fun trySend(socket: DatagramSocket, query: ByteArray, upstream: InetAddress): Boolean =
        try {
            socket.send(DatagramPacket(query, query.size, upstream, DNS_PORT))
            true
        } catch (e: Exception) {
            Log.d(TAG, "send to $upstream failed: ${e.message}")
            false
        }

    private fun resolveUpstream(spec: String, fallback: String, primary: Boolean): InetAddress {
        val cached = if (primary) cachedUpstream else cachedUpstream2
        val cachedSpec = if (primary) cachedUpstreamSpec else cachedUpstream2Spec
        if (cached != null && cachedSpec == spec) return cached

        val address = parseIpLiteral(spec) ?: parseIpLiteral(fallback)!!
        if (primary) {
            cachedUpstream = address
            cachedUpstreamSpec = spec
        } else {
            cachedUpstream2 = address
            cachedUpstream2Spec = spec
        }
        return address
    }

    /** Accepts only numeric IPv4/IPv6 literals so no hostname lookup can ever happen. */
    private fun parseIpLiteral(spec: String): InetAddress? {
        val s = spec.trim()
        if (s.isEmpty()) return null
        val isLiteral = s.contains(':') || s.all { it.isDigit() || it == '.' }
        if (!isLiteral) return null
        return try {
            InetAddress.getByName(s)
        } catch (e: Exception) {
            null
        }
    }

    private fun writeToTun(packet: ByteArray) {
        try {
            synchronized(tunOutput) { tunOutput.write(packet) }
        } catch (e: Exception) {
            Log.d(TAG, "write to tun failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "PacketProcessor"

        private const val DNS_PORT = 53
        private const val RESPONSE_BUFFER = 8192
        private const val MAX_TUN_PAYLOAD = 32000
        private const val PRIMARY_TIMEOUT_MS = 3000
        private const val SECONDARY_TIMEOUT_MS = 7000
    }
}
