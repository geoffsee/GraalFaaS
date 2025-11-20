package ltd.gsio.app

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM‑wide egress enforcement hooks. Currently installs a global ProxySelector
 * that denies blocked destinations before any HTTP client attempts a connection.
 *
 * Note: Raw sockets may bypass ProxySelector. Guests do not get direct socket access; they use VirtualNet.
 */
object EgressGuard {
    private val installed = AtomicBoolean(false)

    fun installProxySelector() {
        if (installed.compareAndSet(false, true)) {
            val delegate = try { ProxySelector.getDefault() } catch (_: Throwable) { null }
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI?): MutableList<Proxy> {
                    if (uri != null) {
                        // Deny when blocked or on any error (fail‑closed)
                        EgressFilter.enforceUri(uri)
                    }
                    return mutableListOf(Proxy.NO_PROXY)
                }

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                    // Delegate if original selector existed
                    delegate?.connectFailed(uri, sa, ioe)
                }
            })
        }
    }
}
