package com.shilapi.xcertplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.session.MediaSession
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/**
 * Hands CarPlay's music to Android as an ordinary media app would: a media-style notification on
 * CarPlay's media session, so the notification shade, the lock screen and launcher music widgets
 * that follow media notifications show the song and its controls.
 *
 * It is posted again only when the track or the play state changes. The iPhone sends the position
 * twice a second, and the position belongs in the session's playback state (see [CarPlayMediaKeys]).
 */
internal object CarPlayMediaNotification {
    private const val TAG = "DiPlay-MediaNotify"
    private const val CHANNEL = "diplay_now_playing"
    private const val ID = 2
    /** Large enough for the shade's cover, small enough to cross system_server cheaply. */
    private const val MAX_ICON_PIXELS = 192
    private var lastPosted: List<Any?>? = null

    @Synchronized
    fun update(context: Context, token: MediaSession.Token, info: CarPlayNowPlaying, artwork: Bitmap?, playing: Boolean) {
        val title = info.title?.takeIf { it.isNotBlank() }
        val artist = info.artist?.takeIf { it.isNotBlank() }
        if (title == null && artist == null) {
            cancel(context)
            return
        }
        val key = listOf(title, artist, info.album, info.sourceApp, artwork?.let(System::identityHashCode), playing)
        if (key == lastPosted) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        lastPosted = key
        val builder = channelBuilder(context, manager, CHANNEL, R.string.notification_channel_now_playing)
        fun action(icon: Int, label: Int, button: Int) = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(context, icon),
            context.getString(label),
            PendingIntent.getBroadcast(
                context, button,
                Intent(context, CarPlayMediaActionReceiver::class.java).putExtra(CarPlayMediaActionReceiver.EXTRA_BUTTON, button),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()
        builder
            .setSmallIcon(R.drawable.ic_dp_music)
            .setContentTitle(title ?: artist)
            .setContentText(if (title == null) info.album else artist)
            .setSubText(info.sourceApp ?: "CarPlay")
            .setContentIntent(openCarPlay(context))
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            // Paused music can be swiped away, as with any media app; playing music stays.
            .setOngoing(playing)
            .addAction(action(R.drawable.ic_dp_skip_previous, R.string.home_media_previous, CarPlayMediaButton.PREVIOUS))
            .addAction(
                if (playing) action(R.drawable.ic_dp_pause, R.string.home_media_play_pause, CarPlayMediaButton.PAUSE)
                else action(R.drawable.ic_dp_play, R.string.home_media_play_pause, CarPlayMediaButton.PLAY),
            )
            .addAction(action(R.drawable.ic_dp_skip_next, R.string.home_media_next, CarPlayMediaButton.NEXT))
            .setStyle(Notification.MediaStyle().setMediaSession(token).setShowActionsInCompactView(0, 1, 2))
        artwork?.takeUnless { it.isRecycled }?.let { cover ->
            runCatching { scaled(cover) }.getOrNull()?.let(builder::setLargeIcon)
        }
        notify(manager, ID, builder.build())
    }

    @Synchronized
    fun cancel(context: Context) {
        if (lastPosted == null) return
        lastPosted = null
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID) }
    }

    private fun scaled(cover: Bitmap): Bitmap {
        val largest = maxOf(cover.width, cover.height)
        if (largest <= MAX_ICON_PIXELS) return cover.copy(cover.config ?: Bitmap.Config.ARGB_8888, false)
        val scale = MAX_ICON_PIXELS.toFloat() / largest
        return Bitmap.createScaledBitmap(cover, (cover.width * scale).toInt().coerceAtLeast(1),
            (cover.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun notify(manager: NotificationManager, id: Int, notification: Notification) {
        // Without notification permission (Android 13+) Android drops it; CarPlay itself is unaffected.
        runCatching { manager.notify(id, notification) }.onFailure { Log.i(TAG, "media notification not posted", it) }
    }
}

/** The previous, play, pause and next buttons of [CarPlayMediaNotification]. */
class CarPlayMediaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val button = intent.getIntExtra(EXTRA_BUTTON, -1)
        if (button in CarPlayMediaButton.PLAY..CarPlayMediaButton.PREVIOUS) {
            CarPlayMediaKeys.onAppMediaKey(button, "notification")
        }
    }

    companion object {
        const val EXTRA_BUTTON = "com.shihab.diplay.extra.MEDIA_BUTTON"
    }
}

