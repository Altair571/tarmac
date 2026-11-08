package com.shilapi.xcertplay.headunit

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.WheelKeyLog
import com.shilapi.xcertplay.transport.RootShell
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** What Tarmac does with a key `com.syu.ms` hands over: a CarPlay action, or the key given back as the unit would. */
internal sealed interface FytLinkKey {
    data class CarPlay(val action: HeadUnitKeyAction) : FytLinkKey

    /** A media key, sent to the playing app as a media button, as `com.syu.ms` itself does. */
    data class Media(val keyCode: Int) : FytLinkKey

    /** Home or back, injected as root so the unit's own keys keep working. */
    data class System(val keyCode: Int) : FytLinkKey
}

/** The keys `com.syu.ms` forwards as main update 133 while `sys.carlink.type` is set (docs/HEADUNIT_FYT_DUDU.md). */
internal object FytLinkKeys {
    const val HOME = 574
    const val BACK = 575
    const val VOICE = 576
    const val PHONE_HANG_UP = 587
    const val PHONE_ANSWER = 588

    /** `com.syu.ms` turns 588 into "answer" and 587 into "hang up" for FYT's own CarPlay calls; Tarmac does the same. */
    fun forKey(code: Int): FytLinkKey? = when (code) {
        VOICE -> FytLinkKey.CarPlay(HeadUnitKeyAction.SIRI)
        KeyEvent.KEYCODE_CALL, PHONE_ANSWER -> FytLinkKey.CarPlay(HeadUnitKeyAction.ANSWER_CALL)
        KeyEvent.KEYCODE_ENDCALL, PHONE_HANG_UP -> FytLinkKey.CarPlay(HeadUnitKeyAction.END_CALL)
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> FytLinkKey.Media(code)
        HOME -> FytLinkKey.System(KeyEvent.KEYCODE_HOME)
        BACK -> FytLinkKey.System(KeyEvent.KEYCODE_BACK)
        else -> null
    }
}

/**
 * Root helper for [FytKeyLink], run through app_process. It holds `sys.carlink.type` at the link type while Tarmac's
 * process lives and its input stays open, injects home and back on request, and then restores the property.
 */
object FytKeyGateTool {
    const val HEADER = "TARMAC-KEYS"
    const val PROPERTY = "sys.carlink.type"
    private const val HOLD_MILLIS = 1_000L

    /** Only these keys may be injected: the two the link takes away from the unit's own handling. */
    private val INJECTABLE = setOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK)

    @JvmStatic fun main(args: Array<String>) {
        val pid = args.getOrNull(0)?.toIntOrNull() ?: exitProcess(2)
        val type = args.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..3 }?.toString() ?: exitProcess(2)
        val properties = Class.forName("android.os.SystemProperties")
        val get = properties.getMethod("get", String::class.java)
        val set = properties.getMethod("set", String::class.java, String::class.java)
        val before = get.invoke(null, PROPERTY) as String
        val stop = CountDownLatch(1)
        Thread({ commands(); stop.countDown() }, "tarmac-key-gate-input").apply { isDaemon = true; start() }
        println("$HEADER|ready")
        while (File("/proc/$pid").exists()) {
            if (get.invoke(null, PROPERTY) != type) set.invoke(null, PROPERTY, type)
            if (stop.await(HOLD_MILLIS, TimeUnit.MILLISECONDS)) break
        }
        set.invoke(null, PROPERTY, restored(before, type))
        println("$HEADER|stopped")
        exitProcess(0)
    }

    /** The value to put back: what was there before, unless that was a link type left by an earlier helper. */
    fun restored(before: String, type: String): String = before.takeIf { it.isNotBlank() && it != type } ?: "0"

    private fun commands() {
        val input = System.`in`.bufferedReader()
        while (true) {
            val line = runCatching { input.readLine() }.getOrNull()?.trim() ?: return
            if (line == "stop") return
            val key = line.removePrefix("key ").toIntOrNull()
            if (line.startsWith("key ") && key != null && key in INJECTABLE) {
                runCatching { inject(key) }.onFailure { println("$HEADER|error|${it.javaClass.simpleName}") }
            }
        }
    }

    private fun inject(keyCode: Int) {
        val manager = runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
        }.getOrElse { Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null) }
        val inject = manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD)
            // 0: INJECT_INPUT_EVENT_MODE_ASYNC.
            inject.invoke(manager, event, 0)
        }
    }

    /** The root command for Tarmac's process [pid]. Nothing in it comes from outside the app. */
    fun command(apk: String, pid: Int, type: Int): String {
        require(pid > 0 && type in 1..3) { "bad helper arguments" }
        return "CLASSPATH=${RootShell.quote(apk)} app_process /system/bin ${FytKeyGateTool::class.java.name} $pid $type"
    }
}

