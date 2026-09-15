package com.serhanensar.hotspotttl

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.service.quicksettings.TileService
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.*
import android.system.StructPollfd
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Yerel VPN: bütün trafik TUN arayüzüne yönlendirilir, hiçbir sunucuya tünellenmez.
 * Her TCP/UDP akışı cihazın içinde sonlandırılıp TTL 65'li soketlerle yeniden açılır.
 */
class TtlVpnService : VpnService() {

    class Stats {
        val bytesUp = AtomicLong()
        val bytesDown = AtomicLong()
    }

    val stats = Stats()

    private var tun: ParcelFileDescriptor? = null
    private val tunWriteLock = Any()
    @Volatile private var running = false
    private var timer: ScheduledExecutorService? = null

    private val tcp = ConcurrentHashMap<FlowKey, TcpSession>()
    private val udp = ConcurrentHashMap<FlowKey, UdpSession>()

    @Volatile private var upstreamDns: InetAddress? = null
    @Volatile private var upstreamDnsAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    override fun onRevoke() = stopVpn()

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn() {
        if (running) return
        val pfd = try {
            Builder()
                .setSession("Hotspot TTL")
                .setMtu(MTU)
                .addAddress(VPN_ADDR4, 24)
                .addAddress(VPN_ADDR6, 64)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(VIRTUAL_DNS)
                .addDisallowedApplication(packageName) // kendi soketlerimiz VPN'e geri dönmesin
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "VPN kurulamadı", e)
            null
        }
        if (pfd == null) {
            lastError = "VPN izni yok ya da kurulamadı"
            stopSelf()
            return
        }

        tun = pfd
        running = true
        lastError = null
        instance = this
        startedAt = System.currentTimeMillis()

        thread(name = "tun-reader", isDaemon = true) { readLoop(pfd) }
        timer = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({
                val now = System.currentTimeMillis()
                tcp.values.forEach { s -> s.tick(now) }
            }, 250, 250, TimeUnit.MILLISECONDS)
        }
        refreshTile()
    }

    private fun stopVpn() {
        if (!running) return
        running = false
        timer?.shutdownNow()
        timer = null
        tcp.values.forEach { it.close() }
        udp.values.forEach { it.close() }
        tcp.clear()
        udp.clear()
        synchronized(tunWriteLock) {
            try { tun?.close() } catch (_: Exception) {}
            tun = null
        }
        if (instance === this) instance = null
        refreshTile()
        stopSelf()
    }

    private fun readLoop(pfd: ParcelFileDescriptor) {
        val fd = pfd.fileDescriptor
        val poll = StructPollfd().apply { this.fd = fd; events = POLLIN.toShort() }
        val buf = ByteArray(MTU + 100)
        while (running) {
            try {
                if (Os.poll(arrayOf(poll), 1000) <= 0) continue
                val n = Os.read(fd, buf, 0, buf.size)
                if (n > 0) handle(buf, n)
            } catch (e: ErrnoException) {
                if (e.errno == EAGAIN || e.errno == EINTR) continue
                if (running) Log.e(TAG, "TUN okuma hatası", e)
                break
            } catch (e: Exception) {
                Log.e(TAG, "Paket işlenemedi", e)
            }
        }
    }

    private fun handle(buf: ByteArray, len: Int) {
        val p = Packet.parse(buf, len) ?: return // ICMP vb. atlanır
        val key = FlowKey(p.src, p.srcPort, p.dst, p.dstPort)
        val (remote, remotePort) = resolveTarget(p.dst, p.dstPort) ?: run {
            if (p.protocol == Packet.TCP) rejectTcp(p)
            return
        }

        when (p.protocol) {
            Packet.TCP -> {
                val existing = tcp[key]
                if (existing != null) {
                    existing.onPacket(p)
                } else if (p.has(Packet.SYN) && !p.has(Packet.ACK)) {
                    val s = TcpSession(this, key, remote, remotePort, p.seq, p.mss, p.window)
                    tcp[key] = s
                    s.start()
                } else if (!p.has(Packet.RST)) {
                    rejectTcp(p)
                }
            }
            Packet.UDP -> {
                val s = udp[key] ?: try {
                    UdpSession(this, key, remote, remotePort).also { udp[key] = it }
                } catch (e: Exception) {
                    return
                }
                s.send(p)
            }
        }
    }

    /** Sanal DNS adresine giden trafik gerçek DNS sunucusuna gider. Diğer VPN adreslerine gidilmez. */
    private fun resolveTarget(dst: InetAddress, port: Int): Pair<InetAddress, Int>? {
        if (dst == virtualDns) return if (port == 53) upstreamDns() to 53 else null
        if (dst == vpnAddr4 || dst == vpnAddr6) return null
        return dst to port
    }

    private fun rejectTcp(p: Packet) {
        val syn = if (p.has(Packet.SYN)) 1 else 0
        val fin = if (p.has(Packet.FIN)) 1 else 0
        val ack = (p.seq + p.payloadLength + syn + fin) and 0xffffffffL
        val seq = if (p.has(Packet.ACK)) p.ack else 0
        writeTun(
            Packet.buildTcp(
                p.dst, p.dstPort, p.src, p.srcPort,
                seq, ack, Packet.RST or Packet.ACK, 0,
            )
        )
    }

    /** Wi-Fi'ın (hotspot'un) DNS sunucusu; bulunamazsa 1.1.1.1. 10 sn önbellek. */
    private fun upstreamDns(): InetAddress {
        val now = System.currentTimeMillis()
        upstreamDns?.let { if (now - upstreamDnsAt < 10_000) return it }
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val servers = cm.allNetworks
            .filter { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@filter false
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            .sortedByDescending { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .flatMap { n -> cm.getLinkProperties(n)?.dnsServers.orEmpty() }
        val dns = servers.firstOrNull { it is Inet4Address } ?: servers.firstOrNull()
            ?: InetAddress.getByName("1.1.1.1")
        upstreamDns = dns
        upstreamDnsAt = now
        return dns
    }

    fun writeTun(packet: ByteArray) {
        synchronized(tunWriteLock) {
            val t = tun ?: return
            try {
                Os.write(t.fileDescriptor, packet, 0, packet.size)
            } catch (_: ErrnoException) {
                // TUN kuyruğu dolu: paket düşer, TCP yeniden gönderir
            }
        }
    }

    fun removeTcp(key: FlowKey, s: TcpSession) { tcp.remove(key, s) }
    fun removeUdp(key: FlowKey, s: UdpSession) { udp.remove(key, s) }

    val tcpCount get() = tcp.size
    val udpCount get() = udp.size
    val currentDns get() = upstreamDns

    private fun refreshTile() {
        TileService.requestListeningState(this, android.content.ComponentName(this, ToggleTileService::class.java))
    }

    companion object {
        private const val TAG = "HotspotTTL"
        const val ACTION_STOP = "com.serhanensar.hotspotttl.STOP"
        const val MTU = 1500
        const val VPN_ADDR4 = "10.215.173.1"
        const val VPN_ADDR6 = "fd7a:7474:6c00::1"
        const val VIRTUAL_DNS = "10.215.173.2"
        private val vpnAddr4 = InetAddress.getByName(VPN_ADDR4)
        private val vpnAddr6 = InetAddress.getByName(VPN_ADDR6)
        private val virtualDns = InetAddress.getByName(VIRTUAL_DNS)

        @Volatile var instance: TtlVpnService? = null
            private set
        @Volatile var startedAt = 0L
            private set
        @Volatile var lastError: String? = null

        val isRunning get() = instance != null

        fun start(context: Context) {
            context.startService(Intent(context, TtlVpnService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TtlVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
