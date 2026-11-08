package com.shilapi.xcertplay.headunit

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.transport.I2cTransportException
import com.shilapi.xcertplay.transport.LinuxI2cTransport
import com.shilapi.xcertplay.transport.RootI2cTransport

/**
 * Whether DiPlay may open the head unit's Apple authentication chip. The probe opens and closes the device
 * node and sends nothing on the bus. It blocks; never call it on the main thread.
 */
internal object HeadUnitMfiChip {
    enum class Access { READY, BLOCKED, MISSING, UNKNOWN }

    fun probe(path: String): Access = try {
        LinuxI2cTransport.open(path).close()
        Access.READY
    } catch (error: I2cTransportException.PermissionDenied) {
        // A 0777 node still fails here when SELinux keeps ordinary apps away from it (TEYES: label `device`).
        Access.BLOCKED
    } catch (error: I2cTransportException.DeviceUnavailable) {
        Access.MISSING
    } catch (error: Throwable) {
        Log.w("Tarmac-HeadUnit", "authentication chip probe on $path failed", error)
        Access.UNKNOWN
    }

    /** The same probe through the root helper, for rooted units; the root manager may ask the driver first. */
    fun probeWithRoot(context: Context, path: String): Access = try {
        RootI2cTransport.open(context, path).close()
        Access.READY
    } catch (error: I2cTransportException.PermissionDenied) {
        Access.BLOCKED
    } catch (error: I2cTransportException.DeviceUnavailable) {
        Access.MISSING
    } catch (error: Throwable) {
        Log.w("Tarmac-HeadUnit", "root probe on $path failed", error)
        Access.UNKNOWN
    }
}
