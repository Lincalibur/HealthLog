package com.liamo.f1.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import com.liamo.f1.protocol.CHUNK_SIZE
import com.liamo.f1.protocol.Uuids
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.suspend

/**
 * GATT layer for the Genius F1. The rules here come from what went wrong on the laptop:
 *
 * - Subscribe to `6e400003` only. A remembered FEA1 subscription once wedged the watch.
 * - Never call requestMtu(): the watch ignores the MTU exchange and the link stalls.
 *   20-byte chunks fit the default MTU of 23.
 * - Write without response, one 20-byte chunk at a time (the library queues them).
 * - Unsubscribe before disconnecting, so the watch doesn't remember the subscription.
 */
class F1BleManager(
    context: Context,
    private val onPacket: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit,
) : BleManager(context) {
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var pedometerChar: BluetoothGattCharacteristic? = null

    val hasPedometer get() = pedometerChar != null

    override fun getMinLogPriority() = Log.INFO

    override fun log(priority: Int, message: String) {
        if (priority >= Log.INFO) onLog(message)
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val uart = gatt.getService(Uuids.UART_SERVICE)
        if (uart == null) {
            onLog("Nordic UART service 6e400001 is missing: is this really the F1?")
            return false
        }
        writeChar = uart.getCharacteristic(Uuids.UART_WRITE)
        notifyChar = uart.getCharacteristic(Uuids.UART_NOTIFY)
        pedometerChar = gatt.getService(Uuids.PEDOMETER_SERVICE)?.getCharacteristic(Uuids.PEDOMETER_STEPS)
        return writeChar != null && notifyChar != null
    }

    override fun initialize() {
        setNotificationCallback(notifyChar).with { _, data -> data.value?.let(onPacket) }
        enableNotifications(notifyChar).enqueue()
    }

    override fun onServicesInvalidated() {
        writeChar = null
        notifyChar = null
        pedometerChar = null
    }

    suspend fun open(device: BluetoothDevice) {
        connect(device)
            .useAutoConnect(false)
            .retry(3, 200)
            .timeout(CONNECT_TIMEOUT_MS)
            .suspend()
    }

    /** One chunk of at most 20 bytes; callers split frames with [com.liamo.f1.protocol.split]. */
    suspend fun writeChunk(chunk: ByteArray) {
        require(chunk.size <= CHUNK_SIZE) { "chunk of ${chunk.size} bytes" }
        writeCharacteristic(writeChar, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE).suspend()
    }

    /** Today's steps from FEA1. Read only; enabling its indications is what wedged the watch. */
    suspend fun readPedometer(): ByteArray? {
        val c = pedometerChar ?: return null
        return readCharacteristic(c).suspend().value
    }

    /** Unsubscribe first, then close the link. Never throws. */
    suspend fun shutdown() {
        runCatching { if (isReady) disableNotifications(notifyChar).suspend() }
        runCatching { disconnect().suspend() }
        runCatching { close() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000L
    }
}
