package com.serhanensar.hotspotttl

import java.net.InetAddress

/** TUN arayüzünden okunan bir IPv4/IPv6 paketinin TCP veya UDP başlığı. */
class Packet(
    val v6: Boolean,
    val protocol: Int,
    val src: InetAddress,
    val dst: InetAddress,
    val srcPort: Int,
    val dstPort: Int,
    // TCP alanları (UDP'de kullanılmaz)
    val seq: Long,
    val ack: Long,
    val flags: Int,
    val window: Int,
    val mss: Int,
    // Yük: data[payloadOffset, payloadOffset + payloadLength)
    val data: ByteArray,
    val payloadOffset: Int,
    val payloadLength: Int,
) {
    fun has(flag: Int) = flags and flag != 0

    companion object {
        const val TCP = 6
        const val UDP = 17

        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10

        /** Desteklenmeyen paketler (ICMP, parçalı paket, uzantı başlığı) için null döner. */
        fun parse(buf: ByteArray, len: Int): Packet? {
            if (len < 20) return null
            val version = (buf[0].toInt() and 0xff) ushr 4
            val v6: Boolean
            val protocol: Int
            val ipHeaderLen: Int
            val totalLen: Int
            val src: InetAddress
            val dst: InetAddress
            when (version) {
                4 -> {
                    v6 = false
                    ipHeaderLen = (buf[0].toInt() and 0x0f) * 4
                    totalLen = u16(buf, 2)
                    val fragment = u16(buf, 6) and 0x3fff // MF bayrağı + parça ofseti
                    if (fragment != 0 || totalLen > len || ipHeaderLen < 20) return null
                    protocol = buf[9].toInt() and 0xff
                    src = InetAddress.getByAddress(buf.copyOfRange(12, 16))
                    dst = InetAddress.getByAddress(buf.copyOfRange(16, 20))
                }
                6 -> {
                    if (len < 40) return null
                    v6 = true
                    ipHeaderLen = 40
                    totalLen = 40 + u16(buf, 4)
                    if (totalLen > len) return null
                    protocol = buf[6].toInt() and 0xff
                    src = InetAddress.getByAddress(buf.copyOfRange(8, 24))
                    dst = InetAddress.getByAddress(buf.copyOfRange(24, 40))
                }
                else -> return null
            }

            val t = ipHeaderLen
            when (protocol) {
                TCP -> {
                    if (totalLen < t + 20) return null
                    val dataOffset = ((buf[t + 12].toInt() and 0xff) ushr 4) * 4
                    if (dataOffset < 20 || totalLen < t + dataOffset) return null
                    val flags = buf[t + 13].toInt() and 0xff
                    var mss = 0
                    if (flags and SYN != 0) mss = parseMss(buf, t + 20, t + dataOffset)
                    return Packet(
                        v6, protocol, src, dst,
                        u16(buf, t), u16(buf, t + 2),
                        u32(buf, t + 4), u32(buf, t + 8),
                        flags, u16(buf, t + 14), mss,
                        buf, t + dataOffset, totalLen - t - dataOffset,
                    )
                }
                UDP -> {
                    if (totalLen < t + 8) return null
                    return Packet(
                        v6, protocol, src, dst,
                        u16(buf, t), u16(buf, t + 2),
                        0, 0, 0, 0, 0,
                        buf, t + 8, totalLen - t - 8,
                    )
                }
                else -> return null
            }
        }

        private fun parseMss(buf: ByteArray, start: Int, end: Int): Int {
            var i = start
            while (i < end) {
                when (val kind = buf[i].toInt() and 0xff) {
                    0 -> return 0
                    1 -> i++
                    else -> {
                        if (i + 1 >= end) return 0
                        val optLen = buf[i + 1].toInt() and 0xff
                        if (optLen < 2) return 0
                        if (kind == 2 && optLen == 4 && i + 3 < end) return u16(buf, i + 2)
                        i += optLen
                    }
                }
            }
            return 0
        }

        fun buildTcp(
            src: InetAddress, srcPort: Int, dst: InetAddress, dstPort: Int,
            seq: Long, ack: Long, flags: Int, window: Int, mss: Int = 0,
            payload: ByteArray? = null, off: Int = 0, len: Int = 0,
        ): ByteArray {
            val optLen = if (mss > 0) 4 else 0
            val tcpLen = 20 + optLen + len
            val (out, t) = ipHeader(src, dst, TCP, tcpLen)
            put16(out, t, srcPort)
            put16(out, t + 2, dstPort)
            put32(out, t + 4, seq)
            put32(out, t + 8, ack)
            out[t + 12] = ((20 + optLen) shl 2).toByte()
            out[t + 13] = flags.toByte()
            put16(out, t + 14, window)
            if (mss > 0) {
                out[t + 20] = 2
                out[t + 21] = 4
                put16(out, t + 22, mss)
            }
            if (len > 0) System.arraycopy(payload!!, off, out, t + 20 + optLen, len)
            put16(out, t + 16, transportChecksum(out, src, dst, TCP, t, tcpLen))
            return out
        }

        fun buildUdp(
            src: InetAddress, srcPort: Int, dst: InetAddress, dstPort: Int,
            payload: ByteArray, off: Int, len: Int,
        ): ByteArray {
            val udpLen = 8 + len
            val (out, t) = ipHeader(src, dst, UDP, udpLen)
            put16(out, t, srcPort)
            put16(out, t + 2, dstPort)
            put16(out, t + 4, udpLen)
            System.arraycopy(payload, off, out, t + 8, len)
            var sum = transportChecksum(out, src, dst, UDP, t, udpLen)
            if (sum == 0) sum = 0xffff
            put16(out, t + 6, sum)
            return out
        }

        /** Uygulamaya dönen paketler için IP başlığı. Bu paketler cihazdan çıkmaz, TTL önemsiz. */
        private fun ipHeader(src: InetAddress, dst: InetAddress, proto: Int, payloadLen: Int): Pair<ByteArray, Int> {
            val s = src.address
            val d = dst.address
            return if (s.size == 4) {
                val out = ByteArray(20 + payloadLen)
                out[0] = 0x45
                put16(out, 2, 20 + payloadLen)
                out[6] = 0x40 // Don't Fragment
                out[8] = 64
                out[9] = proto.toByte()
                System.arraycopy(s, 0, out, 12, 4)
                System.arraycopy(d, 0, out, 16, 4)
                put16(out, 10, finish(sum(out, 0, 20, 0)))
                out to 20
            } else {
                val out = ByteArray(40 + payloadLen)
                out[0] = 0x60
                put16(out, 4, payloadLen)
                out[6] = proto.toByte()
                out[7] = 64
                System.arraycopy(s, 0, out, 8, 16)
                System.arraycopy(d, 0, out, 24, 16)
                out to 40
            }
        }

        private fun transportChecksum(
            buf: ByteArray, src: InetAddress, dst: InetAddress, proto: Int, off: Int, len: Int,
        ): Int {
            val s = src.address
            val d = dst.address
            var acc = sum(s, 0, s.size, 0L)
            acc = sum(d, 0, d.size, acc)
            acc += proto + len
            acc = sum(buf, off, len, acc)
            return finish(acc)
        }

        private fun sum(buf: ByteArray, off: Int, len: Int, start: Long): Long {
            var acc = start
            var i = off
            val end = off + len
            while (i + 1 < end) {
                acc += ((buf[i].toInt() and 0xff) shl 8) or (buf[i + 1].toInt() and 0xff)
                i += 2
            }
            if (i < end) acc += (buf[i].toInt() and 0xff) shl 8
            return acc
        }

        private fun finish(acc: Long): Int {
            var a = acc
            while (a ushr 16 != 0L) a = (a and 0xffff) + (a ushr 16)
            return (a.inv() and 0xffff).toInt()
        }

        fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

        fun u32(b: ByteArray, i: Int): Long =
            ((u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()) and 0xffffffffL

        private fun put16(b: ByteArray, i: Int, v: Int) {
            b[i] = (v ushr 8).toByte()
            b[i + 1] = v.toByte()
        }

        private fun put32(b: ByteArray, i: Int, v: Long) {
            put16(b, i, (v ushr 16).toInt() and 0xffff)
            put16(b, i + 2, v.toInt() and 0xffff)
        }
    }
}
