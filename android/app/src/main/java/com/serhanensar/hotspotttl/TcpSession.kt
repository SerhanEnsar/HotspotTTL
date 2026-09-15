package com.serhanensar.hotspotttl

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.SHUT_WR
import java.io.FileDescriptor
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.random.Random

data class FlowKey(val app: InetAddress, val appPort: Int, val dst: InetAddress, val dstPort: Int)

/**
 * Uygulamanın TUN'a yazdığı TCP bağlantısını kullanıcı alanında sonlandırır ve hedefe
 * TTL 65'li gerçek bir soketle yeniden bağlanır.
 *
 *   uygulama ⇄ [TUN: bu sınıf TCP konuşur] ⇄ soket (TTL 65) ⇄ hedef
 *
 * Uygulama tarafı kayıpsız yerel bir bağlantı olduğu için TCP'nin küçük bir alt kümesi
 * yeterli: pencere ölçekleme yok, sıra dışı segmentler atılır, kayıpta go-back-N.
 */
class TcpSession(
    private val vpn: TtlVpnService,
    val key: FlowKey,
    private val remote: InetAddress,
    private val remotePort: Int,
    clientIsn: Long,
    clientMss: Int,
    clientWindow: Int,
) {
    private enum class State { CONNECTING, SYN_ACK_SENT, ESTABLISHED, CLOSED }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    private var state = State.CONNECTING
    private val mss = minOf(if (clientMss > 0) clientMss else 536, if (key.app.address.size == 4) 1460 else 1440)

    // Uygulamadan gelen yön
    private var rcvNxt = (clientIsn + 1) and MASK
    private var appFin = false
    private val writeQueue = LinkedBlockingQueue<ByteArray>()
    private val queuedBytes = AtomicInteger()
    @Volatile private var advertisedWindow = 0

    // Uygulamaya giden yön
    private val isn = Random.nextLong(0, 1L shl 32)
    private var sndUna = isn
    private var sndNxt = isn
    private var sndMax = isn
    private var peerWindow = clientWindow
    private val sendBuf = ByteQueue()
    private var remoteEof = false
    private var finAcked = false

    private var lastProgress = System.currentTimeMillis()
    private var rto = RTO_MIN
    private var synAckTries = 0

    @Volatile private var fd: FileDescriptor? = null

    fun start() {
        thread(name = "tcp-${key.dstPort}", isDaemon = true) { run() }
    }

    private fun run() {
        val sock = try {
            TtlSocket.connectTcp(remote, remotePort)
        } catch (e: Exception) {
            lock.withLock {
                if (state != State.CLOSED) {
                    send(0, Packet.RST or Packet.ACK)
                    closeLocked()
                }
            }
            return
        }

        lock.withLock {
            if (state == State.CLOSED) {
                Os.close(sock)
                return
            }
            fd = sock
            sendSynAck()
        }

        val writer = thread(name = "tcp-w-${key.dstPort}", isDaemon = true) { writeLoop(sock) }
        try {
            readLoop(sock)
        } finally {
            writer.join()
            try { Os.close(sock) } catch (_: Exception) {}
        }
    }

    // ---- hedef → uygulama ----

    private fun readLoop(sock: FileDescriptor) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            lock.withLock {
                while (state == State.SYN_ACK_SENT || (state == State.ESTABLISHED && sendBuf.size >= MAX_SEND_BUF)) {
                    changed.await(500, TimeUnit.MILLISECONDS)
                }
                if (state == State.CLOSED) return
            }
            val n = try {
                Os.read(sock, buf, 0, buf.size)
            } catch (e: ErrnoException) {
                abort()
                return
            }
            lock.withLock {
                if (state == State.CLOSED) return
                if (n <= 0) {
                    remoteEof = true
                    pump(probe = false)
                    return
                }
                sendBuf.append(buf, n)
                pump(probe = false)
            }
            vpn.stats.bytesDown.addAndGet(n.toLong())
        }
    }

    /** Pencerenin izin verdiği kadar gönderilmemiş veriyi uygulamaya yollar, veri bitince FIN. */
    private fun pump(probe: Boolean) {
        val sent = seqDiff(sndNxt, sndUna)
        if (sent > sendBuf.size) return // FIN dahil her şey gönderildi
        val window = if (probe) maxOf(peerWindow, 1) else peerWindow
        var pos = sent
        while (pos < sendBuf.size && pos < window) {
            val n = minOf(mss, sendBuf.size - pos, window - pos)
            val chunk = sendBuf.copy(pos, n)
            send((sndUna + pos) and MASK, Packet.ACK or Packet.PSH, chunk, n)
            pos += n
        }
        sndNxt = (sndUna + pos) and MASK
        if (remoteEof && pos == sendBuf.size) {
            send(sndNxt, Packet.FIN or Packet.ACK)
            sndNxt = (sndNxt + 1) and MASK
        }
        if (seqDiff(sndNxt, sndMax) > 0) sndMax = sndNxt
    }

    // ---- uygulama → hedef ----

    private fun writeLoop(sock: FileDescriptor) {
        try {
            while (true) {
                val data = writeQueue.take()
                if (data === POISON) return
                if (data === HALF_CLOSE) {
                    Os.shutdown(sock, SHUT_WR)
                    continue
                }
                var off = 0
                while (off < data.size) off += Os.write(sock, data, off, data.size - off)
                val queued = queuedBytes.addAndGet(-data.size)
                vpn.stats.bytesUp.addAndGet(data.size.toLong())
                // Pencere daralmıştı ve yeniden açıldıysa uygulamaya haber ver
                if (advertisedWindow < mss && MAX_QUEUE - queued >= 2 * mss) {
                    lock.withLock { if (state == State.ESTABLISHED) send(sndNxt, Packet.ACK) }
                }
            }
        } catch (e: Exception) {
            abort()
        }
    }

    /** TUN okuma iş parçacığından çağrılır. */
    fun onPacket(p: Packet) = lock.withLock {
        if (state == State.CLOSED) return
        if (p.has(Packet.RST)) {
            closeLocked()
            return
        }
        if (p.has(Packet.SYN)) {
            if (state == State.SYN_ACK_SENT) sendSynAck() // uygulama SYN'i tekrarladı
            return
        }
        if (state == State.CONNECTING) return
        if (p.has(Packet.ACK)) onAck(p.ack, p.window)
        if (state != State.ESTABLISHED) return

        var ackNeeded = false
        val len = p.payloadLength
        if (len > 0) {
            val ahead = seqDiff(p.seq, rcvNxt)
            if (ahead > 0) {
                send(sndNxt, Packet.ACK) // sıra dışı: tekrar ACK, uygulama yeniden gönderir
                return
            }
            val skip = -ahead
            if (skip < len && !appFin) {
                val fresh = len - skip
                if (queuedBytes.get() + fresh > MAX_QUEUE) {
                    send(sndNxt, Packet.ACK)
                    return
                }
                writeQueue.put(p.data.copyOfRange(p.payloadOffset + skip, p.payloadOffset + len))
                queuedBytes.addAndGet(fresh)
                rcvNxt = (rcvNxt + fresh) and MASK
            }
            ackNeeded = true
        }
        if (p.has(Packet.FIN)) {
            if (!appFin && seqDiff((p.seq + len) and MASK, rcvNxt) == 0) {
                appFin = true
                rcvNxt = (rcvNxt + 1) and MASK
                writeQueue.put(HALF_CLOSE)
            }
            ackNeeded = true
        }
        if (ackNeeded) send(sndNxt, Packet.ACK)
        if (appFin && finAcked) closeLocked()
    }

    private fun onAck(ack: Long, window: Int) {
        if (state == State.SYN_ACK_SENT) {
            if (ack != ((isn + 1) and MASK)) return
            state = State.ESTABLISHED
            sndUna = ack; sndNxt = ack; sndMax = ack
            peerWindow = window
            lastProgress = System.currentTimeMillis()
            changed.signalAll()
            return
        }
        if (state != State.ESTABLISHED) return
        val acked = seqDiff(ack, sndUna)
        if (acked < 0 || acked > seqDiff(sndMax, sndUna)) return
        if (acked > 0) {
            val data = minOf(acked, sendBuf.size)
            sendBuf.drop(data)
            if (acked > data) finAcked = true
            sndUna = ack
            if (seqDiff(sndNxt, sndUna) < 0) sndNxt = sndUna
            lastProgress = System.currentTimeMillis()
            rto = RTO_MIN
            changed.signalAll()
        }
        peerWindow = window
        pump(probe = false)
    }

    /** Servisin zamanlayıcısı ~250 ms'de bir çağırır: SYN-ACK ve veri yeniden gönderimi. */
    fun tick(now: Long) = lock.withLock {
        when (state) {
            State.SYN_ACK_SENT -> if (now - lastProgress > RTO_MIN) {
                if (synAckTries >= 5) abortLocked() else sendSynAck()
            }
            State.ESTABLISHED -> {
                val outstanding = seqDiff(sndMax, sndUna) > 0 || (sendBuf.size > 0 && peerWindow == 0)
                if (outstanding && now - lastProgress > rto) {
                    sndNxt = sndUna
                    pump(probe = true)
                    lastProgress = now
                    rto = minOf(rto * 2, RTO_MAX)
                } else if (remoteEof && finAcked && now - lastProgress > HALF_OPEN_TIMEOUT) {
                    abortLocked() // hedef kapattı, uygulama kendi tarafını hiç kapatmadı
                }
            }
            else -> {}
        }
    }

    private fun sendSynAck() {
        state = State.SYN_ACK_SENT
        synAckTries++
        lastProgress = System.currentTimeMillis()
        sndNxt = (isn + 1) and MASK
        vpn.writeTun(
            Packet.buildTcp(
                key.dst, key.dstPort, key.app, key.appPort,
                isn, rcvNxt, Packet.SYN or Packet.ACK, window(), mss = mss,
            )
        )
    }

    private fun send(seq: Long, flags: Int, payload: ByteArray? = null, len: Int = 0) {
        vpn.writeTun(
            Packet.buildTcp(
                key.dst, key.dstPort, key.app, key.appPort,
                seq, rcvNxt, flags, window(), payload = payload, len = len,
            )
        )
    }

    private fun window(): Int {
        val w = (MAX_QUEUE - queuedBytes.get()).coerceIn(0, 65535)
        advertisedWindow = w
        return w
    }

    fun abort() = lock.withLock { abortLocked() }

    private fun abortLocked() {
        if (state == State.CLOSED) return
        send(sndNxt, Packet.RST or Packet.ACK)
        closeLocked()
    }

    /** Servis kapanırken: TUN gidiyor, RST göndermeye gerek yok. */
    fun close() = lock.withLock { closeLocked() }

    private fun closeLocked() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        fd?.let { TtlSocket.shutdownQuietly(it) }
        writeQueue.put(POISON)
        changed.signalAll()
        vpn.removeTcp(key, this)
    }

    /** Uygulamaya iletilmeyi ya da onaylanmayı bekleyen baytlar. */
    private class ByteQueue {
        private var data = ByteArray(64 * 1024)
        private var start = 0
        var size = 0
            private set

        fun append(src: ByteArray, len: Int) {
            if (start + size + len > data.size) {
                if (size + len > data.size) {
                    data = data.copyOfRange(start, start + size).copyOf(maxOf(data.size * 2, size + len))
                } else {
                    System.arraycopy(data, start, data, 0, size)
                }
                start = 0
            }
            System.arraycopy(src, 0, data, start + size, len)
            size += len
        }

        fun drop(n: Int) {
            start += n
            size -= n
            if (size == 0) start = 0
        }

        fun copy(offset: Int, len: Int): ByteArray = data.copyOfRange(start + offset, start + offset + len)
    }

    companion object {
        private const val MASK = 0xffffffffL
        private const val MAX_QUEUE = 256 * 1024
        private const val MAX_SEND_BUF = 256 * 1024
        private const val RTO_MIN = 1000L
        private const val RTO_MAX = 8000L
        private const val HALF_OPEN_TIMEOUT = 120_000L
        private val POISON = ByteArray(0)
        private val HALF_CLOSE = ByteArray(0)

        /** a - b, 32 bit sıra numarası aritmetiğiyle işaretli fark. */
        fun seqDiff(a: Long, b: Long): Int = (a - b).toInt()
    }
}
