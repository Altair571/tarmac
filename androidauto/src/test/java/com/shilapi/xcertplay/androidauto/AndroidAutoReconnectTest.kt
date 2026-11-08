package com.shilapi.xcertplay.androidauto

import com.shilapi.xcertplay.androidauto.aap.AapIncompatibleVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLException

class AndroidAutoReconnectTest {
    @Test fun backsOffThenStops() {
        val delays = (0 until 6).map { AndroidAutoReconnect.delayMillis(AndroidAutoFailure.DISCONNECTED, it) }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, null), delays)
    }

    /** A declined prompt or a rejected identity needs the driver; retrying would only repeat it. */
    @Test fun failuresThatNeedTheDriverAreNotRetried() {
        listOf(
            AndroidAutoFailure.PERMISSION_DENIED,
            AndroidAutoFailure.NOT_ANDROID_AUTO,
            AndroidAutoFailure.IDENTITY_REJECTED,
            AndroidAutoFailure.INCOMPATIBLE_VERSION,
        ).forEach { assertNull(it.name, AndroidAutoReconnect.delayMillis(it, 0)) }
        // The phone closing Android Auto itself ends with no failure.
        assertNull(AndroidAutoReconnect.delayMillis(null, 0))
    }

    @Test fun errorsMapToFailures() {
        assertEquals(AndroidAutoFailure.SILENT, AndroidAutoFailure.of(AndroidAutoException(AndroidAutoFailure.SILENT, "quiet")))
        assertEquals(AndroidAutoFailure.IDENTITY_REJECTED, AndroidAutoFailure.of(SSLException("bad certificate")))
        assertEquals(AndroidAutoFailure.INCOMPATIBLE_VERSION, AndroidAutoFailure.of(AapIncompatibleVersion()))
        assertEquals(AndroidAutoFailure.OTHER, AndroidAutoFailure.of(IOException("other")))
        assertNull(AndroidAutoFailure.of(null))
    }
}
