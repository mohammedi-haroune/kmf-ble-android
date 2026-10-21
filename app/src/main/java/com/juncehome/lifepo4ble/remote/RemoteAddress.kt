package com.juncehome.lifepo4ble.remote

/** Where the home phone's live feed server can be reached, as typed on the carried phone. */
data class RemoteAddress(val host: String, val port: Int) {
    /** Stable meter id of this remote link; never a Bluetooth address. */
    val id: String get() = "remote:$host:$port"

    /** Short text for lists and status lines. */
    val display: String get() = "$host:$port"

    val liveUrl: String get() = "http://$host:$port/v1/live"

    companion object {
        const val DEFAULT_PORT = 8765
        private const val SCHEME = "http://"
        private val HOST = Regex("[a-z0-9.-]+")

        /**
         * Reads `host`, `host:port` or `http://host:port/`; the host is lowercased and the port defaults to
         * [DEFAULT_PORT]. Returns null for anything else, including IPv6 literals.
         */
        fun parse(text: String): RemoteAddress? {
            var rest = text.trim()
            if (rest.startsWith(SCHEME, ignoreCase = true)) rest = rest.substring(SCHEME.length)
            rest = rest.trimEnd('/').lowercase()
            val parts = rest.split(':')
            if (parts.size > 2) return null
            val host = parts[0]
            if (!HOST.matches(host)) return null
            val port = if (parts.size == 1) DEFAULT_PORT else parsePort(parts[1]) ?: return null
            return RemoteAddress(host, port)
        }

        private fun parsePort(text: String): Int? {
            if (text.isEmpty() || text.length > 5 || !text.all { it in '0'..'9' }) return null
            return text.toInt().takeIf { it in 1..65535 }
        }
    }
}
