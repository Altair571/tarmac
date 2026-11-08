package com.shilapi.xcertplay.vehicle

import java.io.IOException

/**
 * Everything DiPlay may send to an ELM327 or STN OBD-II adapter. OBD-II reading works by request and answer,
 * so the adapter does put request frames on the diagnostic bus; these are only the read services. Writing
 * data, clearing fault codes, resetting a module, opening a diagnostic session or running a routine (services
 * 04, 10, 11, 14, 27, 2E, 2F, 31 and the rest) is refused here, before anything reaches the adapter.
 */
object Elm327Requests {
    /** Current data (01), vehicle information (09), read data by identifier (22). */
    val READ_SERVICES = listOf("01", "09", "22")

    private val readRequest = Regex("^(01|09)[0-9A-F]{2}$|^22[0-9A-F]{4}$")
    private val header = Regex("^[0-9A-F]{3}$")

    /** Reset, echo/linefeeds/spaces/headers off, adaptive timing, automatic protocol, identify. */
    private val setup = setOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0", "ATI")

    fun allowed(request: String): Boolean = readRequest.matches(request)

    /** An 11-bit CAN id such as `7E4`; 29-bit cars are read with the standard requests only. */
    fun validHeader(text: String): Boolean = header.matches(text)

    fun allowedCommand(command: String): Boolean = command in setup || allowed(command) ||
        (command.startsWith("ATSH") && validHeader(command.removePrefix("ATSH")))

    /** The broadcast id an adapter uses after reset. */
    const val DEFAULT_HEADER = "7DF"
}

/** Reads an ELM327 answer: single-frame, ISO-TP multi-frame (`03E` / `0:` / `1:`), or an error. */
object Elm327Response {
    private val errors = listOf("NO DATA", "NODATA", "?", "CAN ERROR", "CANERROR", "UNABLE TO CONNECT",
        "UNABLETOCONNECT", "STOPPED", "BUFFER FULL", "BUFFERFULL", "BUS ERROR", "BUSERROR", "BUS INIT", "ERROR")
    private val hex = Regex("^[0-9A-F]+$")
    private val length = Regex("^[0-9A-F]{3}$")
    private val numbered = Regex("^[0-9A-F]:[0-9A-F]+$")

    /**
     * The data bytes after the echoed service and PID (Torque's A, B, C…), or null for an error, an empty
     * answer or an answer to a different request.
     */
    fun data(reply: String, request: String): ByteArray? {
        val lines = reply.replace('\n', '\r').split('\r')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() && it != ">" && !it.startsWith("SEARCHING") }
        if (lines.isEmpty() || lines.any { line -> errors.any { line.startsWith(it) } }) return null
        val compact = lines.map { it.replace(" ", "") }
        val prefix = "%02X".format(request.take(2).toInt(16) + 0x40) + request.drop(2)
        val payload = when {
            compact.size >= 2 && length.matches(compact[0]) && compact.drop(1).all(numbered::matches) ->
                compact.drop(1).joinToString("") { it.substringAfter(':') }.take(compact[0].toInt(16) * 2)
            compact.all(numbered::matches) -> compact.joinToString("") { it.substringAfter(':') }
            // With headers off several modules may answer; the first answer to this request wins.
            else -> compact.firstOrNull { hex.matches(it) && it.startsWith(prefix) } ?: return null
        }
        if (!payload.startsWith(prefix) || payload.length % 2 != 0) return null
        return payload.drop(prefix.length).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /** Mode 01 support bitmaps: PID `base + 1 + n` is supported when bit 31 - n of the answer is set. */
    fun supportedPids(base: Int, data: ByteArray): Set<Int> {
        if (data.size < 4) return emptySet()
        val bits = CanBits.bytes(data, 0, 4, signed = false) ?: return emptySet()
        return (0 until 32).filter { bits and (1L shl (31 - it)) != 0L }.map { base + 1 + it }.toSet()
    }
}

/**
 * An ELM327/STN adapter on a serial link. Blocking; runs on the vehicle-data thread only. Every command goes
 * through [Elm327Requests.allowedCommand].
 */
class Elm327(private val link: SerialLink, private val clock: () -> Long = System::currentTimeMillis) {
    var version: String? = null
        private set
    private var header = Elm327Requests.DEFAULT_HEADER

    /** The text before the `>` prompt, or null when the adapter did not finish within [timeoutMillis]. */
    fun command(text: String, timeoutMillis: Int = COMMAND_TIMEOUT_MILLIS): String? {
        if (!Elm327Requests.allowedCommand(text)) throw IllegalArgumentException("refused to send $text")
        link.write("$text\r".toByteArray(Charsets.US_ASCII))
        val answer = StringBuilder()
        val buffer = ByteArray(128)
        val deadline = clock() + timeoutMillis
        while (true) {
            val remaining = deadline - clock()
            if (remaining <= 0) return null
            val count = link.read(buffer, remaining.toInt())
            if (count < 0) throw IOException("the OBD-II adapter disconnected")
            for (index in 0 until count) {
                val char = (buffer[index].toInt() and 0xFF).toChar()
                if (char == '>') return answer.toString()
                if (char != '\u0000') answer.append(char)
            }
        }
    }

    /** Resets the adapter and finds the car's protocol; false when it is not an ELM327 or the car is off. */
    fun initialize(): Boolean {
        // ATZ answers with the version after a reset that takes up to a second.
        val reset = command("ATZ", RESET_TIMEOUT_MILLIS) ?: return false
        for (setting in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) command(setting) ?: return false
        version = (command("ATI") ?: reset).lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        header = Elm327Requests.DEFAULT_HEADER
        // The first request starts the protocol search ("SEARCHING..."), which takes several seconds.
        return query("0100", null, SEARCH_TIMEOUT_MILLIS) != null
    }

    /** The data bytes of [request]'s answer, sent to [requestHeader] (or the broadcast id); null without an answer. */
    fun query(request: String, requestHeader: String?, timeoutMillis: Int = COMMAND_TIMEOUT_MILLIS): ByteArray? {
        val wanted = requestHeader ?: Elm327Requests.DEFAULT_HEADER
        if (wanted != header) {
            command("ATSH$wanted") ?: return null
            header = wanted
        }
        return Elm327Response.data(command(request, timeoutMillis) ?: return null, request)
    }

    /** The mode 01 PIDs the car answers, following the 00/20/40 bitmaps as far as the car supports them. */
    fun supportedPids(): Set<Int> {
        val supported = mutableSetOf<Int>()
        for (base in listOf(0x00, 0x20, 0x40)) {
            if (base != 0 && base !in supported) break
            val data = query("01%02X".format(base), null) ?: break
            supported += Elm327Response.supportedPids(base, data)
        }
        return supported
    }

    companion object {
        const val COMMAND_TIMEOUT_MILLIS = 3_000
        const val RESET_TIMEOUT_MILLIS = 5_000
        const val SEARCH_TIMEOUT_MILLIS = 15_000

        /** Hybrid/EV battery pack remaining life, SAE J1979: A x 100 / 255 %. Few battery-electric cars answer it. */
        const val PID_HYBRID_BATTERY_REMAINING = 0x5B

        fun stateOfCharge(data: ByteArray): Double? = data.getOrNull(0)?.let { (it.toInt() and 0xFF) * 100.0 / 255 }
    }
}
