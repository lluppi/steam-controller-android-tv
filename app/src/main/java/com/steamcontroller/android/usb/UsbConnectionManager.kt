package com.steamcontroller.android.usb

import android.content.Context
import android.hardware.usb.*
import android.util.Log

class UsbConnectionManager(
    private val context: Context,
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    var device: UsbDevice? = null
        private set
    var connection: UsbDeviceConnection? = null
        private set

    data class InputPipe(
        val usbInterface: UsbInterface,
        val endpoint: UsbEndpoint,
        val endpointOut: UsbEndpoint?,
    )

    var inputPipes: List<InputPipe> = emptyList()
        private set

    // Interfaces claimed only to keep them away from the kernel - e.g. the Steam
    // Controller's legacy "boot keyboard" HID interface used for lizard mode. If we
    // don't claim it, Android's kernel usbhid driver binds it and creates a real,
    // non-virtual keyboard InputDevice - which makes Android believe a hardware
    // keyboard is always connected and suppresses the on-screen keyboard everywhere,
    // for as long as the controller stays plugged in. Released on disconnect().
    private val heldInterfaces = mutableListOf<UsbInterface>()
    private val dataInterfaces = mutableListOf<UsbInterface>()

    companion object {
        private const val TAG = "UsbConnectionManager"
        const val STEAM_VID = 0x28DE
        private const val PID_IBEX_WIRED = 0x1302
        private const val PID_PROTEUS_PUCK = 0x1304
        private const val PID_NEREID_RECEIVER = 0x1305

        fun isSupportedDevice(device: UsbDevice): Boolean =
            device.vendorId == STEAM_VID &&
                device.productId in
                setOf(PID_IBEX_WIRED, PID_PROTEUS_PUCK, PID_NEREID_RECEIVER)
    }

    fun findSteamController(): UsbDevice? = usbManager.deviceList.values.firstOrNull(::isSupportedDevice)

    fun connect(dev: UsbDevice): Boolean {
        if (!usbManager.hasPermission(dev)) {
            Log.e(TAG, "No USB permission for device")
            return false
        }

        logAllInterfaces(dev)

        val conn = usbManager.openDevice(dev)
        if (conn == null) {
            Log.e(TAG, "Failed to open device")
            return false
        }

        val pipes = claimInputPipes(dev, conn)
        if (pipes.isEmpty()) {
            Log.e(TAG, "No usable controller slot interface found")
            conn.close()
            return false
        }

        val wirelessStates =
            if (dev.productId == PID_PROTEUS_PUCK || dev.productId == PID_NEREID_RECEIVER) {
                pipes.associateWith { pipe ->
                    SteamHidProtocol.getWirelessState(conn, pipe.usbInterface.id).also { state ->
                        Log.i(
                            TAG,
                            "Puck slot iface ${pipe.usbInterface.id} wireless state=${state ?: "unavailable"}",
                        )
                    }
                }
            } else {
                emptyMap()
            }
        val orderedPipes = pipes.sortedByDescending { wirelessStates[it] == 0x02 }

        device = dev
        connection = conn
        inputPipes = orderedPipes
        dataInterfaces.addAll(orderedPipes.map(InputPipe::usbInterface))
        claimRemainingInterfaces(dev, conn, dataInterfaces.mapTo(mutableSetOf()) { it.id })
        Log.i(
            TAG,
            "Connected - PID=0x${dev.productId.toString(16).uppercase()} " +
                "pipes=${orderedPipes.joinToString { "iface=${it.usbInterface.id}/ep=0x${it.endpoint.address.toString(16)}" }}",
        )
        return true
    }

    // Force-claims every other interface on the device so the kernel usbhid driver
    // can't bind them behind our back (see heldInterfaces comment). We never read
    // from these - just holding the claim is enough to keep the kernel off them.
    private fun claimRemainingInterfaces(
        dev: UsbDevice,
        conn: UsbDeviceConnection,
        dataInterfaceIds: Set<Int>,
    ) {
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            if (iface.id in dataInterfaceIds) continue
            if (conn.claimInterface(iface, true)) {
                heldInterfaces.add(iface)
                Log.i(
                    TAG,
                    "Claimed iface ${iface.id} (class=0x${iface.interfaceClass.toString(16)}) to keep it off the kernel usbhid driver",
                )
            } else {
                Log.w(TAG, "Could not claim iface ${iface.id} to withhold it from kernel usbhid")
            }
        }
    }

    private fun logAllInterfaces(dev: UsbDevice) {
        Log.i(TAG, "Device VID=0x${dev.vendorId.toString(16)} PID=0x${dev.productId.toString(16)} interfaces=${dev.interfaceCount}")
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            Log.i(TAG, "  iface[$i] id=${iface.id} class=0x${iface.interfaceClass.toString(16)} endpoints=${iface.endpointCount}")
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                val type =
                    when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
                        else -> "CTRL"
                    }
                Log.i(TAG, "    ep[$j] addr=0x${ep.address.toString(16)} $dir $type maxPkt=${ep.maxPacketSize}")
            }
        }
    }

    private fun claimInputPipes(
        dev: UsbDevice,
        conn: UsbDeviceConnection,
    ): List<InputPipe> {
        val candidates =
            (0 until dev.interfaceCount)
                .map { dev.getInterface(it) }
                .filter { iface ->
                    iface.interfaceClass == UsbConstants.USB_CLASS_HID &&
                        when (dev.productId) {
                            PID_PROTEUS_PUCK, PID_NEREID_RECEIVER -> iface.id in 2..5
                            else -> true
                        }
                }

        return candidates.mapNotNull { iface ->
            val endpoint = findInEndpoint(iface) ?: return@mapNotNull null
            if (conn.claimInterface(iface, true)) {
                InputPipe(iface, endpoint, findOutEndpoint(iface))
            } else {
                Log.w(TAG, "Could not claim controller slot iface ${iface.id}")
                null
            }
        }
    }

    fun disconnect() {
        try {
            connection?.let { conn ->
                heldInterfaces.forEach { conn.releaseInterface(it) }
                dataInterfaces.forEach { conn.releaseInterface(it) }
                conn.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during disconnect: ${e.message}")
        } finally {
            heldInterfaces.clear()
            dataInterfaces.clear()
            device = null
            connection = null
            inputPipes = emptyList()
        }
    }

    private fun findInEndpoint(iface: UsbInterface): UsbEndpoint? = findEndpoint(iface, UsbConstants.USB_DIR_IN)

    private fun findOutEndpoint(iface: UsbInterface): UsbEndpoint? = findEndpoint(iface, UsbConstants.USB_DIR_OUT)

    private fun findEndpoint(
        iface: UsbInterface,
        direction: Int,
    ): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val endpoint = iface.getEndpoint(i)
            if (endpoint.direction == direction &&
                (
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK ||
                        endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT
                )
            ) {
                return endpoint
            }
        }
        return null
    }
}
