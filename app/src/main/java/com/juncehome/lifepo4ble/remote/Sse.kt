package com.juncehome.lifepo4ble.remote

/** One dispatched Server-Sent Event: [name] defaults to "message" when the stream gave no `event:` field. */
data class SseEvent(val name: String, val data: String)

/** Writer side of the SSE wire format. */
object SseFormat {
    private val LINE_BREAKS = Regex("\r\n|\r|\n")

    /** `event: <name>`, one `data:` line per line of [data] (so the payload can never end the event early), blank line. */
    fun event(name: String, data: String): String = buildString {
        append("event: ").append(name).append('\n')
        for (line in data.split(LINE_BREAKS)) append("data: ").append(line).append('\n')
        append('\n')
    }

    /** Comment block ignored by readers; used as the 15 s `: ping` keepalive. */
    fun comment(text: String): String = ": $text\n\n"
}

/**
 * Reader side of the SSE wire format. Feed it one line at a time without its terminator; it returns the event when
 * the blank line that ends it arrives. Comments, `id`, `retry` and unknown fields are ignored, and a block with no
 * `data:` line dispatches nothing.
 */
class SseLineReader {
    private var eventName: String? = null
    private val dataLines = mutableListOf<String>()

    fun offer(line: String): SseEvent? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(':')) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> eventName = value
            "data" -> dataLines += value
        }
        return null
    }

    private fun dispatch(): SseEvent? {
        val event = if (dataLines.isEmpty()) null else SseEvent(eventName ?: "message", dataLines.joinToString("\n"))
        eventName = null
        dataLines.clear()
        return event
    }
}
