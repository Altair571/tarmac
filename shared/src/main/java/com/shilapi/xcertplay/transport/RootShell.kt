package com.shilapi.xcertplay.transport

import java.io.File
import java.io.IOException

/** `su` on a rooted head unit. Whether a command runs is up to the root manager, which may ask the driver. */
object RootShell {
    private val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/su/bin/su")

    /** The su binary an app can run, or null on an unrooted device. */
    fun suPath(): String? {
        val onPath = System.getenv("PATH").orEmpty().split(':').filter { it.isNotBlank() }.map { "$it/su" }
        return (SU_PATHS + onPath).firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }
    }

    /** Starts [command] as root with separate standard streams, for a helper the caller talks to. */
    @Throws(IOException::class)
    fun start(command: String): Process {
        val su = suPath() ?: throw IOException("No su binary on this device")
        return ProcessBuilder(su, "-c", command).redirectErrorStream(false).start()
    }

    /**
     * Runs [command] as root and returns its standard output, or null without su. A command still running after
     * [timeoutMs] is stopped and its output so far is returned. Blocks; never call it on the main thread.
     */
    fun run(command: String, timeoutMs: Long): String? {
        val process = runCatching { start(command) }.getOrNull() ?: return null
        val output = StringBuilder()
        val reader = Thread({
            runCatching { process.inputStream.bufferedReader().forEachLine { synchronized(output) { output.appendLine(it) } } }
        }, "tarmac-root-shell").apply { isDaemon = true; start() }
        runCatching { process.outputStream.close() }
        reader.join(timeoutMs)
        if (reader.isAlive) process.destroy()
        reader.join(STOP_WAIT_MS)
        return synchronized(output) { output.toString() }
    }

    /** Single-quotes [value] for `sh -c`. */
    fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

    private const val STOP_WAIT_MS = 500L
}
