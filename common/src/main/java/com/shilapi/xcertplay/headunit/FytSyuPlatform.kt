package com.shilapi.xcertplay.headunit

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * FYT head units (`com.syu.*`, also sold with DUDU OS), read from the UIS7870 firmware; see docs/HEADUNIT_FYT_DUDU.md.
 * The car-signal adapter only registers for updates from FYT's module service. Only the display test the driver
 * starts in Settings sends it commands (app ID and play info), and nothing ever sends raw CAN-box frames.
 */
internal object FytSyuPlatform : HeadUnitPlatform {
    const val MODULE_SERVICE_PACKAGE = "com.syu.ms"
    const val CANBUS_PACKAGE = "com.syu.canbus"
    const val SETTINGS_APP_PACKAGE = "com.syu.settings"

    // com.lsec.core.util.data.FinalMain: update codes of the main module (code 0).
    const val U_LAMPLET = 4
    const val U_BACKCAR = 12
    const val U_ACC_ON = 50

    /** FYT's CarPlay opens this node from a root daemon; the 0660 system:system node is closed to apps. */
    const val MFI_I2C_PATH = "/dev/i2c-2"

    override val id = "fyt-syu"
    override val displayName = "FYT"
    override val detectionPackages = setOf(MODULE_SERVICE_PACKAGE, CANBUS_PACKAGE)
    override val settingsPackage = SETTINGS_APP_PACKAGE
    override val trackTestRoute = TrackTestRoute.FYT_MODULE
    override val forwardsAmapGuidance = true

    override fun matches(probe: HeadUnitProbe): Boolean =
        MODULE_SERVICE_PACKAGE in probe.systemPackages && CANBUS_PACKAGE in probe.systemPackages

    override fun signals(context: Context): HeadUnitSignalSource = FytSyuSignals(context.applicationContext)

    override fun mfiI2cPath(): String = MFI_I2C_PATH

    /** The signal an update from the main module carries, or null for every other update. */
    fun signalFor(updateCode: Int, ints: IntArray?): Pair<HeadUnitSignal, Boolean>? {
        val value = ints?.firstOrNull() ?: return null
        val signal = when (updateCode) {
            U_ACC_ON -> HeadUnitSignal.ACC
            U_BACKCAR -> HeadUnitSignal.REVERSE
            U_LAMPLET -> HeadUnitSignal.NIGHT
            else -> return null
        }
        return signal to (value != 0)
    }

    /** `com.syu.ms` broadcasts ACC as int extra `cmd.acc`: 1 on, 0 off (system_server's LsecPower reads the same). */
    fun accFromBroadcast(value: Int): Boolean? = when (value) {
        1 -> true
        0 -> false
        else -> null
    }
}

/**
 * FYT's car signals: the open `com.syu.ms` ACC broadcast, and the main module of its toolkit service, which tells
 * registered callbacks about ACC, reverse and the lights. Each value is null until FYT has said it.
 */
internal class FytSyuSignals(private val context: Context) : HeadUnitSignalSource {
    private val state = ConcurrentHashMap<HeadUnitSignal, Boolean>()
    @Volatile private var listener: ((HeadUnitSignal, Boolean) -> Unit)? = null
    @Volatile private var module: IBinder? = null
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var bound = false
    private var receiverRegistered = false

