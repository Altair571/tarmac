package com.shilapi.xcertplay

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.session.MediaSession
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CarPlaySystemNotificationsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val posted get() = shadowOf(manager).allNotifications

    @After fun tearDown() {
        CarPlayGuidanceNotification.cancel(context)
        CarPlayMediaNotification.cancel(context)
    }

    private fun turn(road: String = "Main Street") = CarPlayGlance.Snapshot(
        connected = true, maneuverType = 1, distanceMeters = 300, road = road, remainingSeconds = 600,
    )

    @Test fun theNextTurnIsANavigationNotification() {
        CarPlayGuidanceNotification.update(context, turn())
        val notification = posted.single()
        assertEquals("navigation", notification.category)
        assertEquals("300 m", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Main Street", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("10 min", notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        // In the main list and the status bar, but silent.
        assertEquals("diplay_guidance_visible", notification.channelId)
        val channel = manager.getNotificationChannel("diplay_guidance_visible")
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals(null, channel.sound)
        assertFalse(channel.shouldVibrate())
    }

    @Test fun theTurnGoesAwayWithTheRouteAndWithTheConnection() {
        CarPlayGuidanceNotification.update(context, turn())
        CarPlayGuidanceNotification.update(context, CarPlayGlance.Snapshot(connected = true))
        assertTrue(posted.isEmpty())
        CarPlayGuidanceNotification.update(context, turn())
        CarPlayGuidanceNotification.update(context, turn().copy(connected = false))
        assertTrue(posted.isEmpty())
    }

    @Test fun theSongIsAMediaNotificationOnCarPlaysSession() {
        val session = MediaSession(context, "test")
        try {
            val song = CarPlayNowPlaying(title = "Song", artist = "Artist", album = "Album", playing = true)
            CarPlayMediaNotification.update(context, session.sessionToken, song, null, playing = true)
            val notification = posted.single()
            assertEquals("Song", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("Artist", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertNotNull(notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java))
            assertEquals(3, notification.actions.size)
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)

            // Paused music can be swiped away.
            CarPlayMediaNotification.update(context, session.sessionToken, song.copy(playing = false), null, playing = false)
            assertFalse(posted.single().flags and Notification.FLAG_ONGOING_EVENT != 0)

            // A session without a song shows nothing.
            CarPlayMediaNotification.update(context, session.sessionToken, CarPlayNowPlaying(), null, playing = false)
            assertTrue(posted.isEmpty())
        } finally {
            session.release()
        }
    }
}
