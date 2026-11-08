package com.shilapi.xcertplay.transport

import android.content.Context
import android.os.Build
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.system.exitProcess

/**
 * I2C through a helper that runs as root, for a rooted head unit whose authentication chip node is closed to apps
 * (TEYES: SELinux label `device`; FYT: mode 0660 system). The helper opens the node and relays transactions over its
 * standard input and output; it changes no permission, label or SELinux mode. Every call blocks.
 */
class RootI2cTransport private constructor(
    private val process: Process,
    private val input: DataInputStream,
    private val output: DataOutputStream,
) : I2cTransport, Closeable {
    private val lock = Any()
    private var closed = false

    override fun transaction(address7Bit: Int, writeData: ByteArray, readLength: Int): ByteArray =
        synchronized(lock) {
            if (closed) throw I2cTransportException.DeviceUnavailable("Root I2C helper is closed")
            try {
                RootI2cProtocol.writeRequest(output, address7Bit, writeData, readLength)
                RootI2cProtocol.readResponse(input, readLength)
            } catch (error: I2cTransportException) {
                throw error
            } catch (error: IOException) {
                throw I2cTransportException.DeviceUnavailable("Root I2C helper stopped: ${error.message}", error)
            }
        }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            // End of input stops the helper's loop; destroy also covers a helper stuck in a transaction.
            runCatching { output.close() }
            process.destroy()
        }
    }

    companion object {
        /** Whether this device has an `su` binary an app can run. Granting is up to the root manager. */
        fun suAvailable(): Boolean = RootShell.suPath() != null

        /**
         * Starts the root helper on [devicePath]. The root manager may ask the driver first, so this can take as
         * long as that prompt; [I2cTransportException.PermissionDenied] means root was refused or the node stayed
         * closed even to root.
         */
        @Throws(I2cTransportException::class)
        fun open(context: Context, devicePath: String): RootI2cTransport {
            val info = context.applicationInfo
            val process = try {
                RootShell.start(command(info.sourceDir, libraryPath(info.sourceDir, info.nativeLibraryDir), devicePath))
            } catch (error: IOException) {
                throw I2cTransportException.PermissionDenied("su could not start: ${error.message}")
            }
            val input = DataInputStream(BufferedInputStream(process.inputStream))
            val output = DataOutputStream(BufferedOutputStream(process.outputStream))
            try {
                RootI2cProtocol.awaitReady(input)
            } catch (error: Throwable) {
                process.destroy()
                throw when (error) {
                    is I2cTransportException -> error
                    is EOFException -> I2cTransportException.PermissionDenied("Root was refused or the helper did not start")
                    else -> I2cTransportException.DeviceUnavailable("Root I2C helper failed: ${error.message}", error)
                }
            }
            return RootI2cTransport(process, input, output)
        }

        /** The shell command su runs: Tarmac's own helper class from the installed APK, on [devicePath]. */
        fun command(apk: String, libraryPath: String, devicePath: String): String {
            require(Regex("/dev/i2c-[0-9]+").matches(devicePath)) { "I2C device path must be /dev/i2c-N: $devicePath" }
            return "CLASSPATH=${RootShell.quote(apk)} app_process -Djava.library.path=${RootShell.quote(libraryPath)} /system/bin " +
                "${RootI2cHelper::class.java.name} $devicePath 2>/dev/null"
        }

        /** Extracted libraries when the installer unpacked them, else the ones stored inside the APK. */
        fun libraryPath(apk: String, nativeLibraryDir: String?): String {
            val abi = Build.SUPPORTED_64_BIT_ABIS?.firstOrNull() ?: Build.SUPPORTED_ABIS?.firstOrNull() ?: "arm64-v8a"
            return listOfNotNull(nativeLibraryDir, "$apk!/lib/$abi").joinToString(":")
        }

    }
}

/** The root helper's byte protocol: a ready header, then one request and one response per transaction. */
object RootI2cProtocol {
    /** "TMI2": lets the client skip anything a ROM prints on standard output before the helper speaks. */
    const val MAGIC = 0x544D4932
    private const val OK = 0
    private const val MAX_PREAMBLE = 64 * 1024

