package com.serhanensar.hotspotttl

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.*
import android.system.StructTimeval
import java.io.FileDescriptor
import java.net.InetAddress
import kotlin.concurrent.thread

/** UDP akışı (DNS, QUIC): her kaynak/hedef çifti için TTL 65'li bağlı bir soket. */
class UdpSession(
    private val vpn: TtlVpnService,
    val key: FlowKey,
    remote: InetAddress,
    remotePort: Int,
) {
    private val fd: FileDescriptor = TtlSocket.open(remote, SOCK_DGRAM)
    private val idleTimeout = if (remotePort == 53) 20_000L else 120_000L

    @Volatile private var closed = false
    @Volatile private var lastActivity = System.currentTimeMillis()

    init {
        try {
            Os.setsockoptTimeval(fd, SOL_SOCKET, SO_RCVTIMEO, StructTimeval.fromMillis(1000))
            Os.connect(fd, remote, remotePort)
        } catch (e: Exception) {
            Os.close(fd)
            throw e
        }
        thread(name = "udp-$remotePort", isDaemon = true) { readLoop() }
    }

    @Synchronized
    fun send(p: Packet) {
        if (closed) return
        lastActivity = System.currentTimeMillis()
        try {
            Os.write(fd, p.data, p.payloadOffset, p.payloadLength)
            vpn.stats.bytesUp.addAndGet(p.payloadLength.toLong())
        } catch (_: ErrnoException) {
            // ağ geçici olarak yok ya da ICMP hatası: paket düşer, uygulama yeniden dener
        }
    }

    private fun readLoop() {
        val buf = ByteArray(65535)
        try {
            while (!closed) {
                val n = try {
                    Os.read(fd, buf, 0, buf.size)
                } catch (e: ErrnoException) {
                    if (e.errno == EAGAIN || e.errno == EINTR || e.errno == ECONNREFUSED) {
                        if (System.currentTimeMillis() - lastActivity > idleTimeout) break
                        continue
                    }
                    break
                }
                lastActivity = System.currentTimeMillis()
                vpn.stats.bytesDown.addAndGet(n.toLong())
                vpn.writeTun(Packet.buildUdp(key.dst, key.dstPort, key.app, key.appPort, buf, 0, n))
            }
        } finally {
            synchronized(this) {
                closed = true
                try { Os.close(fd) } catch (_: ErrnoException) {}
            }
            vpn.removeUdp(key, this)
        }
    }

    /** Okuyucu en geç SO_RCVTIMEO (1 sn) sonra çıkar ve soketi kapatır. */
    fun close() {
        closed = true
    }
}