/**
 * CarPlay's next turn as an Android navigation notification, the way phone navigation apps publish
 * guidance: arrow, distance, road and arrival. Launchers, dashboards and watch bridges that read
 * navigation notifications can show CarPlay's route as they show any other.
 */
internal object CarPlayGuidanceNotification {
    private const val TAG = "DiPlay-GuidanceNotify"
    /**
     * Normal importance, so the turn sits in the main notification list and the status bar; low importance
     * hid it in Samsung's collapsed Silent section. The channel makes no sound and does not vibrate.
     * Android never raises an existing channel's importance, hence a new id; the old one is removed.
     */
    private const val CHANNEL = "diplay_guidance_visible"
    private const val OLD_CHANNEL = "diplay_guidance"
    private const val ID = 3
    /** Notification.CATEGORY_NAVIGATION, a plain string on every Android version. */
    private const val CATEGORY_NAVIGATION = "navigation"
    /** If DiPlay stops without clearing it, Android removes the turn after this long. */
    private const val TIMEOUT_MILLIS = 90_000L
    private const val REFRESH_MILLIS = 30_000L
    private var lastPosted: List<Any?>? = null

    /** From the widget worker, at most once a second while a route is shown. */
    @Synchronized
    fun update(context: Context, glance: CarPlayGlance.Snapshot) {
        val type = glance.maneuverType
        if (!glance.connected || type == null) {
            cancel(context)
            return
        }
        val arrow = NavigationWidgetUpdater.arrow(type, glance.drivingSide)
        val distance = NavigationWidgetUpdater.distance(context, glance.distanceMeters.toLong())
        val eta = NavigationWidgetUpdater.eta(context, glance)
        // A long straight road changes nothing for minutes; repost before the timeout removes it.
        val key = listOf(arrow, distance, glance.road, eta, SystemClock.elapsedRealtime() / REFRESH_MILLIS)
        if (key == lastPosted) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        lastPosted = key
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { manager.deleteNotificationChannel(OLD_CHANNEL) }
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                context.getString(R.string.notification_channel_guidance), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        val builder = channelBuilder(context, manager, CHANNEL, R.string.notification_channel_guidance)
            .setSmallIcon(arrow)
            .setContentTitle(distance)
            .setContentText(glance.road.ifBlank { "CarPlay" })
            .setContentIntent(openCarPlay(context))
            .setCategory(CATEGORY_NAVIGATION)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (eta.isNotEmpty()) builder.setSubText(eta)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setTimeoutAfter(TIMEOUT_MILLIS)
        runCatching { manager.notify(ID, builder.build()) }.onFailure { Log.i(TAG, "guidance notification not posted", it) }
    }

    @Synchronized
    fun cancel(context: Context) {
        if (lastPosted == null) return
        lastPosted = null
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID) }
    }
}

/** "Your iPhone is here": the tap that starts CarPlay when Android will not let Tarmac open by itself. */
internal object AutoConnectNotification {
    private const val CHANNEL = "diplay_auto_connect"
    private const val ID = 4
    private const val TIMEOUT_MILLIS = 120_000L

    fun post(context: Context, launch: Intent, phone: String?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // High importance, so it appears over whatever is on screen when the iPhone arrives.
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                context.getString(R.string.notification_channel_auto_connect), NotificationManager.IMPORTANCE_HIGH))
            Notification.Builder(context, CHANNEL).setTimeoutAfter(TIMEOUT_MILLIS)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH)
        }
        val open = PendingIntent.getActivity(context, ID, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = builder
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle(context.getString(R.string.notification_auto_connect_title, phone?.takeIf { it.isNotBlank() } ?: "iPhone"))
            .setContentText(context.getString(R.string.notification_auto_connect_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .build()
        runCatching { manager.notify(ID, notification) }
    }
}

private fun channelBuilder(context: Context, manager: NotificationManager, channel: String, name: Int): Notification.Builder =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(channel) != null) {
        Notification.Builder(context, channel)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // Low importance: no sound and no pop-up over CarPlay. Drivers can turn either channel off in Android.
        manager.createNotificationChannel(NotificationChannel(channel, context.getString(name), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        Notification.Builder(context, channel)
    } else {
        // Android 7.x has no notification channels; the priority stands in for the channel importance.
        @Suppress("DEPRECATION")
        Notification.Builder(context).setPriority(Notification.PRIORITY_LOW)
    }

private fun openCarPlay(context: Context): PendingIntent = PendingIntent.getActivity(
    context, 0, CarPlayScreen.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)
