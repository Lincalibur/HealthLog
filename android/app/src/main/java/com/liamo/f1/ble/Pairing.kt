package com.liamo.f1.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Parcelable
import androidx.core.content.IntentCompat
import java.util.regex.Pattern

/**
 * Pick the watch once with the system's companion-device dialog. No location permission
 * is needed, and the association lets the app reconnect from the background later.
 * The watch advertises as "F1" with service FEE7; the UART service isn't in its advert.
 */
object Pairing {
    private fun request(): AssociationRequest = AssociationRequest.Builder()
        .addDeviceFilter(BluetoothLeDeviceFilter.Builder().setNamePattern(Pattern.compile("F1")).build())
        .setSingleDevice(false)
        .build()

    /** Calls [onChooser] with the system dialog to launch, or [onError]. */
    fun start(context: Context, onChooser: (IntentSender) -> Unit, onError: (String) -> Unit) {
        val cdm = context.getSystemService(CompanionDeviceManager::class.java)
            ?: return onError("Companion device pairing isn't available on this phone.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.associate(request(), context.mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = onChooser(intentSender)
                override fun onAssociationCreated(associationInfo: AssociationInfo) = Unit
                override fun onFailure(error: CharSequence?) = onError(error?.toString() ?: "Pairing failed.")
            })
        } else {
            @Suppress("DEPRECATION")
            cdm.associate(request(), object : CompanionDeviceManager.Callback() {
                @Deprecated("Replaced by onAssociationPending on Android 13")
                override fun onDeviceFound(chooserLauncher: IntentSender) = onChooser(chooserLauncher)
                override fun onFailure(error: CharSequence?) = onError(error?.toString() ?: "Pairing failed.")
            }, null)
        }
    }

    /** The chosen watch's address from the dialog's result, or null if cancelled. Stays on the phone. */
    fun addressFrom(data: Intent?): String? {
        if (data == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            IntentCompat.getParcelableExtra(data, CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
                ?.deviceMacAddress?.toString()?.uppercase()?.let { return it }
        }
        @Suppress("DEPRECATION") // still filled in on every version; EXTRA_ASSOCIATION needs 13+
        return when (val d = IntentCompat.getParcelableExtra(data, CompanionDeviceManager.EXTRA_DEVICE, Parcelable::class.java)) {
            is ScanResult -> d.device.address
            is BluetoothDevice -> d.address
            else -> null
        }
    }
}
