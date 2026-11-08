package com.shilapi.xcertplay.headunit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.shilapi.xcertplay.transport.RootShell
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What a driver-started display test did, and what the unit logged about it when root could read the log. */
internal data class ClusterTestResult(val sent: Boolean, val error: String?, val log: List<String>?)

/**
 * The standard AMap guidance broadcast both TEYES and FYT forward to clusters and HUDs (docs/HEADUNIT_*.md). The test
 * sends about ten seconds of guidance: a start state, five shrinking distances to a left turn, then an end state.
 */
internal object AmapGuidanceTest {
    const val ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
    const val KEY_GUIDANCE = 10001
    const val KEY_STATE = 10019
    const val STATE_STARTED = 8
    const val STATE_ENDED = 9
    const val ICON_LEFT = 2
    const val NEXT_ROAD = "Tarmac Street"
    const val CURRENT_ROAD = "Test Road"
    private val DISTANCES = intArrayOf(350, 280, 210, 140, 70)
    private const val STEP_MS = 2_000L

    fun state(state: Int): Intent = Intent(ACTION).putExtra("KEY_TYPE", KEY_STATE).putExtra("EXTRA_STATE", state)

    /** FYT reads ICON and TEYES NEW_ICON, so both carry the turn. */
    fun guidance(distanceMeters: Int, routeMeters: Int, routeSeconds: Int, arrival: String): Intent =
        Intent(ACTION).putExtra("KEY_TYPE", KEY_GUIDANCE)
            .putExtra("ICON", ICON_LEFT)
            .putExtra("NEW_ICON", ICON_LEFT)
            .putExtra("SEG_REMAIN_DIS", distanceMeters)
            .putExtra("SEG_REMAIN_DIS_AUTO", "$distanceMeters m")
            .putExtra("NEXT_ROAD_NAME", NEXT_ROAD)
            .putExtra("CUR_ROAD_NAME", CURRENT_ROAD)
            .putExtra("ROUTE_REMAIN_DIS", routeMeters)
            .putExtra("ROUTE_REMAIN_DIS_AUTO", "${routeMeters / 1000} km")
            .putExtra("ROUTE_REMAIN_TIME", routeSeconds)
            .putExtra("ROUTE_REMAIN_TIME_AUTO", "${routeSeconds / 60} min")
            .putExtra("ROUTE_ALL_DIS", routeMeters)
            .putExtra("ROUTE_ALL_TIME", routeSeconds)
            .putExtra("ETA_TEXT", arrival)
            .putExtra("CAR_DIRECTION", 0)
            .putExtra("ROUNG_ABOUT_NUM", 0)
            .putExtra("CAMERA_SPEED", 0)

    /** Every intent of one test run, in order. */
    fun run(nowMillis: Long): List<Intent> {
        val arrival = "预计" + SimpleDateFormat("HH:mm", Locale.US).format(Date(nowMillis + 15 * 60_000L)) + "到达"
        return listOf(state(STATE_STARTED)) +
            DISTANCES.map { guidance(it, 12_000 - (350 - it), 900, arrival) } +
            listOf(state(STATE_ENDED))
    }

    /** Sends the run as ordinary broadcasts (no root), then reads the unit's log when root allows. Blocks ~12 s. */
    fun send(context: Context): ClusterTestResult {
        val since = RootLog.now()
        val intents = run(System.currentTimeMillis())
        intents.forEachIndexed { index, intent ->
            context.sendBroadcast(intent)
            if (index < intents.lastIndex) Thread.sleep(STEP_MS)
        }
        return ClusterTestResult(true, null, RootLog.since(since, GUIDANCE_KEYS))
    }

    private val GUIDANCE_KEYS = listOf(
        "AUTONAVI", "parseNavigationInfo", "onAmapGuidInfoUpdate", "NaviInfoBox", "Hiworld", "bundle.getInt",
        NEXT_ROAD, "AndroidRuntime",
    )
}

/**
 * FYT's own third-party-player interface: the main module's C_APP_ID (0) makes an app the current source and
 * C_PLAY_INFO (23) hands it title (0), artist (1) and album (2). About ninety CAN-box profiles send those fields to the
 * cluster. No root is needed; the toolkit service is open to apps.
 */
internal object FytTrackText {
    const val APP_ID_THIRD_PLAYER = 10
    const val C_APP_ID = 0
    const val C_PLAY_INFO = 23
    private const val BIND_WAIT_MS = 3_000L

    /** One main-module command: its code, ints and strings. */
    class Command(val code: Int, val ints: IntArray, val strs: Array<String>?)

    /** The commands one test sends, in order: optional source switch, then the three fields. */
    fun commands(becomeSource: Boolean): List<Command> = buildList {
        if (becomeSource) add(Command(C_APP_ID, intArrayOf(APP_ID_THIRD_PLAYER), null))
        add(Command(C_PLAY_INFO, intArrayOf(APP_ID_THIRD_PLAYER, 0), arrayOf(TeyesTrackTextTool.TEST_TITLE)))
        add(Command(C_PLAY_INFO, intArrayOf(APP_ID_THIRD_PLAYER, 1), arrayOf(TeyesTrackTextTool.TEST_ARTIST)))
        add(Command(C_PLAY_INFO, intArrayOf(APP_ID_THIRD_PLAYER, 2), arrayOf(TeyesTrackTextTool.TEST_ALBUM, "")))
    }

    /** Blocks for a few seconds; never call it on the main thread. */
    fun send(context: Context, becomeSource: Boolean): ClusterTestResult {
        val since = RootLog.now()
        val connected = CountDownLatch(1)
        var toolkit: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                toolkit = service
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val bound = runCatching { context.bindService(FytModuleIpc.toolkitIntent(), connection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (!bound) return ClusterTestResult(false, "com.syu.ms.toolkit not available", null)
        try {
            connected.await(BIND_WAIT_MS, TimeUnit.MILLISECONDS)
            val service = toolkit ?: return ClusterTestResult(false, "com.syu.ms.toolkit did not connect", null)
            val module = FytModuleIpc.mainModule(service) ?: return ClusterTestResult(false, "no main module", null)
            for (command in commands(becomeSource)) {
                FytModuleIpc.cmd(module, command.code, command.ints, command.strs)
                Thread.sleep(COMMAND_GAP_MS)
            }
        } catch (error: Exception) {
            Log.w("Tarmac-HeadUnit", "FYT track test failed", error)
            return ClusterTestResult(false, error.message ?: error.javaClass.simpleName, null)
        } finally {
            runCatching { context.unbindService(connection) }
        }
        return ClusterTestResult(true, null, RootLog.since(since, TRACK_KEYS))
    }

    private const val COMMAND_GAP_MS = 300L
    private val TRACK_KEYS = listOf("cmd cmdid", TeyesTrackTextTool.TEST_TITLE, "AndroidRuntime")
}

/** The unit's own log since a moment, read with root; null where there is no su or it was refused. */
internal object RootLog {
    fun now(): String = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    fun since(since: String, keys: List<String>): List<String>? {
        if (RootShell.suPath() == null) return null
        val output = RootShell.run("logcat -d -v brief -T ${RootShell.quote(since)}", 8_000L) ?: return null
        return output.lineSequence().filter { line -> keys.any(line::contains) }.toList().takeLast(60)
    }
}

/** How a recognised platform takes a test track, if it can. */
internal enum class TrackTestRoute { TEYES_ROOT, FYT_MODULE }
