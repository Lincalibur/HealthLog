package com.liamo.f1.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.liamo.f1.data.AttemptKind
import com.liamo.f1.data.Outcome
import com.liamo.f1.data.Settings
import com.liamo.f1.data.Store
import com.liamo.f1.protocol.Commands
import com.liamo.f1.protocol.Reassembler
import com.liamo.f1.protocol.WatchEvent
import com.liamo.f1.protocol.decode
import com.liamo.f1.protocol.split
import com.liamo.f1.protocol.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ConnectFailed(message: String) : Exception(message)

data class LogLine(val time: Long, val dir: Char, val text: String)

/**
 * Owns the single connection to the watch (it accepts only one at a time). The debug
 * screen, the live heart-rate service and the background workers all share it:
 * [acquire] connects if needed, and the link closes when the last holder [release]s.
 */
class WatchSession(
    private val context: Context,
    private val store: Store,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    enum class Status { DISCONNECTED, CONNECTING, READY }

    private val _status = MutableStateFlow(Status.DISCONNECTED)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _events = MutableSharedFlow<WatchEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WatchEvent> = _events.asSharedFlow()

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    /** Raw hex in (<) and out (>), plus connection notes (·). Like the laptop's `listen`. */
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    private val connectLock = Mutex()
    private val writeLock = Mutex()
    private var holders = 0
    private var manager: F1BleManager? = null
    private val rx = Reassembler()

    val hasPedometer get() = manager?.hasPedometer == true

    /** Connect if needed. Throws [ConnectFailed]; every connect is logged as an attempt. */
    suspend fun acquire() = connectLock.withLock {
        holders++
        if (_status.value == Status.READY && manager?.isConnected == true) return@withLock
        try {
            connect()
        } catch (e: Exception) {
            holders--
            throw e
        }
    }

    /** Let go of the connection; the last holder closes it (unsubscribing first). */
    suspend fun release() = withContext(NonCancellable) {
        connectLock.withLock {
            holders = (holders - 1).coerceAtLeast(0)
            if (holders == 0) disconnect()
        }
    }

    suspend fun <T> withConnection(block: suspend WatchSession.() -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    /** Send one frame, split into 20-byte chunks. */
    suspend fun send(frame: ByteArray) = writeLock.withLock {
        val m = manager ?: throw ConnectFailed("not connected")
        note('>', frame.toHex())
        for (chunk in split(frame)) m.writeChunk(chunk)
    }

    /**
     * Run [action] (usually a [send]) and wait for the first event of type [T] that
     * matches. Subscribes before acting, so a fast reply can't be missed.
     */
    suspend inline fun <reified T : WatchEvent> request(
        timeoutMs: Long,
        crossinline match: (T) -> Boolean = { true },
        crossinline action: suspend () -> Unit,
    ): T? = withTimeoutOrNull(timeoutMs) {
        events.onSubscription { action() }.filterIsInstance<T>().filter { match(it) }.first()
    }

    suspend fun readPedometer(): ByteArray? {
        val value = manager?.readPedometer() ?: return null
        note('<', "[fea1] ${value.toHex()}")
        return value
    }

    fun note(dir: Char, text: String) {
        val line = LogLine(System.currentTimeMillis(), dir, text)
        _log.update { (it + line).takeLast(LOG_LINES) }
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    private suspend fun connect() {
        val address = settings.state.value.watchAddress ?: throw ConnectFailed("No watch paired yet: pair it in Settings.")
        if (!hasBluetoothPermission(context)) throw ConnectFailed("Bluetooth permission not granted.")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw ConnectFailed("This phone has no Bluetooth.")
        if (!adapter.isEnabled) throw ConnectFailed("Bluetooth is off.")

        val device = remoteDevice(adapter, address)
        _status.value = Status.CONNECTING
        note('·', "connecting")
        rx.reset()
        val m = F1BleManager(context, ::onPacket) { note('·', it) }
        m.setConnectionObserver(DisconnectWatcher { onLinkLost() })
        val started = System.currentTimeMillis()
        try {
            m.open(device)
        } catch (e: Exception) {
            runCatching { m.close() }
            _status.value = Status.DISCONNECTED
            val timedOut = e.javaClass.simpleName.contains("Timeout", ignoreCase = true)
            val why = e.message ?: e.javaClass.simpleName
            store.attempt(AttemptKind.CONNECT, if (timedOut) Outcome.TIMEOUT else Outcome.ERROR, started, why)
            store.updateState { it.copy(connectFailures = it.connectFailures + 1, lastConnectError = why) }
            note('·', "connect failed: $why")
            throw ConnectFailed(why)
        }
        manager = m
        _status.value = Status.READY
        store.attempt(AttemptKind.CONNECT, Outcome.OK, started)
        store.updateState { it.copy(connectFailures = 0, lastConnectError = null) }
        note('·', "connected")
    }

    private suspend fun disconnect() {
        val m = manager ?: return
        manager = null
        note('·', "disconnecting")
        m.shutdown()
        _status.value = Status.DISCONNECTED
    }

    private fun onLinkLost() {
        // The watch dropped the link (out of range, or another app grabbed it).
        if (manager != null) {
            manager = null
            _status.value = Status.DISCONNECTED
            note('·', "link lost")
        }
    }

    private fun onPacket(packet: ByteArray) {
        val frame = rx.feed(packet) ?: return
        note('<', frame.toHex())
        val event = decode(frame)
        if (event is WatchEvent.Heartbeat) scope.launch { runCatching { send(Commands.HEARTBEAT_REPLY) } }
        if (event is WatchEvent.Unknown) note('·', "unknown frame (kept in log)")
        _events.tryEmit(event)
    }

    @Suppress("MissingPermission")
    private fun remoteDevice(adapter: android.bluetooth.BluetoothAdapter, address: String): BluetoothDevice =
        // The F1 uses a static random address; say so where the API allows it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            adapter.getRemoteLeDevice(address, android.bluetooth.BluetoothDevice.ADDRESS_TYPE_RANDOM)
        } else {
            adapter.getRemoteDevice(address)
        }

    companion object {
        private const val LOG_LINES = 400

        fun hasBluetoothPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    }
}

private class DisconnectWatcher(private val onLost: () -> Unit) : no.nordicsemi.android.ble.observer.ConnectionObserver {
    override fun onDeviceConnecting(device: BluetoothDevice) = Unit
    override fun onDeviceConnected(device: BluetoothDevice) = Unit
    override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) = Unit
    override fun onDeviceReady(device: BluetoothDevice) = Unit
    override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit
    override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) = onLost()
}
