package com.steamcontroller.android.usb

import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import com.steamcontroller.android.input.SteamHaptics

object SteamHidProtocol {

    private const val TAG = "SteamHidProtocol"

    // Feature report 0x85: disable lizard mode (keyboard/mouse emulation)
    private val DISABLE_LIZARD = byteArrayOf(0x85.toByte())

    // HID Set_Report control transfer parameters
    private const val BM_REQUEST_TYPE = 0x21 // host→device, class, interface
    private const val B_REQUEST_SET_REPORT = 0x09
    private const val W_VALUE_FEATURE_REPORT = 0x0300 // Feature (0x03) + report ID (0x00)
    private const val TIMEOUT_MS = 1000

    @Synchronized
    fun disableLizardMode(connection: UsbDeviceConnection): Boolean {
        val result = sendFeatureReport(connection, DISABLE_LIZARD)
        if (result < 0) {
            Log.e(TAG, "Failed to disable lizard mode: $result")
            return false
        }
        Log.i(TAG, "Lizard mode disabled")
        return true
    }

    // Call heartbeat() every 800ms to keep lizard mode disabled
    @Synchronized
    fun heartbeat(connection: UsbDeviceConnection) {
        sendFeatureReport(connection, DISABLE_LIZARD)
    }

    @Synchronized
    fun sendRumble(connection: UsbDeviceConnection, strong: Int, weak: Int): Boolean =
        sendFeatureReport(connection, SteamHaptics.payload(0, strong)) >= 0 &&
            sendFeatureReport(connection, SteamHaptics.payload(1, weak)) >= 0

    private fun sendFeatureReport(connection: UsbDeviceConnection, payload: ByteArray): Int =
        connection.controlTransfer(
            BM_REQUEST_TYPE,
            B_REQUEST_SET_REPORT,
            W_VALUE_FEATURE_REPORT,
            0,
            payload,
            payload.size,
            TIMEOUT_MS
        )
}
