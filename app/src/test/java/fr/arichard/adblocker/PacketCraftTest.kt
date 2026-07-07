package fr.arichard.adblocker

import fr.arichard.adblocker.core.BlocklistManager
import fr.arichard.adblocker.vpn.PacketCraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class PacketCraftTest {

    // ------------------------------------------------------------ helpers

    /** Independent RFC 1071 checksum verifier (different implementation on purpose). */
    private fun internetSum(vararg parts: ByteArray): Int {
        val all = ByteArrayOutputStream()
        parts.forEach { all.write(it) }
        var data = all.toByteArray()
        if (data.size % 2 == 1) data += 0
        val bb = ByteBuffer.wrap(data)
        var sum = 0L
        while (bb.hasRemaining()) sum += bb.short.toLong() and 0xFFFF
        while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.toInt()
    }

    private fun dnsQuery(domain: String, qtype: Int, id: Int = 0x1234, withEdns: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id ushr 8); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00)                    // RD=1
        out.write(0); out.write(1)                          // QDCOUNT
        out.write(0); out.write(0)
        out.write(0); out.write(0)
        out.write(0); out.write(if (withEdns) 1 else 0)     // ARCOUNT
        for (label in domain.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(qtype ushr 8); out.write(qtype and 0xFF)
        out.write(0); out.write(1)                          // class IN
        if (withEdns) {
            out.write(0)                                    // root name
            out.write(0); out.write(41)                     // OPT
            out.write(0x10); out.write(0)                   // UDP size 4096
            repeat(4) { out.write(0) }                      // extended rcode/flags
            out.write(0); out.write(0)                      // rdlen 0
        }
        return out.toByteArray()
    }

    private val clientIp = byteArrayOf(10, 111, 222.toByte(), 1)
    private val serverIp = byteArrayOf(10, 111, 222.toByte(), 2)

    private fun ipUdpDnsPacket(dns: ByteArray, srcPort: Int = 41000): ByteArray =
        PacketCraft.buildUdpPacket(clientIp, srcPort, serverIp, 53, dns, dns.size)

    // ------------------------------------------------------------ DNS parsing

    @Test
    fun extractsQueryDomain() {
        val dns = dnsQuery("ads.example.com", PacketCraft.QTYPE_A)
        assertEquals("ads.example.com", PacketCraft.extractQueryDomain(dns, 0, dns.size))
    }

    @Test
    fun extractsQueryDomainWithEdns() {
        val dns = dnsQuery("tracker.evil-ads.net", PacketCraft.QTYPE_AAAA, withEdns = true)
        assertEquals("tracker.evil-ads.net", PacketCraft.extractQueryDomain(dns, 0, dns.size))
    }

    @Test
    fun rejectsTruncatedQuery() {
        val dns = dnsQuery("ads.example.com", PacketCraft.QTYPE_A)
        assertNull(PacketCraft.extractQueryDomain(dns, 0, 14))
    }

    // ------------------------------------------------------------ blocked responses

    @Test
    fun blockedAResponseIsZeroAddress() {
        val dns = dnsQuery("ads.example.com", PacketCraft.QTYPE_A, id = 0xBEEF)
        val resp = PacketCraft.buildBlockedDnsResponse(dns, 0, dns.size)!!

        assertEquals(0xBEEF, PacketCraft.u16(resp, 0))                    // ID preserved
        assertTrue((resp[2].toInt() and 0x80) != 0)                       // QR = response
        assertEquals(0, resp[3].toInt() and 0x0F)                         // RCODE 0
        assertEquals(1, PacketCraft.u16(resp, 4))                         // QDCOUNT
        assertEquals(1, PacketCraft.u16(resp, 6))                         // ANCOUNT
        assertEquals(0, PacketCraft.u16(resp, 10))                        // ARCOUNT (EDNS stripped)

        val ans = resp.size - 16                                          // answer start
        assertEquals(0xC00C, PacketCraft.u16(resp, ans))                  // name pointer
        assertEquals(PacketCraft.QTYPE_A, PacketCraft.u16(resp, ans + 2))
        assertEquals(4, PacketCraft.u16(resp, ans + 10))                  // RDLENGTH
        for (i in 0 until 4) assertEquals(0, resp[ans + 12 + i].toInt())  // 0.0.0.0
    }

    @Test
    fun blockedAaaaResponseHas16ZeroBytes() {
        val dns = dnsQuery("ads.example.com", PacketCraft.QTYPE_AAAA)
        val resp = PacketCraft.buildBlockedDnsResponse(dns, 0, dns.size)!!
        assertEquals(1, PacketCraft.u16(resp, 6))
        val ans = resp.size - 28
        assertEquals(16, PacketCraft.u16(resp, ans + 10))
        for (i in 0 until 16) assertEquals(0, resp[ans + 12 + i].toInt())
    }

    @Test
    fun blockedOtherTypesGetEmptyNoError() {
        val dns = dnsQuery("ads.example.com", 16 /* TXT */)
        val resp = PacketCraft.buildBlockedDnsResponse(dns, 0, dns.size)!!
        assertEquals(0, PacketCraft.u16(resp, 6))                         // no answers
        assertEquals(0, resp[3].toInt() and 0x0F)                         // NOERROR
    }

    // ------------------------------------------------------------ IP/UDP building

    @Test
    fun udpPacketHeadersAndChecksumsAreValid() {
        val dns = dnsQuery("ads.example.com", PacketCraft.QTYPE_A)
        val p = ipUdpDnsPacket(dns, srcPort = 55555)

        assertEquals(0x45, p[0].toInt() and 0xFF)
        assertEquals(p.size, PacketCraft.u16(p, 2))                       // total length
        assertEquals(17, p[9].toInt())                                    // UDP
        // addresses: src = client, dst = server
        assertEquals(clientIp.toList(), p.copyOfRange(12, 16).toList())
        assertEquals(serverIp.toList(), p.copyOfRange(16, 20).toList())
        assertEquals(55555, PacketCraft.u16(p, 20))
        assertEquals(53, PacketCraft.u16(p, 22))
        assertEquals(8 + dns.size, PacketCraft.u16(p, 24))

        // IP header checksum: folded sum over the header must be 0xFFFF
        assertEquals(0xFFFF, internetSum(p.copyOfRange(0, 20)))

        // UDP checksum over pseudo header + segment must be 0xFFFF
        val udpLen = p.size - 20
        val pseudo = ByteArray(12)
        System.arraycopy(p, 12, pseudo, 0, 8)
        pseudo[9] = 17
        PacketCraft.put16(pseudo, 10, udpLen)
        assertEquals(0xFFFF, internetSum(pseudo, p.copyOfRange(20, p.size)))
    }

    @Test
    fun oddLengthPayloadChecksumIsValid() {
        val payload = ByteArray(13) { (it * 7).toByte() }
        val p = PacketCraft.buildUdpPacket(clientIp, 1000, serverIp, 53, payload, payload.size)
        val pseudo = ByteArray(12)
        System.arraycopy(p, 12, pseudo, 0, 8)
        pseudo[9] = 17
        PacketCraft.put16(pseudo, 10, p.size - 20)
        assertEquals(0xFFFF, internetSum(pseudo, p.copyOfRange(20, p.size)))
    }

    // ------------------------------------------------------------ TCP RST

    @Test
    fun synGetsRstAckWithCorrectAckNumber() {
        // hand-build a SYN to serverIp:53
        val tcp = ByteArray(20)
        PacketCraft.put16(tcp, 0, 44444)
        PacketCraft.put16(tcp, 2, 53)
        PacketCraft.put32(tcp, 4, 0x01020304L)     // seq
        tcp[12] = (5 shl 4).toByte()
        tcp[13] = 0x02                              // SYN
        val syn = ByteArray(40)
        syn[0] = 0x45
        PacketCraft.put16(syn, 2, 40)
        syn[8] = 64
        syn[9] = 6
        System.arraycopy(clientIp, 0, syn, 12, 4)
        System.arraycopy(serverIp, 0, syn, 16, 4)
        System.arraycopy(tcp, 0, syn, 20, 20)

        val rst = PacketCraft.buildTcpRst(syn, syn.size, 20)!!
        // swapped addressing
        assertEquals(serverIp.toList(), rst.copyOfRange(12, 16).toList())
        assertEquals(clientIp.toList(), rst.copyOfRange(16, 20).toList())
        assertEquals(53, PacketCraft.u16(rst, 20))
        assertEquals(44444, PacketCraft.u16(rst, 22))
        assertEquals(0x14, rst[33].toInt() and 0x3F)                      // RST|ACK
        assertEquals(0x01020305L, PacketCraft.u32(rst, 28))               // ack = seq + 1
        // checksums
        assertEquals(0xFFFF, internetSum(rst.copyOfRange(0, 20)))
        val pseudo = ByteArray(12)
        System.arraycopy(rst, 12, pseudo, 0, 8)
        pseudo[9] = 6
        PacketCraft.put16(pseudo, 10, 20)
        assertEquals(0xFFFF, internetSum(pseudo, rst.copyOfRange(20, rst.size)))
    }

    @Test
    fun incomingRstIsIgnored() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[9] = 6
        packet[32] = (5 shl 4).toByte()
        packet[33] = 0x04 // RST
        assertNull(PacketCraft.buildTcpRst(packet, packet.size, 20))
    }

    // ------------------------------------------------------------ full round trip

    @Test
    fun blockedQueryRoundTrip() {
        val dns = dnsQuery("ads.example.com", PacketCraft.QTYPE_A, id = 0x4242, withEdns = true)
        val query = ipUdpDnsPacket(dns)

        // what PacketProcessor does for a blocked domain:
        val dnsOffset = 28
        val dnsLength = query.size - dnsOffset
        assertEquals("ads.example.com", PacketCraft.extractQueryDomain(query, dnsOffset, dnsLength))
        val respDns = PacketCraft.buildBlockedDnsResponse(query, dnsOffset, dnsLength)!!
        val reply = PacketCraft.buildUdpPacket(serverIp, 53, clientIp, 41000, respDns, respDns.size)

        // reply goes server -> client with a valid IP header
        assertEquals(serverIp.toList(), reply.copyOfRange(12, 16).toList())
        assertEquals(clientIp.toList(), reply.copyOfRange(16, 20).toList())
        assertEquals(0xFFFF, internetSum(reply.copyOfRange(0, 20)))
        assertEquals(0x4242, PacketCraft.u16(reply, 28))                  // DNS ID intact
    }

    // ------------------------------------------------------------ blocklist

    @Test
    fun hostsParsingHandlesAllFormats() {
        val content = """
            # comment line
            0.0.0.0 ads.example.com
            127.0.0.1 tracker.example.net extra.example.net  # trailing comment
            plain-domain.org
            ::1 ip6-localhost
            0.0.0.0 0.0.0.0
        """.trimIndent()
        val set = HashSet<String>()
        BlocklistManager.parseHosts(content.byteInputStream(), set)
        assertEquals(
            setOf("ads.example.com", "tracker.example.net", "extra.example.net", "plain-domain.org"),
            set
        )
    }

    @Test
    fun suffixMatchingAndAllowlist() {
        BlocklistManager.setRulesForTest(
            blockedSet = setOf("doubleclick.net", "ads.host.com"),
            allowedSet = setOf("good.doubleclick.net")
        )
        assertTrue(BlocklistManager.isBlocked("doubleclick.net"))
        assertTrue(BlocklistManager.isBlocked("stats.g.doubleclick.net"))
        assertTrue(BlocklistManager.isBlocked("ads.host.com"))
        assertTrue(BlocklistManager.isBlocked("x.ads.host.com"))
        assertFalse(BlocklistManager.isBlocked("host.com"))               // parent not blocked
        assertFalse(BlocklistManager.isBlocked("example.com"))
        assertFalse(BlocklistManager.isBlocked("good.doubleclick.net"))   // allowlisted
        assertFalse(BlocklistManager.isBlocked("sub.good.doubleclick.net"))
        assertTrue(BlocklistManager.isBlocked("Other.DOUBLECLICK.net"))   // case-insensitive
    }

    @Test
    fun realQueryForAllowedDomainIsNotBlocked() {
        BlocklistManager.setRulesForTest(setOf("ads.example.com"), emptySet())
        val dns = dnsQuery("www.wikipedia.org", PacketCraft.QTYPE_A)
        val domain = PacketCraft.extractQueryDomain(dns, 0, dns.size)
        assertNotNull(domain)
        assertFalse(BlocklistManager.isBlocked(domain!!))
    }
}
