package com.shilapi.xcertplay.vehicle

import java.io.IOException

/** A received CAN frame. */
class CanFrame(val id: Int, val extended: Boolean, val data: ByteArray)

/**
 * Everything DiPlay may send to an slcan (Lawicel) adapter such as a CANable: close the channel, set the
 * bitrate, open it in listen-only mode, ask for the version. Never `t`/`T`/`r`/`R` (a frame of its own) and
 * never `O`, which opens the channel in normal mode where the adapter acknowledges other nodes' frames. In
 * listen-only mode the adapter's CAN controller does not drive the bus at all.
 */
object SlcanCommands {
    private val allowed = Regex("^(C|S[0-8]|L|V)$")

    fun allowed(command: String): Boolean = allowed.matches(command)
}

/** An slcan adapter on a serial link. Blocking; runs on the vehicle-data thread only. */
class Slcan(private val link: SerialLink, private val clock: () -> Long = System::currentTimeMillis) {
    private val line = StringBuilder()
    private val buffer = ByteArray(512)
    private val pending = ArrayDeque<Char>()

    /**
     * Opens the channel listen-only at [bitrate]. False when the adapter refuses listen-only mode: DiPlay then
     * stays off the bus instead of opening it normally.
     */
    fun openListenOnly(bitrate: Int): Boolean {
        val speed = bitrateCommand(bitrate) ?: return false
        send("C") // closes a channel left open; refused (BEL) when it was already closed
        if (send(speed) != true) return false
        return send("L") == true
    }

    fun close() {
        runCatching { send("C") }
    }

    /** True for an acknowledgement (CR), false for a refusal (BEL), null when the adapter said nothing. */
    private fun send(command: String): Boolean? {
        if (!SlcanCommands.allowed(command)) throw IllegalArgumentException("refused to send $command")
        line.clear()
        pending.clear()
        link.write("$command\r".toByteArray(Charsets.US_ASCII))
        val deadline = clock() + COMMAND_TIMEOUT_MILLIS
        while (clock() < deadline) {
            val char = next((deadline - clock()).toInt()) ?: continue
            when (char) {
                '\r' -> if (line.isEmpty() || command == "V") return true.also { line.clear() } else line.clear()
                BELL -> return false
                else -> line.append(char)
            }
        }
        return null
    }

    /** The next data frame within [timeoutMillis], or null; remote and malformed frames are skipped. */
    fun readFrame(timeoutMillis: Int): CanFrame? {
        val deadline = clock() + timeoutMillis
        while (true) {
            val remaining = deadline - clock()
            if (remaining <= 0) return null
            val char = next(remaining.toInt()) ?: continue
            if (char == '\r' || char == BELL) {
                val frame = parse(line.toString())
                line.clear()
                if (frame != null) return frame
            } else if (line.length < MAX_LINE) {
                line.append(char)
            } else {
                line.clear()
            }
        }
    }

    private fun next(timeoutMillis: Int): Char? {
        pending.removeFirstOrNull()?.let { return it }
        val count = link.read(buffer, timeoutMillis.coerceAtLeast(1))
        if (count < 0) throw IOException("the CAN adapter disconnected")
        for (index in 0 until count) pending.addLast((buffer[index].toInt() and 0xFF).toChar())
        return pending.removeFirstOrNull()
    }

    companion object {
        private const val BELL = '\u0007'
        private const val MAX_LINE = 64
        private const val COMMAND_TIMEOUT_MILLIS = 1_000L
        private val bitrates = mapOf(10_000 to "S0", 20_000 to "S1", 50_000 to "S2", 100_000 to "S3",
            125_000 to "S4", 250_000 to "S5", 500_000 to "S6", 800_000 to "S7", 1_000_000 to "S8")

        fun bitrateCommand(bitrate: Int): String? = bitrates[bitrate]

        /** `tIIILDD…` (11-bit) or `TIIIIIIIILDD…` (29-bit), with or without a 4-digit timestamp. */
        fun parse(text: String): CanFrame? {
            val extended = when (text.firstOrNull()) {
                't' -> false
                'T' -> true
                else -> return null // remote frames, acknowledgements, CAN FD and anything else
            }
            val idEnd = if (extended) 9 else 4
            if (text.length < idEnd + 1) return null
            val id = text.substring(1, idEnd).toIntOrNull(16) ?: return null
            val length = text[idEnd].digitToIntOrNull() ?: return null
            if (length > 8) return null
            val dataEnd = idEnd + 1 + length * 2
            if (text.length != dataEnd && text.length != dataEnd + 4) return null
            val data = ByteArray(length) { index ->
                text.substring(idEnd + 1 + index * 2, idEnd + 3 + index * 2).toIntOrNull(16)?.toByte() ?: return null
            }
            return CanFrame(id, extended, data)
        }
    }
}