    private val callback = object : Binder() {
        init {
            attachInterface(null, CALLBACK_DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TRANSACTION_UPDATE) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CALLBACK_DESCRIPTOR)
            val updateCode = data.readInt()
            val ints = data.createIntArray()
            FytSyuPlatform.signalFor(updateCode, ints)?.let { (signal, on) -> deliver(signal, on) }
            return true
        }
    }

    private val accReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            FytSyuPlatform.accFromBroadcast(intent.getIntExtra(EXTRA_ACC, -1))?.let { deliver(HeadUnitSignal.ACC, it) }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val toolkit = service ?: return
            worker?.post { register(toolkit) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            module = null
        }
    }

    override fun current(signal: HeadUnitSignal): Boolean? = state[signal]

    @Synchronized override fun start(listener: (HeadUnitSignal, Boolean) -> Unit) {
        if (thread != null) return
        this.listener = listener
        val handlerThread = HandlerThread("tarmac-fyt-signals").also { it.start() }
        thread = handlerThread
        worker = Handler(handlerThread.looper)
        receiverRegistered = runCatching {
            ContextCompat.registerReceiver(context, accReceiver, IntentFilter(ACTION_ACC), ContextCompat.RECEIVER_EXPORTED)
        }.onFailure { Log.w(TAG, "cannot listen for the ACC broadcast", it) }.isSuccess
        bound = runCatching {
            context.bindService(FytModuleIpc.toolkitIntent(), connection, Context.BIND_AUTO_CREATE)
        }.onFailure { Log.w(TAG, "module service unavailable", it) }.getOrDefault(false)
    }

    @Synchronized override fun stop() {
        module?.let { remote -> CODES.forEach { code -> runCatching { unregister(remote, code) } } }
        module = null
        if (receiverRegistered) runCatching { context.unregisterReceiver(accReceiver) }
        receiverRegistered = false
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        thread?.quitSafely()
        thread = null
        worker = null
        listener = null
    }

    private fun deliver(signal: HeadUnitSignal, on: Boolean) {
        if (state.put(signal, on) == on) return
        listener?.invoke(signal, on)
    }

    private fun register(toolkit: IBinder) {
        val remote = runCatching { FytModuleIpc.mainModule(toolkit) }.onFailure { Log.w(TAG, "main module unavailable", it) }
            .getOrNull() ?: return
        module = remote
        for (code in CODES) {
            runCatching { FytModuleIpc.register(remote, callback, code) }.onFailure { Log.w(TAG, "register $code failed", it) }
        }
    }

    private fun unregister(remote: IBinder, code: Int) = FytModuleIpc.unregister(remote, callback, code)

    private companion object {
        const val TAG = "Tarmac-HeadUnit"
        const val ACTION_ACC = "com.syu.ms"
        const val EXTRA_ACC = "cmd.acc"
        const val CALLBACK_DESCRIPTOR = "com.syu.ipc.IModuleCallback"
        const val TRANSACTION_UPDATE = 1
        val CODES = intArrayOf(FytSyuPlatform.U_ACC_ON, FytSyuPlatform.U_BACKCAR, FytSyuPlatform.U_LAMPLET)
    }
}

/**
 * FYT's module service, `com.syu.ms` (`com.syu.ipc.IRemoteToolkit` / `IRemoteModule`); docs/HEADUNIT_FYT_DUDU.md.
 * [cmd] drives the unit and is used only by the driver-started display test, never by the car-signal adapter.
 */
internal object FytModuleIpc {
    const val ACTION_TOOLKIT = "com.syu.ms.toolkit"
    private const val TOOLKIT_DESCRIPTOR = "com.syu.ipc.IRemoteToolkit"
    private const val MODULE_DESCRIPTOR = "com.syu.ipc.IRemoteModule"
    private const val TRANSACTION_GET_REMOTE_MODULE = 1
    private const val TRANSACTION_CMD = 1
    private const val TRANSACTION_REGISTER = 3
    private const val TRANSACTION_UNREGISTER = 4
    const val MODULE_MAIN = 0

    fun toolkitIntent(): Intent = Intent(ACTION_TOOLKIT).setPackage(FytSyuPlatform.MODULE_SERVICE_PACKAGE)

    fun mainModule(toolkit: IBinder): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(TOOLKIT_DESCRIPTOR)
            data.writeInt(MODULE_MAIN)
            toolkit.transact(TRANSACTION_GET_REMOTE_MODULE, data, reply, 0)
            reply.readException()
            reply.readStrongBinder()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun register(module: IBinder, callback: IBinder, code: Int) = transact(module, TRANSACTION_REGISTER) {
        writeStrongBinder(callback)
        writeInt(code)
        // 1 asks for the current value straight away.
        writeInt(1)
    }

    fun unregister(module: IBinder, callback: IBinder, code: Int) = transact(module, TRANSACTION_UNREGISTER) {
        writeStrongBinder(callback)
        writeInt(code)
    }

    /** `cmd(int, int[], float[], String[])`, one-way in FYT's interface. */
    fun cmd(module: IBinder, code: Int, ints: IntArray?, strs: Array<String>?) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(MODULE_DESCRIPTOR)
            writeCmd(data, code, ints, strs)
            module.transact(TRANSACTION_CMD, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
    }

    fun writeCmd(data: Parcel, code: Int, ints: IntArray?, strs: Array<String>?) {
        data.writeInt(code)
        data.writeIntArray(ints)
        data.writeFloatArray(null)
        data.writeStringArray(strs)
    }

    private fun transact(module: IBinder, code: Int, write: Parcel.() -> Unit) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(MODULE_DESCRIPTOR)
            data.write()
            module.transact(code, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
