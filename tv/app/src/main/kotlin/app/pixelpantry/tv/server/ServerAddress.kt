package app.pixelpantry.tv.server

/** A server address as the user types it: `host:port`, or `[ipv6]:port` as discovery produces it. */
object ServerAddress {
    /** `host:port` with a non-empty host, no scheme or path, and a port in 1..65535. */
    fun isValid(address: String): Boolean {
        val colon = address.lastIndexOf(':')
        if (colon <= 0) return false
        val host = address.substring(0, colon)
        val port = address.substring(colon + 1).toIntOrNull() ?: return false
        if (port !in 1..65535) return false
        if (host.startsWith("[")) {
            // a bracketed IPv6 literal, as mDNS discovery and the last-known address store it
            if (!host.endsWith("]") || host.length < 3) return false
            return host.substring(1, host.length - 1).all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
        }
        return host.none { it.isWhitespace() || it == '/' || it == ':' || it == '@' || it == '[' || it == ']' }
    }
}
