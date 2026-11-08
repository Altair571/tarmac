// SPDX-License-Identifier: AGPL-3.0-or-later
package com.shilapi.xcertplay.androidauto

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.shilapi.xcertplay.androidauto.aap.AapSession
import com.shilapi.xcertplay.androidauto.aap.AapTls
import com.shilapi.xcertplay.androidauto.aap.AndroidAutoNowPlaying
import com.shilapi.xcertplay.androidauto.aap.AndroidAutoPlayState
import com.shilapi.xcertplay.androidauto.aap.HeadUnitDescription
import com.shilapi.xcertplay.androidauto.aap.TouchPointer
import com.shilapi.xcertplay.androidauto.location.AndroidAutoLocation
import com.shilapi.xcertplay.androidauto.media.AndroidAutoAudio
import com.shilapi.xcertplay.androidauto.media.AndroidAutoMicrophone
import com.shilapi.xcertplay.androidauto.media.AndroidAutoVideo
import com.shilapi.xcertplay.androidauto.usb.AndroidAutoUsb
import com.shilapi.xcertplay.androidauto.usb.AndroidAutoUsbKind
import com.shilapi.xcertplay.androidauto.wireless.AndroidAutoStartNotification
import com.shilapi.xcertplay.androidauto.wireless.AndroidAutoWireless
import com.shilapi.xcertplay.media.DriverSideAudio
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.UsbPermissionBroadcast
import java.io.Closeable
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/**
 * The app's one Android Auto connection: finds the phone, switches it to accessory mode (or, for
 * wireless, waits for it over Bluetooth and Wi-Fi), runs the session and routes its video, audio
 * and microphone. It lives as long as the process; while it runs, [AndroidAutoService] keeps the
 * process in the foreground so sound continues when the projection screen is in the background.
 *
 * Time to the first picture is what the driver feels, so the slow parts overlap: the decoder and
 * the TLS context are made while the phone is still switching, the accessory is picked up from
 * Android's attach broadcast rather than a polling interval, and the TLS session is kept so the
 * next connection can resume it. Each connection logs its timeline.
 */
object AndroidAutoProjection {
    enum class Stage {
        IDLE, NO_USB_HOST, NO_IDENTITY, WAITING_FOR_PHONE, PERMISSION, SWITCHING,
        /** Wireless: the Android Auto record is published and the phone has not opened it yet. */
        WAITING_FOR_WIRELESS_PHONE,
        /** Wireless: the phone has the credentials and is joining the head unit's Wi-Fi. */
        JOINING_WIFI,
        CONNECTING, PROJECTING, RECONNECTING, ENDED,
    }

    /** [failure] says why a session ended or will be retried; [retryInSeconds] is set while [Stage.RECONNECTING]. */
    class Status(
        val stage: Stage,
        val failure: AndroidAutoFailure? = null,
        val detail: String? = null,
        val retryInSeconds: Int = 0,
        /** The phone ended Android Auto itself. */
        val endedByPhone: Boolean = false,
    )

    /** The projection screen. Called on the main thread. */
    interface Screen {
        fun onStatus(status: Status)
        /** The driver chose "exit" inside Android Auto. */
        fun onExitRequested()
    }

    private val main = Handler(Looper.getMainLooper())
    private val connector = Executors.newSingleThreadExecutor { Thread(it, "AndroidAuto-connect") }
    /** Touches, keys and focus changes from the screen, in order and off the main thread: each is a USB write. */
    private val sender = Executors.newSingleThreadExecutor { Thread(it, "AndroidAuto-input").apply { isDaemon = true } }
    private var screen: Screen? = null
    @Volatile var status = Status(Stage.IDLE)
        private set
    // Holds only the application context, which lives as long as this object.
    @SuppressLint("StaticFieldLeak")
    @Volatile private var connection: Connection? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var stoppedByDriver = false
    @Volatile private var reconnectAttempt = 0
    private val retry = Runnable { appContext?.let { if (wireless) startWireless(it) else start(it) } }
    @Volatile private var appContext: Context? = null
    /** The transport of the last start, which a retry repeats. */
    @Volatile var wireless = false
        private set
    /** A wireless attempt still waiting for the phone; [stop] cancels it. Holds only the application context. */
    @SuppressLint("StaticFieldLeak")
    @Volatile private var pendingWireless: AndroidAutoWireless? = null
    private var tlsContext: Pair<X509Certificate, SSLContext>? = null
    @Volatile var nowPlaying: AndroidAutoNowPlaying? = null
        private set
    @Volatile var playState = AndroidAutoPlayState.STOPPED
        private set