/**
 * FYT's voice and call keys for CarPlay on a rooted unit. While a CarPlay session runs, [FytKeyGateTool] holds
 * `sys.carlink.type` set, so `com.syu.ms` hands the wheel keys to apps registered for main update 133 instead of acting
 * on them, as it does for FYT's own CarPlay. Tarmac sends voice and call keys to CarPlay and gives every other key
 * back the way the unit would have. The link stops with the session, and the helper restores the property even if
 * Tarmac's process ends.
 */
internal object FytKeyLink {
    private const val TAG = "Tarmac-HeadUnit"
    private const val WATCH_MILLIS = 2_000L
    private const val STOP_WAIT_MILLIS = 1_500L

    /** `com.syu.ms` forwards 5, 6 and 576 unchanged for this type and leaves FYT's Bluetooth phone alone. */
    const val LINK_TYPE = 3

    private var current: Link? = null

    /** Whether the link may run here: an FYT unit, the driver's choice, and an su binary. */
    fun available(context: Context): Boolean =
        HeadUnitPlatforms.current(context)?.linksKeysWithRoot == true && HeadUnitSettings.linkKeys(context) &&
            RootShell.suPath() != null

    /** Called when a CarPlay session becomes active, and when the driver turns the link on during one. */
    @Synchronized fun sessionActive(context: Context) {
        if (current != null || !available(context)) return
        current = Link(context.applicationContext).also(Link::start)
    }

    /** Applies the driver's switch at once: links the keys during a running session and gives them back otherwise. */
    fun apply(context: Context) {
        if (available(context) && sessionRunning()) sessionActive(context) else stop()
    }

    /** Gives the keys back to the unit: at session end, when the driver turns the link off, or when no session runs. */
    @Synchronized fun stop() {
        current?.stop()
        current = null
    }

    @Synchronized private fun stop(link: Link) {
        if (current === link) stop()
    }

    private fun sessionRunning(): Boolean =
        CarPlayBackgroundSession.snapshot()?.controller?.activeAirPlaySessionToken() != null

    /** One session's link. Its state lives on its own thread, so a link that is stopping never touches the next. */
    private class Link(private val app: Context) {
        private val thread = HandlerThread("tarmac-fyt-keys").apply { start() }
        private val worker = Handler(thread.looper)
        private val main = Handler(Looper.getMainLooper())
        private var helper: Process? = null
        private var input: OutputStreamWriter? = null
        private var module: IBinder? = null
        private var bound = false

        private val callback = FytModuleIpc.callback { code, ints ->
            val key = ints?.firstOrNull()
            if (code == FytSyuPlatform.U_LINK_KEY && key != null) worker.post { onKey(key) }
        }

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val toolkit = service ?: return
                worker.post {
                    if (module != null) return@post
                    val remote = runCatching { FytModuleIpc.mainModule(toolkit) }.getOrNull() ?: return@post
                    // Not the current value: that would replay the last key forwarded.
                    runCatching { FytModuleIpc.register(remote, callback, FytSyuPlatform.U_LINK_KEY, sendCurrent = false) }
                        .onSuccess {
                            module = remote
                            // Only now may com.syu.ms hand the keys over: someone is there to take them.
                            if (helper == null) openGate()
                        }
                        .onFailure { error -> Log.w(TAG, "link keys: register failed", error) }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // com.syu.ms went away with Tarmac's registration: give the keys back until it is registered again.
                worker.post {
                    module = null
                    closeGate()
                }
            }
        }