    fun writeReady(output: DataOutputStream, status: Int) {
        output.writeInt(MAGIC)
        output.writeInt(status)
        output.flush()
    }

    @Throws(IOException::class)
    fun awaitReady(input: DataInputStream) {
        var window = 0
        var skipped = 0
        while (window != MAGIC) {
            val next = input.read()
            if (next < 0) throw EOFException("helper ended before it was ready")
            window = (window shl 8) or next
            if (++skipped > MAX_PREAMBLE) throw I2cTransportException.Protocol("Root I2C helper never became ready")
        }
        val status = input.readInt()
        if (status != OK) throw exceptionFor(status, "Root I2C helper could not open the device")
    }

    fun writeRequest(output: DataOutputStream, address7Bit: Int, writeData: ByteArray, readLength: Int) {
        output.writeInt(address7Bit)
        output.writeInt(readLength)
        output.writeInt(writeData.size)
        output.write(writeData)
        output.flush()
    }

    @Throws(IOException::class)
    fun readResponse(input: DataInputStream, readLength: Int): ByteArray {
        val status = input.readInt()
        if (status != OK) throw exceptionFor(status, "Root I2C transaction failed")
        return ByteArray(readLength).also(input::readFully)
    }

    /** errno-style codes, so the helper and the app agree on what went wrong. */
    fun statusOf(error: I2cTransportException): Int = when (error) {
        is I2cTransportException.PermissionDenied -> 13
        is I2cTransportException.TimedOut -> 110
        is I2cTransportException.Nack -> 121
        is I2cTransportException.DeviceUnavailable -> 19
        is I2cTransportException.InvalidRequest -> 22
        is I2cTransportException.Protocol -> 5
    }

    fun exceptionFor(status: Int, message: String): I2cTransportException = when (status) {
        13 -> I2cTransportException.PermissionDenied(message)
        110 -> I2cTransportException.TimedOut(message)
        121 -> I2cTransportException.Nack(message)
        19 -> I2cTransportException.DeviceUnavailable(message)
        22 -> I2cTransportException.InvalidRequest(message)
        else -> I2cTransportException.Protocol("$message (status $status)")
    }

    /** The helper's loop: one transaction per request until the app closes the pipe. */
    fun serve(transport: I2cTransport, input: DataInputStream, output: DataOutputStream) {
        while (true) {
            val address = try {
                input.readInt()
            } catch (end: EOFException) {
                return
            }
            val readLength = input.readInt()
            val writeLength = input.readInt()
            if (readLength !in 0..0xffff || writeLength !in 0..0xffff) {
                output.writeInt(statusOf(I2cTransportException.InvalidRequest("bad length")))
                output.flush()
                return
            }
            val writeData = ByteArray(writeLength).also(input::readFully)
            try {
                val data = transport.transaction(address, writeData, readLength)
                output.writeInt(OK)
                output.write(data)
            } catch (error: I2cTransportException) {
                output.writeInt(statusOf(error))
            }
            output.flush()
        }
    }

    internal fun streams(input: InputStream, output: OutputStream) =
        DataInputStream(BufferedInputStream(input)) to DataOutputStream(BufferedOutputStream(output))
}

/** Runs as root through app_process (see [RootI2cTransport.command]); never inside the app's own process. */
object RootI2cHelper {
    @JvmStatic fun main(args: Array<String>) {
        val (input, output) = RootI2cProtocol.streams(FileInputStream(FileDescriptor.`in`), FileOutputStream(FileDescriptor.out))
        val transport = try {
            LinuxI2cTransport.open(args.firstOrNull().orEmpty())
        } catch (error: I2cTransportException) {
            RootI2cProtocol.writeReady(output, RootI2cProtocol.statusOf(error))
            exitProcess(1)
        } catch (error: Throwable) {
            RootI2cProtocol.writeReady(output, 5)
            exitProcess(1)
        }
        RootI2cProtocol.writeReady(output, 0)
        try {
            RootI2cProtocol.serve(transport, input, output)
        } finally {
            transport.close()
        }
        exitProcess(0)
    }
}
