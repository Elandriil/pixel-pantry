package app.pixelpantry.tv.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class Candidate(val host: String, val port: Int)

interface Discovery {
    /** Emits servers advertising `_pixelpantry._tcp.`; errors complete the flow quietly. */
    fun candidates(timeoutMs: Long): Flow<Candidate>
}

class NsdDiscovery(context: Context) : Discovery {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Suppress("DEPRECATION") // resolveService is deprecated on API 34+ but still works
    override fun candidates(timeoutMs: Long): Flow<Candidate> = callbackFlow {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                runCatching {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress ?: return
                            trySend(Candidate(host, serviceInfo.port))
                        }
                    })
                }
            }
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            close()
        }
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    private companion object {
        const val SERVICE_TYPE = "_pixelpantry._tcp."
    }
}
