package com.shilapi.xcertplay.headunit

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import com.shilapi.xcertplay.transport.RootShell
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Root-only test for TEYES: hands the unit's source service (`spd`) one track the way TEYES's own players do, then
 * prints what TEYES logged, so the driver can see whether the CAN-box protocol passed it to the cluster. Runs through
 * app_process as root; layouts are from the CC4 firmware (docs/HEADUNIT_SPD_TEYES.md).
 */
object TeyesTrackTextTool {
    const val HEADER = "TARMAC-TRACK"
    const val TEST_TITLE = "Tarmac test"
    const val TEST_ARTIST = "CarPlay"
    const val TEST_ALBUM = "Tarmac"

    /** The two records a third-party track can travel in; each carries its own device ID and class. */
    enum class Record(val deviceId: Int, val className: String) {
        OTHER_MEDIA(14, "com.spd.system.entity.aidl.OtherMediaInfo"),
        MEDIA(1, "com.spd.system.entity.aidl.MediaInfo"),
    }

    private const val DESCRIPTOR = "com.spd.system.aidl.ISpdService"
    private const val TRANSACTION_SYNC_SOURCE_INFO = 7
    private const val TRANSACTION_ENTER_SOURCE = 22
    private const val LOG_WAIT_MS = 1_500L
    private const val DEADLINE_MS = 20_000L
    private const val MAX_LOG_LINES = 60
    private val LOG_KEYS = listOf(
        "SourceInfo", "SourcManager", "sendCommonToSouce", "CarInfoManage", "playTitle", TEST_TITLE,
        "AndroidRuntime", "FATAL",
    )

    @JvmStatic fun main(args: Array<String>) {
        Thread({ Thread.sleep(DEADLINE_MS); exitProcess(3) }, "tarmac-track-deadline").apply { isDaemon = true; start() }
        val pkg = args.getOrNull(0).orEmpty()
        val record = Record.entries.firstOrNull { it.name == args.getOrNull(1) } ?: Record.OTHER_MEDIA
        val enterSource = args.getOrNull(2) == "enter"
        val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val result = runCatching {
            val spd = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "spd") as? IBinder ?: error("spd service not found")
            if (enterSource) call(spd, TRANSACTION_ENTER_SOURCE) { writeString(pkg) }
            call(spd, TRANSACTION_SYNC_SOURCE_INFO) { writeSyncSourceInfo(this, pkg, record) }
        }
        result.exceptionOrNull()?.let { println("$HEADER|error|${it.message ?: it.javaClass.simpleName}") }
        Thread.sleep(LOG_WAIT_MS)
        runCatching { teyesLog(since, pkg) }.getOrDefault(emptyList()).forEach { println("$HEADER|log|$it") }
        println("$HEADER|done|${if (result.isSuccess) "sent" else "failed"}")
        exitProcess(0)
    }

    /** `syncSourceInfo(String pkg, DataWrapper)`: a typed DataWrapper holding one parcelable SourceInfo subclass. */
    fun writeSyncSourceInfo(parcel: Parcel, pkg: String, record: Record) {
        parcel.writeString(pkg)
        parcel.writeInt(1)
        parcel.writeString(record.className)
        parcel.writeInt(record.deviceId)
        when (record) {
            Record.OTHER_MEDIA -> {
                parcel.writeString(pkg)
                parcel.writeString(TEST_TITLE)
                parcel.writeString(TEST_ARTIST)
                parcel.writeString(TEST_ALBUM)
                // playTime, playTotalTime, playIndex, playTotalNumber, playState (1 = playing)
                for (value in intArrayOf(0, 0, 1, 1, 1)) parcel.writeInt(value)
            }
            Record.MEDIA -> {
                parcel.writeString(TEST_TITLE)
                parcel.writeString(TEST_TITLE)
                parcel.writeString(TEST_ARTIST)
                parcel.writeString(TEST_ALBUM)
                // usb1State, usb2State, playType, playIndex, playTotalNumber, playTime, playTotalTime, playState
                for (value in intArrayOf(0, 0, 0, 1, 1, 0, 0, 1)) parcel.writeInt(value)
            }
        }
    }

    private fun call(binder: IBinder, code: Int, write: Parcel.() -> Unit) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.write()
            check(binder.transact(code, data, reply, 0)) { "transaction $code was refused" }
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun teyesLog(since: String, pkg: String): List<String> {
        val process = ProcessBuilder("logcat", "-d", "-v", "brief", "-T", since).redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        return relevant(lines, pkg)
    }

    /** The log lines that tell whether TEYES took the track: its source manager, CarInfo and the car protocol. */
    fun relevant(lines: List<String>, pkg: String): List<String> =
        lines.filter { line -> LOG_KEYS.any(line::contains) || line.contains(pkg) }.takeLast(MAX_LOG_LINES)

    /** The root command for [record], run by su. Nothing in it comes from outside the app. */
    fun command(apk: String, pkg: String, record: Record, enterSource: Boolean): String {
        require(Regex("[A-Za-z0-9_.]+").matches(pkg)) { "bad package name: $pkg" }
        return "CLASSPATH=${RootShell.quote(apk)} app_process /system/bin ${TeyesTrackTextTool::class.java.name} " +
            "$pkg ${record.name} ${if (enterSource) "enter" else "keep"} 2>&1"
    }

    internal fun parse(output: String?): ClusterTestResult {
        if (output == null) return ClusterTestResult(false, "no su", emptyList())
        val lines = output.lineSequence().map(String::trim).filter { it.startsWith("$HEADER|") }
            .map { it.removePrefix("$HEADER|") }.toList()
        val error = lines.lastOrNull { it.startsWith("error|") }?.removePrefix("error|")
        val done = lines.lastOrNull { it.startsWith("done|") }?.removePrefix("done|")
        val log = lines.filter { it.startsWith("log|") }.map { it.removePrefix("log|") }
        return ClusterTestResult(done == "sent", error ?: if (done == null) "root was refused or the helper did not run" else null, log)
    }

    /** Runs the test as root. Blocks for a few seconds; never call it on the main thread. */
    internal fun run(context: Context, record: Record, enterSource: Boolean): ClusterTestResult =
        parse(RootShell.run(command(context.applicationInfo.sourceDir, context.packageName, record, enterSource), 25_000L))
}
