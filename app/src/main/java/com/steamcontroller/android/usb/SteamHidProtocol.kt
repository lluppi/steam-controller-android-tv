package com.steamcontroller.android.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import com.steamcontroller.android.input.SteamHaptics

object SteamHidProtocol {
    private const val TAG = "SteamHidProtocol"

    private const val REPORT_ID_FEATURES_CONTROLLER = 0x01
    private const val REPORT_ID_FEATURES_DONGLE = 0x02
    private const val BM_REQUEST_TYPE_OUT = 0x21 // host -> device, class, interface
    private const val BM_REQUEST_TYPE_IN = 0xA1 // device -> host, class, interface
    private const val B_REQUEST_GET_REPORT = 0x01
    private const val B_REQUEST_SET_REPORT = 0x09
    private const val W_VALUE_FEATURE_REPORT = 0x0300
    private const val FEATURE_REPORT_SIZE = 64
    private const val TIMEOUT_MS = 1000
    private const val REPORT_HAPTIC_COMMAND = 0x82
    private const val HAPTIC_TICK = 0x01
    private const val HAPTIC_CLICK = 0x02

    // Steam Controller 2026 lizard-mode shutdown, matching Linux hid-steam.
    private val CLEAR_DIGITAL_MAPPINGS = byteArrayOf(0x81.toByte())
    private val GET_WIRELESS_STATE = byteArrayOf(0xB4.toByte())
    private val DISABLE_LIZARD_AND_WATCHDOG =
        byteArrayOf(
            0x87.toByte(),
            0x06,
            0x09,
            0x00,
            0x00,
            0x47,
            0x00,
            0x00,
        )

    @Synchronized
    fun disableLizardMode(
        connection: UsbDeviceConnection,
        interfaceId: Int,
    ): Boolean {
        val mappings = sendFeatureReport(connection, interfaceId, CLEAR_DIGITAL_MAPPINGS)
        val settings = sendFeatureReport(connection, interfaceId, DISABLE_LIZARD_AND_WATCHDOG)
        if (mappings < 0 || settings < 0) {
            Log.w(
                TAG,
                "Failed to disable lizard mode on iface $interfaceId: mappings=$mappings settings=$settings",
            )
            return false
        }
        Log.i(TAG, "Lizard mode disabled on iface $interfaceId")
        return true
    }

    @Synchronized
    fun getWirelessState(
        connection: UsbDeviceConnection,
        interfaceId: Int,
    ): Int? {
        if (sendFeatureReport(
                connection,
                interfaceId,
                GET_WIRELESS_STATE,
                REPORT_ID_FEATURES_DONGLE,
            ) < 0
        ) {
            return null
        }

        val report = ByteArray(FEATURE_REPORT_SIZE)
        val result =
            connection.controlTransfer(
                BM_REQUEST_TYPE_IN,
                B_REQUEST_GET_REPORT,
                W_VALUE_FEATURE_REPORT or REPORT_ID_FEATURES_DONGLE,
                interfaceId,
                report,
                report.size,
                TIMEOUT_MS,
            )
        if (result < 4 ||
            report[0].toInt().and(0xFF) != REPORT_ID_FEATURES_DONGLE ||
            report[1].toInt().and(0xFF) != GET_WIRELESS_STATE[0].toInt().and(0xFF)
        ) {
            Log.w(
                TAG,
                "Invalid wireless state on iface $interfaceId: result=$result " +
                    report.take(result.coerceAtLeast(0).coerceAtMost(8)).joinToString(" ") {
                        "%02x".format(it)
                    },
            )
            return null
        }
        return report[3].toInt().and(0xFF)
    }

    fun sendTrackpadHaptic(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        left: Boolean,
        strongClick: Boolean,
    ): Boolean {
        val report =
            byteArrayOf(
                REPORT_HAPTIC_COMMAND.toByte(),
                if (left) 0x00 else 0x01,
                if (strongClick) HAPTIC_CLICK.toByte() else HAPTIC_TICK.toByte(),
                0x00,
            )
        return connection.bulkTransfer(endpoint, report, report.size, TIMEOUT_MS) == report.size
    }

    @Synchronized
    fun sendRumble(
        connection: UsbDeviceConnection,
        interfaceId: Int,
        strong: Int,
        weak: Int,
    ): Boolean =
        sendFeatureReport(connection, interfaceId, SteamHaptics.payload(0, strong)) >= 0 &&
            sendFeatureReport(connection, interfaceId, SteamHaptics.payload(1, weak)) >= 0

    private fun sendFeatureReport(
        connection: UsbDeviceConnection,
        interfaceId: Int,
        payload: ByteArray,
        reportId: Int = REPORT_ID_FEATURES_CONTROLLER,
    ): Int {
        val report = ByteArray(FEATURE_REPORT_SIZE)
        report[0] = reportId.toByte()
        payload.copyInto(report, destinationOffset = 1)
        return connection.controlTransfer(
            BM_REQUEST_TYPE_OUT,
            B_REQUEST_SET_REPORT,
            W_VALUE_FEATURE_REPORT or reportId,
            interfaceId,
            report,
            report.size,
            TIMEOUT_MS,
        )
    }
}
