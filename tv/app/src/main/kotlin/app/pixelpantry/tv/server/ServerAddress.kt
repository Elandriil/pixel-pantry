package app.pixelpantry.tv.server

/** A server address as the user types it: `host:port`. */
object ServerAddress {
    /** `host:port` with a non-empty host, no scheme or path, and a port in 1..65535. */
    fun isValid(address: String): Boolean {
        val colon = address.lastIndexOf(':')
        if (colon <= 0) return false
        val host = address.substring(0, colon)
        val port = address.substring(colon + 1).toIntOrNull() ?: return false
        if (port !in 1..65535) return false
        return host.none { it.isWhitespace() || it == '/' || it == ':' || it == '@' }
    }
}
