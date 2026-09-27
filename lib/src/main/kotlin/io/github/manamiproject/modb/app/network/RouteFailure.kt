package io.github.manamiproject.modb.app.network

import java.io.IOException
import javax.net.ssl.SSLHandshakeException

/**
 * Whether a request failed on its way to the site rather than at the site. A proxy which cannot open a tunnel, or an
 * exit which answers the TLS handshake with a certificate of its own, says nothing about the entry being requested.
 * Leaving through another route is what fixes it, and skipping the entry instead only spends the next one against the
 * same broken route.
 * @since 1.0.0
 * @receiver Any failure of a request.
 * @return `true` if the request is worth repeating through another route.
 */
fun Throwable.isRouteFailure(): Boolean = generateSequence(this) { it.cause }.any {
    when (it) {
        is SSLHandshakeException -> true
        is IOException -> it.message?.startsWith(TUNNEL_REFUSED) == true
        else -> false
    }
}

private const val TUNNEL_REFUSED = "Unexpected response code for CONNECT"
