// SPDX-License-Identifier: AGPL-3.0-or-later
// The flow follows open-headunit's Native mode (NativeAaHandshakeManager): an RFCOMM record with
// Android Auto's UUID, the setup exchange in WifiSetup.kt, then the session over TCP.
package com.shilapi.xcertplay.androidauto.wireless

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.shilapi.xcertplay.androidauto.AndroidAutoException
import com.shilapi.xcertplay.androidauto.AndroidAutoFailure
import com.shilapi.xcertplay.androidauto.AndroidAutoLog
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * One wireless connection attempt. Android Auto on the phone looks for this record when its
 * hands-free link to the head unit connects, opens it, and is told over it which Wi-Fi to join and
 * where to connect; the AAP session then runs over that TCP connection.
 *
 * The record is published first, before the Wi-Fi network is up, because the phone looks only
 * once. [close] from another thread cancels a waiting [connect] and frees everything.
 */
internal class AndroidAutoWireless(private val context: Context) : Closeable {
    enum class Stage { WAITING_FOR_PHONE, JOINING }

    /** The session's stream, and what must stay open until the session ends. */
    class Link(val stream: BlockingDuplexByteStream, val phoneAddress: String?, private val held: List<Closeable>) : Closeable {
        override fun close() {
            stream.close()
            held.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private val owned = CopyOnWriteArrayList<Closeable>()
    @Volatile private var cancelled = false

    /** Blocks until the phone has connected over Wi-Fi; [stage] reports progress. */
    @SuppressLint("MissingPermission") // Checked in bluetoothAdapter().
    fun connect(stage: (Stage) -> Unit): Link {
        val accepted = AtomicReference<Socket?>()
        try {
            val adapter = bluetoothAdapter()
            val record = try {
                adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, ANDROID_AUTO_UUID)
            } catch (error: IOException) {
                throw AndroidAutoException(AndroidAutoFailure.BLUETOOTH, "the Android Auto Bluetooth record was refused", error)
            }.also(::own)
            AndroidAutoLog.i("listening for Android Auto over Bluetooth")

            val network = AndroidAutoWifiNetwork.manager(context).also(::own)
            val wifi = FutureTask { network.start(WIFI_START_MILLIS) }
            Thread(wifi, "AndroidAuto-wifi").apply { isDaemon = true }.start()
            val server = listen().also(::own)

            stage(Stage.WAITING_FOR_PHONE)
            val phone = accept(record, wifi)
            runCatching { record.close() }
            val address = runCatching { phone.remoteDevice.address }.getOrNull()
            AndroidAutoLog.i("phone opened the Android Auto record")
            val setup = BluetoothRfcommDuplexStream(phone) { AndroidAutoLog.i(it) }.also(::own)

            val credentials = credentials(waitFor(wifi), server.localPort)
            AndroidAutoLog.i("offering $credentials")
            val tcp = CountDownLatch(1)
            Thread({
                runCatching { server.accept() }.onSuccess(accepted::set)
                tcp.countDown()
            }, "AndroidAuto-tcp").apply { isDaemon = true }.start()

            stage(Stage.JOINING)
            val exchange = WifiSetupExchange(setup, credentials, tcpConnected = { accepted.get() != null })
            exchange.run()
            tcp.await(TCP_AFTER_JOIN_MILLIS, TimeUnit.MILLISECONDS)
            checkCancelled()
            val socket = accepted.get()
                ?: throw AndroidAutoException(AndroidAutoFailure.PHONE_WIFI, "the phone joined the Wi-Fi but did not connect to port ${server.localPort}")
            runCatching { server.close() }
            AndroidAutoLog.i("phone connected over Wi-Fi from ${socket.inetAddress?.hostAddress}")
            // Kept open and answered for the whole session; the phone starts over when it closes.
            Thread(exchange::hold, "AndroidAuto-setup-hold").apply { isDaemon = true }.start()
            return Link(TcpByteStream(socket), address, listOf(network, setup)).also { owned.clear() }
        } catch (error: Exception) {
            close()
            accepted.get()?.let { runCatching { it.close() } }
            if (cancelled) throw InterruptedIOException("wireless Android Auto cancelled")
            throw error
        }
    }

    /** Cancels a waiting [connect] by closing what it waits on. */
    override fun close() {
        cancelled = true
        owned.asReversed().forEach { runCatching { it.close() } }
        owned.clear()
    }

