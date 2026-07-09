package fr.arichard.adblocker.vpn

/**
 * Pure byte-level packet crafting and parsing: IPv4/UDP/TCP headers, RFC 1071
 * checksums and minimal DNS message handling. No Android dependencies so the
 * whole thing is unit-testable on the JVM.
 */
object PacketCraft {

    const val IP4_HEADER = 20
    const val UDP_HEADER = 8
    const val TCP_HEADER = 20
    const val DNS_HEADER = 12
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17
    const val QTYPE_A = 1
    const val QTYPE_AAAA = 28

    private const val TCP_FLAG_FIN = 0x01
    private const val TCP_FLAG_SYN = 0x02
    private const val TCP_FLAG_RST = 0x04
    private const val TCP_FLAG_ACK = 0x10

    // ------------------------------------------------------------------ DNS

    /** Extracts the query name from a DNS message, or null if it cannot be parsed. */
    fun extractQueryDomain(buf: ByteArray, offset: Int, length: Int): String? {
        if (length < DNS_HEADER + 5) return null
        if (u16(buf, offset + 4) < 1) return null // QDCOUNT
        val end = offset + length
        val sb = StringBuilder(64)
        var pos = offset + DNS_HEADER
        var jumps = 0
        while (true) {
            if (pos >= end) return null
            val len = buf[pos].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 == 0xC0) {
                if (pos + 1 >= end || ++jumps > 5) return null
                pos = offset + (((len and 0x3F) shl 8) or (buf[pos + 1].toInt() and 0xFF))
                continue
            }
            if (len > 63 || pos + 1 + len > end || sb.length + len > 255) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 1..len) sb.append((buf[pos + i].toInt() and 0xFF).toChar())
            pos += len + 1
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /**
     * Builds a DNS response for a blocked query: 0.0.0.0 for A, :: for AAAA,
     * an empty NOERROR answer for anything else. Returns null for queries it
     * cannot safely answer (multi-question, compressed or truncated).
     */
    fun buildBlockedDnsResponse(buf: ByteArray, offset: Int, length: Int): ByteArray? {
        if (length < DNS_HEADER + 5) return null
        if (u16(buf, offset + 4) != 1) return null // exactly one question
        val end = offset + length
        var pos = offset + DNS_HEADER
        while (true) {
            if (pos >= end) return null
            val len = buf[pos].toInt() and 0xFF
            if (len == 0) {
                pos++
                break
            }
            if (len and 0xC0 != 0) return null // compression in a question: bail out
            pos += len + 1
        }
        if (pos + 4 > end) return null
        val qtype = u16(buf, pos)
        pos += 4
        val headAndQuestion = pos - offset

        val rdata = when (qtype) {
            QTYPE_A -> ByteArray(4)      // 0.0.0.0
            QTYPE_AAAA -> ByteArray(16)  // ::
            else -> null
        }
        val answerLen = if (rdata != null) 12 + rdata.size else 0
        val resp = ByteArray(headAndQuestion + answerLen)
        System.arraycopy(buf, offset, resp, 0, headAndQuestion)

        resp[2] = (0x80 or (buf[offset + 2].toInt() and 0x01)).toByte() // QR=1, keep RD
        resp[3] = 0x80.toByte()                                        // RA=1, RCODE=0
        resp[4] = 0; resp[5] = 1                                       // QDCOUNT=1
        resp[6] = 0; resp[7] = if (rdata != null) 1 else 0             // ANCOUNT
        resp[8] = 0; resp[9] = 0                                       // NSCOUNT
        resp[10] = 0; resp[11] = 0                                     // ARCOUNT

        if (rdata != null) {
            var a = headAndQuestion
            resp[a++] = 0xC0.toByte(); resp[a++] = 0x0C                // name: pointer to question
            put16(resp, a, qtype); a += 2
            put16(resp, a, 1); a += 2                                  // class IN
            // Short TTL (60s) so allowlisting a domain takes effect quickly despite
            // client-side DNS caches.
            resp[a++] = 0; resp[a++] = 0; resp[a++] = 0; resp[a++] = 60
            put16(resp, a, rdata.size); a += 2
            System.arraycopy(rdata, 0, resp, a, rdata.size)
        }
        return resp
    }

    // ------------------------------------------------------------------ packet building

