package app.pixelpantry.tv.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.net.InetAddress

data class Candidate(val host: String, val port: Int)

interface Discovery {
    /** Emits servers advertising `_pixelpantry._tcp.`; errors complete the flow quietly. */
    fun candidates(timeoutMs: Long): Flow<Candidate>
}

class NsdDiscovery(context: Context) : Discovery {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** [timeoutMs] is ignored: the collector (ServerResolver) owns the timeout and cancels the flow. */
    @Suppress("DEPRECATION") // resolveService is deprecated on API 34+ but still works
    override fun candidates(timeoutMs: Long): Flow<Candidate> = callbackFlow {
        // NsdManager allows one active resolve at a time, so found services are resolved one by one.
        val lock = Any()
        val pending = ArrayDeque<Pair<NsdServiceInfo, Boolean>>() // service, already retried
        var resolving = false
        // Set once the collector is gone: callbacks still arriving from NsdManager start no further resolves.
        var closed = false

        fun resolveNext() {
            val next = synchronized(lock) {
                if (resolving || closed) return
                pending.removeFirstOrNull()?.also { resolving = true } ?: return
            }
            val (service, retried) = next
            val finish = {
                synchronized(lock) { resolving = false }
                resolveNext()
            }
            try {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "mDNS resolve failed for ${serviceInfo.serviceName}: $errorCode")
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && !retried) {
                            synchronized(lock) { if (!closed) pending.addLast(service to true) }
                        }
                        finish()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        Log.i(TAG, "mDNS resolved ${serviceInfo.serviceName} -> ${hostString(serviceInfo)}:${serviceInfo.port}")
                        hostString(serviceInfo)?.let { trySend(Candidate(it, serviceInfo.port)) }
                        finish()
                    }
                })
            } catch (e: Exception) {
                finish()
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery failed to start: $errorCode")
                close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "mDNS found ${serviceInfo.serviceName}")
                synchronized(lock) { if (!closed) pending.addLast(serviceInfo to false) }
                resolveNext()
            }
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            close()
        }
        awaitClose {
            synchronized(lock) {
                closed = true
                pending.clear()
            }
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    /** Prefers IPv4; an IPv6 literal is bracketed so it can be used in a URL. */
    @Suppress("DEPRECATION") // NsdServiceInfo.host is deprecated on API 34+ in favour of hostAddresses
    private fun hostString(info: NsdServiceInfo): String? {
        val addr: InetAddress = info.host ?: return null
        val text = addr.hostAddress ?: return null
        if (addr is Inet4Address) return text
        return "[${text.substringBefore('%')}]"
    }

    private companion object {
        const val SERVICE_TYPE = "_pixelpantry._tcp."
        const val TAG = "PixelPantry"
    }
}