    /** Whether a session is connecting or running. */
    val active: Boolean get() = status.stage in setOf(Stage.PERMISSION, Stage.SWITCHING, Stage.WAITING_FOR_WIRELESS_PHONE,
        Stage.JOINING_WIFI, Stage.CONNECTING, Stage.PROJECTING)

    fun attach(screen: Screen) {
        this.screen = screen
        screen.onStatus(status)
    }

    fun detach(screen: Screen) {
        if (this.screen === screen) this.screen = null
    }

    /**
     * Connects [device], or the first Android phone on the bus. Does nothing while a session runs.
     * [since] is when the driver plugged in or asked, the start of the measured connection time.
     */
    fun start(context: Context, device: UsbDevice? = null, since: Long = SystemClock.elapsedRealtime()) {
        if (active) return
        main.removeCallbacks(retry)
        stoppedByDriver = false
        wireless = false
        val app = context.applicationContext
        appContext = app
        publish(Status(Stage.CONNECTING))
        connector.execute { connect(app, device, Timeline(since)) }
    }

    /**
     * Waits for a phone to start wireless Android Auto over Bluetooth, then connects over Wi-Fi.
     * Does nothing while a session runs. [since] is when the phone connected or the driver asked.
     */
    fun startWireless(context: Context, since: Long = SystemClock.elapsedRealtime()) {
        if (active) return
        main.removeCallbacks(retry)
        stoppedByDriver = false
        wireless = true
        val app = context.applicationContext
        appContext = app
        publish(Status(Stage.CONNECTING))
        connector.execute { connectWireless(app, Timeline(since)) }
    }

    /** The driver closed Android Auto: end the session and do not reconnect. */
    fun stop() {
        stoppedByDriver = true
        main.removeCallbacks(retry)
        reconnectAttempt = 0
        if (status.stage == Stage.RECONNECTING) publish(Status(Stage.IDLE))
        pendingWireless?.close()
        val running = connection ?: return
        sender.execute { running.close() }
    }

    /** The screen's surface, or null when it goes; returns once the decoder has let go of the old one. */
    fun setSurface(surface: Surface?) {
        this.surface = surface
        val running = connection ?: return
        running.video.setSurface(surface)
        sender.execute { runCatching { running.session.setSurfaceReady(surface != null) } }
    }

    /** One touch report; [pointers] are (id, x, y) in view pixels on a [viewWidth] × [viewHeight] view. */
    fun touch(action: Int, actionIndex: Int, pointers: List<Triple<Int, Float, Float>>, viewWidth: Int, viewHeight: Int) {
        val running = connection ?: return
        val points = pointers.map { (id, x, y) ->
            val (videoX, videoY) = running.geometry.toVideo(x, y, viewWidth, viewHeight)
            TouchPointer(id, videoX, videoY)
        }
        sender.execute { runCatching { running.session.sendTouch(action, actionIndex, points) } }
    }

    /** An Android key code from the head unit, such as a steering-wheel media key. */
    fun key(keycode: Int, down: Boolean) {
        val running = connection ?: return
        sender.execute { runCatching { running.session.sendKey(keycode, down) } }
    }

    /** Sends the night state the driver's choice gives now, after a setting or day/night change. */
    fun refreshNight(context: Context) {
        val running = connection ?: return
        val night = night(context.applicationContext)
        sender.execute { runCatching { running.session.setNight(night) } }
    }