    fun buildUdpPacket(
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        payload: ByteArray, payloadLen: Int,
    ): ByteArray {
        val udp = ByteArray(UDP_HEADER + payloadLen)
        put16(udp, 0, srcPort)
        put16(udp, 2, dstPort)
        put16(udp, 4, udp.size)
        System.arraycopy(payload, 0, udp, UDP_HEADER, payloadLen)
        val packet = buildIp4Packet(srcIp, dstIp, PROTO_UDP, udp)
        val sum = pseudoHeaderSum(packet, PROTO_UDP, udp.size) + sum16(packet, IP4_HEADER, udp.size)
        var checksum = finishChecksum(sum)
        if (checksum == 0) checksum = 0xFFFF // per RFC 768, transmitted zero means "no checksum"
        put16(packet, IP4_HEADER + 6, checksum)
        return packet
    }

    /**
     * Builds the RST reply for an incoming TCP segment (RFC 793 reset generation),
     * or null when the segment is itself a RST or too short.
     */
    fun buildTcpRst(packet: ByteArray, length: Int, ihl: Int): ByteArray? {
        if (length < ihl + TCP_HEADER) return null
        val flags = packet[ihl + 13].toInt() and 0xFF
        if (flags and TCP_FLAG_RST != 0) return null

        val srcPort = u16(packet, ihl)
        val dstPort = u16(packet, ihl + 2)
        val seq = u32(packet, ihl + 4)
        val ack = u32(packet, ihl + 8)
        val dataOffset = ((packet[ihl + 12].toInt() ushr 4) and 0x0F) * 4
        var segLen = (length - ihl - dataOffset).coerceAtLeast(0).toLong()
        if (flags and TCP_FLAG_SYN != 0) segLen++
        if (flags and TCP_FLAG_FIN != 0) segLen++

        val clientIp = packet.copyOfRange(12, 16)
        val serverIp = packet.copyOfRange(16, 20)

        val tcp = ByteArray(TCP_HEADER)
        put16(tcp, 0, dstPort)
        put16(tcp, 2, srcPort)
        if (flags and TCP_FLAG_ACK != 0) {
            put32(tcp, 4, ack)              // seq = incoming ack
            tcp[13] = TCP_FLAG_RST.toByte()
        } else {
            put32(tcp, 8, (seq + segLen) and 0xFFFFFFFFL)
            tcp[13] = (TCP_FLAG_RST or TCP_FLAG_ACK).toByte()
        }
        tcp[12] = (5 shl 4).toByte()        // data offset: 5 words

        val out = buildIp4Packet(serverIp, clientIp, PROTO_TCP, tcp)
        val sum = pseudoHeaderSum(out, PROTO_TCP, tcp.size) + sum16(out, IP4_HEADER, tcp.size)
        put16(out, IP4_HEADER + 16, finishChecksum(sum))
        return out
    }

    /** Builds an IPv4 packet around [l4]; the layer-4 checksum is left to the caller. */
    private fun buildIp4Packet(srcIp: ByteArray, dstIp: ByteArray, protocol: Int, l4: ByteArray): ByteArray {
        val total = IP4_HEADER + l4.size
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        put16(p, 6, 0x4000)                 // don't fragment
        p[8] = 64                           // TTL
        p[9] = protocol.toByte()
        System.arraycopy(srcIp, 0, p, 12, 4)
        System.arraycopy(dstIp, 0, p, 16, 4)
        System.arraycopy(l4, 0, p, IP4_HEADER, l4.size)
        put16(p, 10, finishChecksum(sum16(p, 0, IP4_HEADER)))
        return p
    }

    // ------------------------------------------------------------------ checksums & helpers

    private fun pseudoHeaderSum(packet: ByteArray, protocol: Int, l4Len: Int): Long =
        sum16(packet, 12, 8) + protocol + l4Len

    fun sum16(buf: ByteArray, offset: Int, len: Int): Long {
        var sum = 0L
        var i = offset
        var remaining = len
        while (remaining > 1) {
            sum += (((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)).toLong()
            i += 2
            remaining -= 2
        }
        if (remaining == 1) sum += ((buf[i].toInt() and 0xFF) shl 8).toLong()
        return sum
    }

    fun finishChecksum(sumIn: Long): Int {
        var sum = sumIn
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    fun u16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    fun u32(buf: ByteArray, offset: Int): Long =
        (u16(buf, offset).toLong() shl 16) or u16(buf, offset + 2).toLong()

    fun put16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    fun put32(buf: ByteArray, offset: Int, value: Long) {
        put16(buf, offset, ((value ushr 16) and 0xFFFF).toInt())
        put16(buf, offset + 2, (value and 0xFFFF).toInt())
    }
}
