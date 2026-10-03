package com.steamcontroller.android.usb

import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import com.steamcontroller.android.parser.SteamControllerState
import com.steamcontroller.android.parser.SteamReportParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

class HidReportReader(
    private val connection: UsbDeviceConnection,
    private val inputPipes: List<UsbConnectionManager.InputPipe>,
    private val onReport: (SteamControllerState?, ByteArray, Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val TAG = "HidReportReader"
    private val BUF_SIZE = 64
    private val PROBE_TIMEOUT_MS = 2
    private val ACTIVE_TIMEOUT_MS = 100

    private var job: Job? = null
    private var activeInterfaceId: Int? = null
    private var totalReports = 0

    fun start(scope: CoroutineScope) {
        job =
            scope.launch(Dispatchers.IO) {
                val buffers = inputPipes.associateWith { ByteArray(BUF_SIZE) }
                Log.i(
                    TAG,
                    "Read loop started on " +
                        inputPipes.joinToString { pipe ->
                            "iface ${pipe.usbInterface.id}/ep 0x${pipe.endpoint.address.toString(16)}"
                        },
                )
                while (isActive) {
                    val activeId = activeInterfaceId
                    val pipesToRead =
                        if (activeId == null) {
                            inputPipes
                        } else {
                            inputPipes.filter { it.usbInterface.id == activeId }
                        }
                    pipesToRead.forEach { pipe ->
                        if (!isActive) return@forEach
                        val buffer = buffers.getValue(pipe)
                        val len =
                            connection.bulkTransfer(
                                pipe.endpoint,
                                buffer,
                                BUF_SIZE,
                                if (activeId == null) PROBE_TIMEOUT_MS else ACTIVE_TIMEOUT_MS,
                            )
                        when {
                            len > 0 -> {
                                activeInterfaceId = pipe.usbInterface.id
                                totalReports++
                                if (totalReports == 1 || totalReports % 100 == 0) {
                                    Log.i(
                                        TAG,
                                        "Reports received: $totalReports, iface=${pipe.usbInterface.id}, " +
                                            "last ID=0x${(buffer[0].toInt() and 0xFF).toString(16)}, len=$len",
                                    )
                                }
                                val raw = buffer.copyOf(len)
                                onReport(
                                    SteamReportParser.parse(raw),
                                    raw,
                                    pipe.usbInterface.id,
                                )
                            }

                            else -> {
                                yield()
                            } // 0 = empty read, -1 = timeout: both normal
                        }
                    }
                }
                Log.i(TAG, "Read loop stopped after $totalReports reports")
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