    /** The geometry of the running session, for the screen's picture layout. */
    val geometry: AndroidAutoGeometry? get() = connection?.geometry

    private fun connect(context: Context, requested: UsbDevice?, timeline: Timeline) {
        var prepared: Connection? = null
        try {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                publish(Status(Stage.NO_USB_HOST))
                return
            }
            val identity = HeadUnitIdentity.load(context) ?: run {
                publish(Status(Stage.NO_IDENTITY))
                return
            }
            val manager = context.getSystemService(UsbManager::class.java)
                ?: throw AndroidAutoException(AndroidAutoFailure.USB, "no USB service")
            var device = requested?.takeIf { AndroidAutoUsb.classify(it) != AndroidAutoUsbKind.OTHER }
                ?: AndroidAutoUsb.find(manager)
                ?: run {
                    publish(Status(Stage.WAITING_FOR_PHONE))
                    return
                }
            // The decoder and audio start now, on their own threads, while the phone switches.
            val geometry = geometry(context)
            val tls = tlsContext(identity)
            prepared = Connection(context, geometry, timeline) { AapTls(tls) }
            if (AndroidAutoUsb.classify(device) == AndroidAutoUsbKind.PHONE) {
                requirePermission(context, manager, device)
                timeline.mark("permission")
                publish(Status(Stage.SWITCHING))
                device = switchToAccessory(context, manager, device)
                timeline.mark("accessory")
            }
            requirePermission(context, manager, device, settleMillis = SYSTEM_GRANT_SETTLE_MILLIS)
            publish(Status(Stage.CONNECTING))
            val stream = AndroidAutoUsb.openAccessory(manager, device)
            timeline.mark("open")
            val running = prepared
            try {
                val location = AndroidAutoSettings.gpsSource(context) == AndroidAutoGpsSource.HEAD_UNIT && running.location.available()
                running.open(stream, description(context, geometry, running.microphone.available(), location))
            } catch (error: Exception) {
                stream.close()
                throw error
            }
            if (stoppedByDriver) {
                // Closed while the phone was still connecting.
                stream.close()
                running.release()
                prepared = null
                publish(Status(Stage.IDLE))
                return
            }
            connection = running
            prepared = null
            running.setSurface(surface)
            AndroidAutoService.start(context)
            running.session.start()
        } catch (error: Exception) {
            AndroidAutoLog.w("Android Auto connection failed", error)
            prepared?.release()
            val failure = AndroidAutoFailure.of(error) ?: AndroidAutoFailure.OTHER
            main.post { ended(context, failure, error.message, endedByPhone = false, healthy = false) }
        }
    }

    private fun connectWireless(context: Context, timeline: Timeline) {
        var prepared: Connection? = null
        var link: AndroidAutoWireless.Link? = null
        try {
            val identity = HeadUnitIdentity.load(context) ?: run {
                publish(Status(Stage.NO_IDENTITY))
                return
            }
            if (AndroidAuto.carPlayActive()) throw AndroidAutoException(AndroidAutoFailure.CARPLAY_RUNNING, "CarPlay is running")
            val geometry = geometry(context)
            val tls = tlsContext(identity)
            prepared = Connection(context, geometry, timeline) { AapTls(tls) }
            // Keeps the process up while the phone takes its time; a Bluetooth broadcast may start it from the background.
            AndroidAutoService.start(context)
            val attempt = AndroidAutoWireless(context)
            pendingWireless = attempt
            if (stoppedByDriver) attempt.close()
            link = attempt.connect { stage ->
                publish(Status(when (stage) {
                    AndroidAutoWireless.Stage.WAITING_FOR_PHONE -> Stage.WAITING_FOR_WIRELESS_PHONE
                    AndroidAutoWireless.Stage.JOINING -> Stage.JOINING_WIFI
                }))
            }
            pendingWireless = null
            timeline.mark("wifi")
            publish(Status(Stage.CONNECTING))
            val running = prepared
            val location = AndroidAutoSettings.gpsSource(context) == AndroidAutoGpsSource.HEAD_UNIT && running.location.available()
            running.open(link.stream, description(context, geometry, running.microphone.available(), location))
            running.transport = link
            running.wirelessPhone = link.phoneAddress
            link = null
            if (stoppedByDriver) {
                running.release()
                prepared = null
                AndroidAutoService.stop(context)
                publish(Status(Stage.IDLE))
                return
            }
            connection = running
            prepared = null
            running.setSurface(surface)
            // Again, so the service adds the microphone now that a session runs.
            AndroidAutoService.start(context)
            running.session.start()
        } catch (error: Exception) {
            pendingWireless = null
            link?.close()
            prepared?.release()
            AndroidAutoService.stop(context)
            // A cancelled attempt fails with whatever its closed sockets threw; the driver asked for it.
            if (stoppedByDriver) {
                publish(Status(Stage.IDLE))
                return
            }
            AndroidAutoLog.w("wireless Android Auto failed", error)
            val failure = AndroidAutoFailure.of(error) ?: AndroidAutoFailure.OTHER
            main.post { ended(context, failure, error.message, endedByPhone = false, healthy = false) }
        }
    }

    /** Switches [device] and returns the accessory it becomes, from the attach broadcast as soon as it comes. */
    private fun switchToAccessory(context: Context, manager: UsbManager, device: UsbDevice): UsbDevice {
        val attached = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = attached.countDown()
        }
        registerExported(context, receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED))
        try {
            AndroidAutoUsb.switchToAccessory(manager, device)
            val deadline = SystemClock.elapsedRealtime() + REENUMERATION_TIMEOUT_MILLIS
            while (true) {
                manager.deviceList.values.firstOrNull { AndroidAutoUsb.classify(it) == AndroidAutoUsbKind.ACCESSORY }?.let { return it }
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                // Wakes on the broadcast; the short cap covers firmware that sends none.
                attached.await(minOf(left, REENUMERATION_FALLBACK_POLL_MILLIS), TimeUnit.MILLISECONDS)
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
        throw AndroidAutoException(AndroidAutoFailure.NO_SWITCH, "the phone did not come back as an Android Auto accessory")
    }

    /**
     * Asks for USB access unless it is already granted. [settleMillis] first gives Android's own
     * "open with" grant for a just-attached accessory a moment, so the driver sees one prompt, not two.
     */
    private fun requirePermission(context: Context, manager: UsbManager, device: UsbDevice, settleMillis: Long = 0) {
        val settled = SystemClock.elapsedRealtime() + settleMillis
        while (!manager.hasPermission(device) && SystemClock.elapsedRealtime() < settled) Thread.sleep(PERMISSION_CHECK_MILLIS)
        if (manager.hasPermission(device)) return
        publish(Status(Stage.PERMISSION))
        val answered = CountDownLatch(1)
        val action = "${context.packageName}.ANDROID_AUTO_USB_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = answered.countDown()
        }
        UsbPermissionBroadcast.register(context, receiver, IntentFilter(action))
        try {
            // Mutable so Android can add the result; the package keeps it to this app.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            manager.requestPermission(device, PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), flags))
            answered.await(PERMISSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
        if (!manager.hasPermission(device)) throw AndroidAutoException(AndroidAutoFailure.PERMISSION_DENIED, "USB access to the phone was not allowed")
    }

    /** The system's attach broadcast comes from outside the app, so this receiver must be exported. */
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // The flags exist from Android 13, the branch above.
    private fun registerExported(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    @Synchronized
    private fun tlsContext(identity: HeadUnitIdentity): SSLContext {
        tlsContext?.takeIf { it.first == identity.certificate }?.let { return it.second }
        return AapTls.context(identity.certificate, identity.privateKey).also { tlsContext = identity.certificate to it }
    }

    private fun geometry(context: Context): AndroidAutoGeometry {
        val metrics = context.resources.displayMetrics
        // The projection screen runs in landscape; take the long side as its width.
        val width = maxOf(metrics.widthPixels, metrics.heightPixels)
        val height = minOf(metrics.widthPixels, metrics.heightPixels)
        return AndroidAutoGeometry.choose(width, height, metrics.xdpi, AndroidAutoSettings.resolution(context), AndroidAutoSettings.density(context))
    }

    private fun description(context: Context, geometry: AndroidAutoGeometry, microphone: Boolean, location: Boolean): HeadUnitDescription {
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        return HeadUnitDescription(
            resolution = geometry.resolution,
            sixtyFps = AndroidAutoSettings.sixtyFps(context),
            marginWidth = geometry.marginWidth,
            marginHeight = geometry.marginHeight,
            density = geometry.density,
            microphone = microphone,
            location = location,
            rightHandDrive = AndroidAutoSettings.rightHandDrive(context),
            make = label,
            model = Build.MODEL.orEmpty(),
            year = Calendar.getInstance().get(Calendar.YEAR).toString(),
            vehicleId = AndroidAutoSettings.vehicleId(context),
            headUnitMake = Build.MANUFACTURER.orEmpty(),
            headUnitModel = Build.MODEL.orEmpty(),
            softwareVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty(),
        )
    }

    private fun night(context: Context): Boolean = when (AndroidAutoSettings.night(context)) {
        AndroidAutoNight.DAY -> false
        AndroidAutoNight.NIGHT -> true
        AndroidAutoNight.FOLLOW_ANDROID ->
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    /** After a session or a connection attempt ends: report it, and try again if that can help. */
    private fun ended(context: Context, failure: AndroidAutoFailure?, detail: String?, endedByPhone: Boolean, healthy: Boolean) {
        if (healthy) reconnectAttempt = 0
        // Over USB only a phone still plugged in is retried; over Bluetooth there is no such test.
        val delay = if (stoppedByDriver || !AndroidAutoSettings.reconnect(context) || (!wireless && !phoneAttached(context))) null
        else AndroidAutoReconnect.delayMillis(failure, reconnectAttempt)
        if (delay == null) {
            reconnectAttempt = 0
            publish(Status(Stage.ENDED, failure, detail, endedByPhone = endedByPhone))
            return
        }
        reconnectAttempt++
        AndroidAutoLog.i("reconnecting in $delay ms after $failure (attempt $reconnectAttempt)")
        publish(Status(Stage.RECONNECTING, failure, detail, retryInSeconds = ((delay + 999) / 1000).toInt()))
        main.postDelayed(retry, delay)
    }

    private fun phoneAttached(context: Context): Boolean =
        context.getSystemService(UsbManager::class.java)?.let { AndroidAutoUsb.find(it) } != null

    private fun publish(next: Status) {
        status = next
        main.post { screen?.onStatus(next) }
        // The service's notification says whether the phone is still on its way.
        AndroidAutoService.refresh()
    }

    /** Milestones of one connection, from plug-in to the first picture, for the log and Settings. */
    private class Timeline(private val since: Long) {
        private val marks = StringBuilder()

        @Synchronized
        fun mark(name: String): Long {
            val elapsed = SystemClock.elapsedRealtime() - since
            marks.append(if (marks.isEmpty()) "" else ", ").append(name).append(' ').append(elapsed).append(" ms")
            return elapsed
        }

        @Synchronized
        override fun toString() = marks.toString()
    }

    private class Connection(
        private val context: Context,
        val geometry: AndroidAutoGeometry,
        private val timeline: Timeline,
        private val tls: () -> AapTls,
    ) : AapSession.Listener {
        val video = AndroidAutoVideo(geometry.resolution.width, geometry.resolution.height, ::firstFrame)
        val audio = AndroidAutoAudio(context, AndroidAutoSettings.separateStreams(context),
            DriverSideAudio.side(context))
        val microphone = AndroidAutoMicrophone(context) { pcm, length, timestamp -> session.sendMicrophone(pcm, length, timestamp) }
        val location = AndroidAutoLocation(context) { event -> sender.execute { runCatching { session.sendLocation(event) } } }
        lateinit var session: AapSession
        /** The wireless link's network and Bluetooth channel, freed with the session. */
        var transport: Closeable? = null
        /** The phone's Bluetooth address, remembered for Bluetooth start once the session is up. */
        var wirelessPhone: String? = null
        private var projectingSince = 0L

        fun open(stream: BlockingDuplexByteStream, description: HeadUnitDescription) {
            session = AapSession(stream, tls, description, this)
            session.setNight(night(context))
        }

        fun setSurface(surface: Surface?) {
            video.setSurface(surface)
            session.setSurfaceReady(surface != null)
        }

        fun close() = session.close()

        /** Frees what was made ahead of a connection that never started its session. */
        fun release() {
            location.stop()
            audio.release()
            video.release()
            transport?.let { runCatching { it.close() } }
            transport = null
        }

        private fun firstFrame() {
            val millis = timeline.mark("first frame")
            AndroidAutoLog.i("Android Auto timeline: $timeline")
            AndroidAutoSettings.setLastConnectMillis(context, millis)
        }

        override fun onVersion() {
            timeline.mark("version")
        }

        override fun onConnected() {
            timeline.mark("tls")
            projectingSince = SystemClock.elapsedRealtime()
            // A phone that got this far over Bluetooth is one Bluetooth start may start again.
            wirelessPhone?.let { AndroidAutoSettings.rememberWirelessPhone(context, it) }
            AndroidAutoStartNotification.cancel(context)
            publish(Status(Stage.PROJECTING))
        }

        override fun onVideoConfig(codecConfig: ByteArray) = video.onCodecConfig(codecConfig)
        override fun onVideoFrame(frame: ByteArray, timestampMicros: Long) = video.onFrame(frame, timestampMicros)
        override fun onVideoFocusRequest(projected: Boolean) {
            if (!projected) main.post { screen?.onExitRequested() }
        }
        override fun onAudioStart(channel: Int) = audio.start(channel)
        override fun onAudio(channel: Int, pcm: ByteArray, offset: Int, length: Int) = audio.write(channel, pcm, offset, length)
        override fun onAudioStop(channel: Int) = audio.stop(channel)
        override fun onMicrophone(open: Boolean) = if (open) microphone.start() else microphone.stop()
        override fun onLocationRequested() {
            location.start()
        }
        override fun onNowPlaying(nowPlaying: AndroidAutoNowPlaying) {
            AndroidAutoProjection.nowPlaying = nowPlaying
            AndroidAutoService.refresh()
        }
        override fun onPlayState(state: AndroidAutoPlayState) {
            if (playState == state) return
            playState = state
            AndroidAutoService.refresh()
        }

        override fun onEnded(reason: String, error: Throwable?) {
            microphone.stop()
            release()
            if (connection === this) connection = null
            AndroidAutoProjection.nowPlaying = null
            playState = AndroidAutoPlayState.STOPPED
            AndroidAutoService.stop(context)
            val healthy = projectingSince > 0 &&
                SystemClock.elapsedRealtime() - projectingSince >= AndroidAutoReconnect.HEALTHY_SESSION_MILLIS
            main.post {
                ended(context, AndroidAutoFailure.of(error), reason, endedByPhone = error == null && !stoppedByDriver, healthy = healthy)
            }
        }
    }

    private const val PERMISSION_TIMEOUT_SECONDS = 60L
    private const val PERMISSION_CHECK_MILLIS = 50L
    private const val SYSTEM_GRANT_SETTLE_MILLIS = 600L
    private const val REENUMERATION_TIMEOUT_MILLIS = 8_000L
    private const val REENUMERATION_FALLBACK_POLL_MILLIS = 250L
}
