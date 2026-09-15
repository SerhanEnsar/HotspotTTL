package com.serhanensar.hotspotttl

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.EHOSTUNREACH
import android.system.OsConstants.ENETUNREACH
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * VPN'in kullandığı yolla aynı: TTL 65'li soket açar, TTL'i geri okur ve
 * connectivitycheck.gstatic.com'dan HTTP 204 bekler.
 */
object SelfTest {
    private const val HOST = "connectivitycheck.gstatic.com"

    fun run(): String = buildString {
        val addresses = try {
            InetAddress.getAllByName(HOST).toList()
        } catch (e: Exception) {
            appendLine("✗ DNS: $HOST çözülemedi (${e.message})")
            return@buildString
        }
        appendLine("✓ DNS: ${addresses.size} adres")

        addresses.firstOrNull { it is Inet4Address }?.let { append(probe("IPv4", it)) }
            ?: appendLine("– IPv4 adresi yok")
        addresses.firstOrNull { it is Inet6Address }?.let { append(probe("IPv6", it)) }
            ?: appendLine("– IPv6 adresi yok")
    }.trimEnd()

    private fun probe(label: String, address: InetAddress): String = buildString {
        val start = System.currentTimeMillis()
        val fd = try {
            TtlSocket.connectTcp(address, 80, timeoutMs = 8000)
        } catch (e: ErrnoException) {
            if (e.errno == ENETUNREACH || e.errno == EHOSTUNREACH) appendLine("– $label: bu ağda yok")
            else appendLine("✗ $label: bağlanılamadı (${e.message})")
            return@buildString
        } catch (e: Exception) {
            appendLine("✗ $label: bağlanılamadı (${e.message})")
            return@buildString
        }
        try {
            // setsockopt başarısız olsaydı connectTcp istisna fırlatırdı
            val ttl = TtlSocket.TTL
            val request = "GET /generate_204 HTTP/1.1\r\nHost: $HOST\r\nConnection: close\r\n\r\n".toByteArray()
            Os.write(fd, request, 0, request.size)
            val buf = ByteArray(256)
            val n = Os.read(fd, buf, 0, buf.size)
            val status = if (n > 0) String(buf, 0, n).lineSequence().first() else "yanıt yok"
            val ms = System.currentTimeMillis() - start
            val ok = status.contains(" 204")
            appendLine("${if (ok) "✓" else "✗"} $label: TTL $ttl, $status (${ms} ms)")
        } catch (e: Exception) {
            appendLine("✗ $label: ${e.message}")
        } finally {
            try { Os.close(fd) } catch (_: Exception) {}
        }
    }
}
