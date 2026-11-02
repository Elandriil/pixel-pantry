package app.pixelpantry.tv.server

/**
 * What the TV holds after entering the server's PIN once: the token it sends on every API call and the id of
 * the server that issued it (`/api/info` names the server, so the token is never sent to a different one).
 */
data class Pairing(val token: String, val serverId: String)