    /** Frees [resource] with the attempt unless the [Link] takes it over; a cancel that came first frees it at once. */
    private fun own(resource: Closeable) {
        owned += resource
        if (cancelled) {
            close()
            throw InterruptedIOException("wireless Android Auto cancelled")
        }
    }

    private fun bluetoothAdapter() = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) throw AndroidAutoException(AndroidAutoFailure.BLUETOOTH, "the Nearby devices permission is not granted")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw AndroidAutoException(AndroidAutoFailure.BLUETOOTH, "this device has no Bluetooth")
        if (!adapter.isEnabled) throw AndroidAutoException(AndroidAutoFailure.BLUETOOTH, "Bluetooth is off")
        adapter
    }

    /** Port 5288, as head units use; any free port otherwise, since the start request names it. */
    private fun listen(): ServerSocket {
        val server = try {
            bound(PORT)
        } catch (_: IOException) {
            bound(0)
        }
        server.soTimeout = (TCP_AFTER_JOIN_MILLIS + WifiSetupExchange.JOIN_MILLIS + WifiSetupExchange.FIRST_REPLY_MILLIS).toInt()
        return server
    }

    private fun bound(port: Int): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        try {
            bind(InetSocketAddress(port))
        } catch (error: IOException) {
            close()
            throw error
        }
    }

    /**
     * Waits for the phone in short slices, so a Wi-Fi that failed to start ends the attempt at once
     * rather than after the whole wait. A slice that fails long before its timeout is Bluetooth itself failing.
     */
    private fun accept(record: BluetoothServerSocket, wifi: FutureTask<WirelessHotspotInfo>): BluetoothSocket {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PHONE_MILLIS)
        while (true) {
            val started = System.nanoTime()
            try {
                return record.accept(ACCEPT_SLICE_MILLIS.toInt()).also { checkCancelled() }
            } catch (error: IOException) {
                checkCancelled()
                if (wifi.isDone) waitFor(wifi)
                if (System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(ACCEPT_SLICE_MILLIS / 2)) {
                    throw AndroidAutoException(AndroidAutoFailure.BLUETOOTH, "the Android Auto Bluetooth record stopped accepting", error)
                }
                if (System.nanoTime() >= deadline) {
                    throw AndroidAutoException(AndroidAutoFailure.NO_WIRELESS_PHONE, "no phone opened Android Auto over Bluetooth", error)
                }
            }
        }
    }

    private fun waitFor(wifi: FutureTask<WirelessHotspotInfo>): WirelessHotspotInfo = try {
        wifi.get(WIFI_START_MILLIS + WIFI_GRACE_MILLIS, TimeUnit.MILLISECONDS)
    } catch (error: ExecutionException) {
        checkCancelled()
        throw AndroidAutoException(AndroidAutoFailure.WIFI, "the Wi-Fi for the phone did not start: ${error.cause?.message}", error.cause)
    } catch (error: TimeoutException) {
        throw AndroidAutoException(AndroidAutoFailure.WIFI, "the Wi-Fi for the phone did not start in time", error)
    }.also { checkCancelled() }

    private fun checkCancelled() {
        if (cancelled) throw InterruptedIOException("wireless Android Auto cancelled")
    }

    internal companion object {
        val ANDROID_AUTO_UUID: UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
        private const val SERVICE_NAME = "Android Auto"
        const val PORT = 5288
        /** From the record's publication to the phone opening it: time to turn the phone's Bluetooth off and on. */
        private const val PHONE_MILLIS = 90_000L
        private const val ACCEPT_SLICE_MILLIS = 2_000L
        private const val WIFI_START_MILLIS = 20_000L
        private const val WIFI_GRACE_MILLIS = 5_000L
        private const val TCP_AFTER_JOIN_MILLIS = 20_000L

        /** What the phone is told; null fields of [info] become an empty BSSID or a refusal. */
        fun credentials(info: WirelessHotspotInfo, port: Int): WifiCredentials {
            val host = (info.hostAddresses + listOfNotNull(info.hostAddress)).firstOrNull { it is Inet4Address }
                ?: throw AndroidAutoException(AndroidAutoFailure.WIFI, "the Wi-Fi for the phone has no IPv4 address")
            // A station network names its access point separately; a network the head unit hosts is its own interface.
            val bssid = info.accessPointBssid?.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
                ?: info.bssid.orEmpty()
            return WifiCredentials(
                ssid = info.ssid,
                key = info.passphrase,
                bssid = bssid.lowercase(),
                securityMode = WifiSecurityMode.of(info.security),
                hostAddress = host.hostAddress.orEmpty(),
                port = port,
            )
        }
    }
}