        private val watch = object : Runnable {
            override fun run() {
                if (sessionRunning()) worker.postDelayed(this, WATCH_MILLIS) else stop(this@Link)
            }
        }

        fun start() {
            worker.post { open() }
            worker.postDelayed(watch, WATCH_MILLIS)
        }

        fun stop() {
            worker.removeCallbacksAndMessages(null)
            worker.post {
                close()
                thread.quitSafely()
            }
        }

        private fun open() {
            bound = runCatching { app.bindService(FytModuleIpc.toolkitIntent(), connection, Context.BIND_AUTO_CREATE) }
                .onFailure { Log.w(TAG, "link keys: module service unavailable", it) }.getOrDefault(false)
        }

        private fun openGate() {
            val command = FytKeyGateTool.command(app.applicationInfo.sourceDir, android.os.Process.myPid(), LINK_TYPE)
            val process = runCatching { RootShell.start(command) }
                .onFailure { Log.w(TAG, "link keys: no root helper", it) }.getOrNull() ?: return
            helper = process
            input = OutputStreamWriter(process.outputStream)
            Thread({
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        if (line.startsWith(FytKeyGateTool.HEADER)) Log.i(TAG, "link keys: $line")
                    }
                }
            }, "tarmac-fyt-keys-output").apply { isDaemon = true; start() }
            WheelKeyLog.note("FYT keys linked to CarPlay (root)")
        }

        private fun close() {
            closeGate()
            module?.let { remote -> runCatching { FytModuleIpc.unregister(remote, callback, FytSyuPlatform.U_LINK_KEY) } }
            module = null
            if (bound) runCatching { app.unbindService(connection) }
            bound = false
        }

        private fun closeGate() {
            val process = helper ?: return
            helper = null
            runCatching { input?.apply { write("stop\n"); flush() } }
            input = null
            val deadline = SystemClock.uptimeMillis() + STOP_WAIT_MILLIS
            while (process.isAliveCompat() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            if (process.isAliveCompat()) {
                process.destroy()
                // The helper did not finish: put the property back only if it still holds Tarmac's type.
                RootShell.run("[ \"$(getprop ${FytKeyGateTool.PROPERTY})\" = $LINK_TYPE ] && " +
                    "setprop ${FytKeyGateTool.PROPERTY} 0", STOP_WAIT_MILLIS)
            }
            WheelKeyLog.note("FYT keys given back to the unit")
        }

        private fun onKey(code: Int) {
            when (val key = FytLinkKeys.forKey(code) ?: return) {
                is FytLinkKey.CarPlay -> main.post {
                    val controller = CarPlayBackgroundSession.snapshot()?.controller
                    val sent = when (key.action) {
                        HeadUnitKeyAction.SIRI -> controller?.requestSiri()
                        HeadUnitKeyAction.ANSWER_CALL -> controller?.answerCall()
                        HeadUnitKeyAction.END_CALL -> controller?.endCall()
                    } == true
                    Log.i(TAG, "link key $code: ${key.action} sent=$sent")
                    WheelKeyLog.note("FYT key $code -> CarPlay ${key.action} sent=$sent")
                }
                is FytLinkKey.Media -> main.post {
                    val audio = app.getSystemService(AudioManager::class.java) ?: return@post
                    for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                        audio.dispatchMediaKeyEvent(KeyEvent(action, key.keyCode))
                    }
                }
                is FytLinkKey.System -> runCatching { input?.apply { write("key ${key.keyCode}\n"); flush() } }
            }
        }

        /** `Process.isAlive` is API 26; exitValue throws while the process runs. */
        private fun Process.isAliveCompat(): Boolean = runCatching { exitValue() }.isFailure
    }
}
