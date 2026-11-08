// SPDX-License-Identifier: AGPL-3.0-or-later
package com.shilapi.xcertplay.androidauto

import android.Manifest
import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import com.shilapi.xcertplay.androidauto.aap.AndroidAutoPlayState
import com.shilapi.xcertplay.androidauto.ui.AndroidAutoActivity

/**
 * Keeps the process in the foreground while Android Auto runs, so its sound goes on when the
 * projection screen is in the background, and while a wireless phone is on its way. Its
 * notification is the phone's music, the way CarPlay's music appears in
 * `CarPlaySystemNotifications.kt`: a media-style notification on a media session, so the shade,
 * the lock screen and launcher music widgets show the song.
 */
class AndroidAutoService : Service() {
    private var session: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
        session = MediaSession(this, "AndroidAuto").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = press(KeyEvent.KEYCODE_MEDIA_PLAY)
                override fun onPause() = press(KeyEvent.KEYCODE_MEDIA_PAUSE)
                override fun onSkipToNext() = press(KeyEvent.KEYCODE_MEDIA_NEXT)
                override fun onSkipToPrevious() = press(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(NOTIFICATION_ID, notification, foregroundTypes())
            } catch (error: SecurityException) {
                // A reconnect or a Bluetooth start runs in the background, where Android 14 refuses the
                // microphone and location types; the connection still needs the process kept up.
                if (foregroundTypes() == ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) throw error
                AndroidAutoLog.w("foreground service without microphone or location", error)
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running === this) running = null
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun repost() {
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification()) }
    }

    /**
     * Connected device always; microphone and location only when they are allowed (and location
     * only when the head unit's GPS feeds Android Auto), or Android 14 refuses the start.
     */
    @TargetApi(Build.VERSION_CODES.Q)
    private fun foregroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (AndroidAutoSettings.gpsSource(this) == AndroidAutoGpsSource.HEAD_UNIT &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        return types
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val playing = AndroidAutoProjection.playState == AndroidAutoPlayState.PLAYING
        val track = AndroidAutoProjection.nowPlaying
        val title = track?.title?.takeIf { it.isNotBlank() }
        val artist = track?.artist?.takeIf { it.isNotBlank() }
        val artwork = track?.artwork?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        session?.apply {
            setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, track?.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (track?.durationSeconds ?: 0) * 1_000L)
                .apply { artwork?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build())
            setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build())
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Low importance: no sound and no pop-up over the projection.
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.aa_notification_channel),
                NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        fun action(icon: Int, label: Int, keycode: Int) = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon),
            getString(label),
            PendingIntent.getBroadcast(this, keycode,
                Intent(this, AndroidAutoMediaActionReceiver::class.java).putExtra(AndroidAutoMediaActionReceiver.EXTRA_KEYCODE, keycode),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        ).build()
        builder
            .setSmallIcon(R.drawable.ic_android_auto)
            .setContentTitle(title ?: getString(when (AndroidAutoProjection.status.stage) {
                AndroidAutoProjection.Stage.PROJECTING -> R.string.aa_notification_connected
                AndroidAutoProjection.Stage.WAITING_FOR_WIRELESS_PHONE, AndroidAutoProjection.Stage.JOINING_WIFI ->
                    R.string.aa_notification_waiting
                else -> R.string.aa_status_connecting
            }))
            .setContentText(if (title == null) null else artist)
            .setSubText(getString(R.string.aa_title))
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, AndroidAutoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (title != null) {
            builder
                .addAction(action(R.drawable.ic_aa_previous, R.string.aa_action_previous, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                .addAction(
                    if (playing) action(R.drawable.ic_aa_pause, R.string.aa_action_play_pause, KeyEvent.KEYCODE_MEDIA_PAUSE)
                    else action(R.drawable.ic_aa_play, R.string.aa_action_play_pause, KeyEvent.KEYCODE_MEDIA_PLAY),
                )
                .addAction(action(R.drawable.ic_aa_next, R.string.aa_action_next, KeyEvent.KEYCODE_MEDIA_NEXT))
                .setStyle(Notification.MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0, 1, 2))
            artwork?.let(builder::setLargeIcon)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "android_auto"
        private const val NOTIFICATION_ID = 41
        @Volatile private var running: AndroidAutoService? = null

        internal fun press(keycode: Int) {
            AndroidAutoProjection.key(keycode, true)
            AndroidAutoProjection.key(keycode, false)
        }

        internal fun start(context: Context) {
            val intent = Intent(context, AndroidAutoService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure { AndroidAutoLog.w("Android Auto service not started", it) }
        }

        /** Posts the notification again with the phone's current track. */
        internal fun refresh() {
            running?.let { service -> android.os.Handler(android.os.Looper.getMainLooper()).post(service::repost) }
        }

        internal fun stop(context: Context) {
            context.stopService(Intent(context, AndroidAutoService::class.java))
        }
    }
}

/** Previous, play, pause and next in the Android Auto notification. */
class AndroidAutoMediaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val keycode = intent.getIntExtra(EXTRA_KEYCODE, -1)
        if (keycode > 0) AndroidAutoService.press(keycode)
    }

    companion object {
        const val EXTRA_KEYCODE = "com.shilapi.xcertplay.androidauto.extra.KEYCODE"
    }
}
