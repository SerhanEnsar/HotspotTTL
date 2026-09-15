package com.serhanensar.hotspotttl

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.*
import android.system.StructTimeval
import java.io.FileDescriptor
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Giden bütün bağlantılar buradan açılır. Soket bağlanmadan önce TTL (IPv4) ve
 * hop limit (IPv6) 65 yapılır. Telefon hotspot'u paketi yönlendirirken 1 azaltır
 * ve operatör telefonun kendi trafiğindeki gibi 64 görür.
 *
 * Uygulama VPN'in dışında tutulduğu için (addDisallowedApplication) bu soketler
 * doğrudan Wi-Fi'dan çıkar, protect() gerekmez.
 */
object TtlSocket {
    const val TTL = 65

    fun open(address: InetAddress, type: Int): FileDescriptor {
        val v6 = address is Inet6Address
        val proto = if (type == SOCK_STREAM) IPPROTO_TCP else IPPROTO_UDP
        val fd = Os.socket(if (v6) AF_INET6 else AF_INET, type, proto)
        try {
            if (v6) Os.setsockoptInt(fd, IPPROTO_IPV6, IPV6_UNICAST_HOPS, TTL)
            else Os.setsockoptInt(fd, IPPROTO_IP, IP_TTL, TTL)
        } catch (e: ErrnoException) {
            Os.close(fd)
            throw e
        }
        return fd
    }

    /** Engelleyici TCP bağlantısı. Linux connect() için SO_SNDTIMEO'yu zaman aşımı olarak kullanır. */
    fun connectTcp(address: InetAddress, port: Int, timeoutMs: Long = 15_000): FileDescriptor {
        val fd = open(address, SOCK_STREAM)
        try {
            Os.setsockoptTimeval(fd, SOL_SOCKET, SO_SNDTIMEO, StructTimeval.fromMillis(timeoutMs))
            Os.connect(fd, address, port)
            Os.setsockoptTimeval(fd, SOL_SOCKET, SO_SNDTIMEO, StructTimeval.fromMillis(0))
            Os.setsockoptInt(fd, IPPROTO_TCP, TCP_NODELAY, 1)
        } catch (e: Exception) {
            try { Os.close(fd) } catch (_: Exception) {}
            throw e
        }
        return fd
    }

    /**
     * read() içinde bekleyen iş parçacığını uyandırır. Descriptor'ı kapatmaz: kapatma işini
     * soketi okuyan iş parçacığı yapar, böylece numarası başka sokete geçmiş bir fd'ye yazılmaz.
     */
    fun shutdownQuietly(fd: FileDescriptor) {
        try { Os.shutdown(fd, SHUT_RDWR) } catch (_: Exception) {}
    }
}
