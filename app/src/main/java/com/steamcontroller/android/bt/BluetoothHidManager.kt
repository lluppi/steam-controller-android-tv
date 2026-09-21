package com.steamcontroller.android.bt

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.steamcontroller.android.input.SteamHaptics
import com.steamcontroller.android.service.Diagnostics
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SC2026 via Bluetooth LE — uses Valve's vendor GATT service (100f6c32-...).
 * The standard HID service 0x1812 is claimed by the OS and requires BLUETOOTH_PRIVILEGED.
 *
 * GATT operations are serialized via a small state machine:
 *   CONNECTED → request MTU → discover services → subscribe N chars one-by-one → send disable lizard → READY
 * Each step waits for its own callback before triggering the next. Duplicate callbacks
 * (Android Bluetooth stack sometimes fires onMtuChanged twice) are ignored.
 */
@SuppressLint("MissingPermission")
class BluetoothHidManager(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothHidManager"

        val VALVE_SERVICE_UUID: UUID = UUID.fromString("100f6c32-1735-4313-b402-38567131e5f3")
        private const val VALVE_NOTIFY_LOW: Long = 0x100f6c75L
        private const val VALVE_NOTIFY_HIGH: Long = 0x100f6c7aL
        private const val VALVE_WRITE_LOW: Long = 0x100f6cb5L
        private const val VALVE_WRITE_HIGH: Long = 0x100f6cbeL
        private const val BATTERY_CHAR_SHORT: Long = 0x100f6c78L

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        val NAME_HINTS = listOf("Steam Ctrl", "Steam Controller", "SteamController", "Valve")

        private val DISABLE_LIZARD = byteArrayOf(0x85.toByte())
        private const val DESIRED_MTU = 100

        /** How long any single handshake step may go without its callback. */
        private const val HANDSHAKE_TIMEOUT_MS = 5000L

        /** Consecutive stalled handshakes before the bond is recreated as a last resort. */
        private const val REBOND_AFTER_STALLS = 4

        /** Rate-limit for the "link is not recovering" diagnostic. */
        private const val LINK_TROUBLE_COOLDOWN_MS = 120_000L

        /** One outstanding GATT operation is allowed; retry a busy descriptor once after this. */
        private const val SUBSCRIBE_BUSY_RETRY_MS = 150L

        private const val BATTERY_POLL_INTERVAL_MS = 30_000L
        private const val BATTERY_POLL_BUSY_RETRY_MS = 750L
        private const val BATTERY_POLL_FIRST_DELAY_MS = 500L
        private const val MAX_BUSY_READ_RETRIES = 3
    }

    private enum class State { IDLE, CONNECTING, MTU_REQUESTED, DISCOVERING, SUBSCRIBING, READY }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = btManager.adapter

    private var gatt: BluetoothGatt? = null
    private var featureWriteChar: BluetoothGattCharacteristic? = null
    private var batteryChar: BluetoothGattCharacteristic? = null

    private val pendingSubs = mutableListOf<BluetoothGattCharacteristic>()
    private var subsIndex = 0
    private var subscriptionBusyRetries = 0

    @Volatile private var state: State = State.IDLE
    private val heartbeatBusy = AtomicBoolean(false)

    private var onReport: ((ByteArray) -> Unit)? = null
    private var onConnectionChange: ((Boolean) -> Unit)? = null

    // ── Auto-reconnect ─────────────────────────────────────────────────────────
    // The controller powers itself off when idle, which tears the GATT down. Retrying only
    // on a user tap left the app sitting on a dead link reporting "disconnected" until it was
    // restarted — and a controller that is bonded but unreachable looks identical to a broken
    // one. So: retry forever with backoff while the service runs, and jump the queue when the
    // ACL comes up (i.e. the controller just woke).
    private var reconnectEnabled = false
    private var reconnectScheduled = false
    private var reconnectAttempts = 0
    private var targetDevice: BluetoothDevice? = null
    private val handler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable { attemptReconnect("backoff") }
    private var batteryPollRunnable: Runnable? = null
    private var consecutiveBusyReads = 0
    private var aclReceiver: BroadcastReceiver? = null

    // Set when a handshake stalls, so the next connect clears the stack's cached GATT database
    // before re-discovering (see refreshGattCache).
    private var refreshNextConnect = false

    // Counts stalls that were not interrupted by a healthy session, for the bond-escalation below.
    private var consecutiveStalls = 0
    private var lastRebondAt = 0L

    // True once a handshake has completed in this service session. A controller that is merely
    // asleep behaves exactly like a wedged one — it answers at the link layer, then stops
    // answering GATT — so a re-bond is only justified if this pairing demonstrably worked and
    // then stopped.
    private var hadReadySession = false

    val isBluetoothAvailable: Boolean get() = adapter != null && adapter.isEnabled

    fun listPairedSteamControllers(): List<BluetoothDevice> {
        val a = adapter ?: return emptyList()
        return try {
            a.bondedDevices.orEmpty().filter { dev ->
                val n = dev.name ?: return@filter false
                NAME_HINTS.any { hint -> n.contains(hint, ignoreCase = true) }
            }
        } catch (t: SecurityException) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT: ${t.message}")
            emptyList()
        }
    }

    fun connect(
        device: BluetoothDevice,
        onReport: (ByteArray) -> Unit,
        onConnectionChange: (Boolean) -> Unit
    ) {
        this.onReport = onReport
        this.onConnectionChange = onConnectionChange
        targetDevice = device
        reconnectEnabled = true
        reconnectAttempts = 0
        registerAclReceiver(device)
        val message = "connecting GATT to ${safeName(device)} (${device.address})"
        Log.i(TAG, message)
        Diagnostics.record(TAG, message)
        state = State.CONNECTING
        // A previous client's claim may still be registered with the stack (e.g. the app was
        // killed mid-session), which makes the new connect fail with 133. Release it first.
        closeGatt()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        armWatchdog("connect")
    }

    /** Immediately rebuild only the GATT link, preserving the virtual input devices. */
    fun reconnect() {
        if (!reconnectEnabled || targetDevice == null) return
        handler.removeCallbacks(reconnectRunnable)
        reconnectScheduled = false
        reconnectAttempts = 0
        attemptReconnect("manual reconnect")
    }

    /** Backoff: 1s, 2s, 4s, 8s, 16s, then every 30s. */
    private fun reconnectDelayMs(): Long =
        (1000L shl reconnectAttempts.coerceAtMost(5)).coerceAtMost(30_000L)

    private fun scheduleReconnect(reason: String) {
        if (!reconnectEnabled || reconnectScheduled) return
        val delayMs = reconnectDelayMs()
        reconnectScheduled = true
        val message =
            "retry GATT in ${delayMs}ms ($reason), attempt ${reconnectAttempts + 1}"
        Log.i(TAG, message)
        Diagnostics.record(TAG, message)
        handler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun attemptReconnect(reason: String) {
        reconnectScheduled = false
        if (!reconnectEnabled) return
        val device = resolveTarget()
        if (device == null) {
            // Nothing is bonded. Do NOT drive createBond() from here: repeatedly starting pairing
            // keeps the Bluetooth stack busy enough to starve the rest of the system — on the
            // Shield it left the UI and even the BLE remote unresponsive until the app was
            // force-stopped. Log it and let the user pair once, which is a single clean operation.
            Log.w(TAG, "No bonded Steam Controller — pair it again from Bluetooth settings")
            return
        }
        reconnectAttempts++
        Log.i(TAG, "Reconnect attempt $reconnectAttempts ($reason) → ${safeName(device)}")
        state = State.CONNECTING
        closeGatt()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        armWatchdog("reconnect")
    }

    /** Close the current client without touching the retry loop. */
    private fun closeGatt() {
        stopBatteryPolling()
        try {
            gatt?.disconnect()
        } catch (_: Throwable) {}
        try {
            gatt?.close()
        } catch (_: Throwable) {}
        gatt = null
    }

    private fun registerAclReceiver(device: BluetoothDevice) {
        if (aclReceiver != null) return
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val changed = intent?.getParcelableExtra<BluetoothDevice>(
                        BluetoothDevice.EXTRA_DEVICE
                    )
                    if (changed?.address != device.address) return
                    if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                        // The controller is back — don't wait out the backoff.
                        Log.i(TAG, "ACL connected — retrying GATT immediately")
                        handler.removeCallbacks(reconnectRunnable)
                        reconnectScheduled = false
                        if (state != State.READY && state != State.CONNECTING) {
                            attemptReconnect("acl-connected")
                        }
                    } else {
                        Log.i(TAG, "ACL disconnected")
                    }
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
        context.registerReceiver(receiver, filter)
        aclReceiver = receiver
    }

    private fun unregisterAclReceiver() {
        aclReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Throwable) {}
        }
        aclReceiver = null
    }

    /**
     * Handshake watchdog.
     *
     * Every step of the connect handshake waits for its own callback before starting the next
     * one, and the vendor service does not always deliver one — a descriptor write that never
     * completes left the app connected but permanently deaf (OS reports the device bonded,
     * battery stays "—", nothing responds) until the service was restarted. Time the step out
     * and hand over to the reconnect loop instead of waiting forever.
     */
    private val handshakeWatchdog =
        Runnable {
            if (state == State.IDLE || state == State.READY) return@Runnable
            Log.w(TAG, "Handshake stalled in $state for ${HANDSHAKE_TIMEOUT_MS}ms — retrying")
            reconnectAttempts++
            // Only a stall *after* the link came up is the wedged-GATT signature: the controller
            // answers at the link layer and then stops answering GATT requests. A stall while
            // CONNECTING just means it is off, asleep or out of range, and recreating the bond for
            // that would throw away a perfectly good pairing.
            if (state == State.CONNECTING) {
                consecutiveStalls = 0
            } else {
                consecutiveStalls++
            }
            state = State.IDLE
            closeGatt()
            // A stalled step usually means the stack's cached handle layout is stale; drop it
            // on the way back in rather than retrying the same dead handles forever.
            refreshNextConnect = true
            if (consecutiveStalls >= REBOND_AFTER_STALLS) {
                escalateRebond("$consecutiveStalls consecutive handshake stalls")
            }
            scheduleReconnect("handshake timeout")
        }

    /**
     * Recreate the bond as a last resort.
     *
     * A GATT session lives in the system Bluetooth process, not in the app, so a client that dies
     * mid-session leaves the peripheral believing it is still connected: the next client connects
     * at the link layer and then every GATT operation stalls. Closing our own client and refreshing
     * the cached database cannot clear that — only dropping the bond does, which is exactly the
     * unpair/re-pair ritual. Doing it here means a wedged controller can recover on its own.
     *
     * Re-pairing is safe to attempt without user interaction for these controllers, and the address
     * change that follows is handled by resolveTarget(). Escalation is capped by a cooldown so a
     * persistently failing link cannot loop on it.
     */

    /**
     * Reports a handshake that keeps failing.
     *
     * This deliberately does NOT recreate the bond any more. An earlier version did, and it unpaired
     * a controller that had merely gone to sleep — a sleeping controller and a wedged one are
     * indistinguishable at the link layer (both connect, then stop answering GATT). The wedge itself
     * also turned out to be caused by this app re-initializing on top of a live connection, which is
     * now prevented. So: report it, throttle the noise, and leave the pairing alone.
     */
    private fun escalateRebond(reason: String) {
        if (!hadReadySession) {
            Log.w(
                TAG,
                "Link not recovering ($reason) — no handshake has completed with this pairing, " +
                    "so the controller is off, asleep or out of range"
            )
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRebondAt < LINK_TROUBLE_COOLDOWN_MS) return
        lastRebondAt = now
        Log.w(
            TAG,
            "Link has not recovered after a working session ($reason). Leaving the pairing " +
                "alone — if this persists, remove and re-pair the controller once."
        )
    }

    /**
     * The device to connect to, re-resolved from the bonded list if the remembered object is no
     * longer bonded — which is what happens after a re-pair gives the controller a new address.
     */
    private fun resolveTarget(): BluetoothDevice? {
        val remembered = targetDevice ?: return null
        if (remembered.bondState == BluetoothDevice.BOND_BONDED) return remembered
        val replacement = listPairedSteamControllers().firstOrNull()
        if (replacement != null) {
            Log.i(TAG, "Re-resolved controller to ${replacement.address}")
            targetDevice = replacement
        }
        return replacement
    }

    /**
     * Clear this device's cached GATT database in the Bluetooth stack.
     *
     * The stack caches services/characteristics per bonded device. When that cache no longer
     * matches the peripheral's handle layout, a descriptor write is sent to a stale handle and
     * no callback ever arrives — the device then looks permanently broken (bonded, connects,
     * then silence) and only an unpair/re-pair, which also drops the cache, appears to fix it.
     * `refresh()` is hidden API, so it is called reflectively; if it is unavailable the retry
     * loop still applies, so a failure here is harmless.
     */
    private fun refreshGattCache(g: BluetoothGatt): Boolean = try {
        (g.javaClass.getMethod("refresh").invoke(g) as? Boolean) ?: false
    } catch (t: Throwable) {
        Log.w(TAG, "gatt.refresh() unavailable: ${t.message}")
        false
    }

    private fun armWatchdog(step: String) {
        handler.removeCallbacks(handshakeWatchdog)
        Log.v(TAG, "Handshake step: $step")
        handler.postDelayed(handshakeWatchdog, HANDSHAKE_TIMEOUT_MS)
    }

    private fun clearWatchdog() {
        handler.removeCallbacks(handshakeWatchdog)
    }

    private fun startBatteryPolling(g: BluetoothGatt) {
        stopBatteryPolling()
        val characteristic = batteryChar ?: return
        val poll =
            object : Runnable {
                override fun run() {
                    if (state != State.READY || gatt !== g) return
                    val accepted =
                        try {
                            g.readCharacteristic(characteristic)
                        } catch (t: Throwable) {
                            Log.w(TAG, "Battery read threw: ${t.message}")
                            false
                        }
                    consecutiveBusyReads = if (accepted) 0 else consecutiveBusyReads + 1
                    if (consecutiveBusyReads == MAX_BUSY_READ_RETRIES) {
                        Log.w(TAG, "Battery reads repeatedly refused; backing off")
                    }
                    val delay =
                        if (consecutiveBusyReads in 1 until MAX_BUSY_READ_RETRIES) {
                            BATTERY_POLL_BUSY_RETRY_MS
                        } else {
                            BATTERY_POLL_INTERVAL_MS
                        }
                    handler.postDelayed(this, delay)
                }
            }
        batteryPollRunnable = poll
        handler.postDelayed(poll, BATTERY_POLL_FIRST_DELAY_MS)
    }

    private fun stopBatteryPolling() {
        batteryPollRunnable?.let(handler::removeCallbacks)
        batteryPollRunnable = null
        consecutiveBusyReads = 0
    }

    fun disconnect() {
        // Explicit teardown: stop retrying, or the loop resurrects the link the caller just cut.
        reconnectEnabled = false
        reconnectScheduled = false
        handler.removeCallbacks(reconnectRunnable)
        clearWatchdog()
        unregisterAclReceiver()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "disconnect: ${t.message}")
        } finally {
            gatt = null
            featureWriteChar = null
            batteryChar = null
            stopBatteryPolling()
            pendingSubs.clear()
            subsIndex = 0
            subscriptionBusyRetries = 0
            state = State.IDLE
            onConnectionChange?.invoke(false)
        }
    }

    /**
     * Send a rumble command to the controller.
     * Magnitudes are Android FF values (0..65535) — strong = left motor, weak = right.
     *
     * REVERTED (2026-07-12): tried switching to OUT_HAPTIC_RUMBLE (0x80), the "modern"
     * continuous-haptic output report documented by github.com/ddeverill/SteamlessController
     * (SteamController.cpp SendRumbleOutput). On real SC2026 hardware over this BLE
     * characteristic it produced NO vibration at all, while this 0x8F pulse format DOES
     * (confirmed on hardware, feel not yet tuned). Likely explanation: 0x80/0x81/0x82 are
     * only valid as genuine USB HID *output* reports (a separate report channel from the
     * feature-report/raw-command scheme), which doesn't necessarily exist as a raw
     * writable command over this vendor GATT characteristic. 0x8F is the older SC1-style
     * direct command (hid-steam.c HAPTIC_PULSE) which the SC2026 firmware apparently still
     * honors here. Do not retry 0x80 without first confirming (via hardware log/sniff)
     * that a *different* characteristic in the 100f6cb5-be write range is meant for it.
     *
     * Payload format inspired by the Linux `hid-steam` driver (steam_haptic_pulse):
     *   byte 0: command id (0x8F = HAPTIC_PULSE)
     *   byte 1: pad id (0 = left, 1 = right)
     *   bytes 2-3: high period (u16 LE, microseconds — actuator ON time per cycle)
     *   bytes 4-5: low period  (u16 LE, microseconds — actuator OFF time per cycle)
     *   bytes 6-7: repeat count (u16 LE, 0xFFFF for continuous)
     *
     * Magnitude is encoded by the ratio high/low, repeated continuously (repeat=0xFFFF):
     *   magnitude 0xFFFF → high=2000us, low=1000us  (~66% duty, full strength)
     *   magnitude 0x0000 → explicit stop (repeat=0)
     */
    fun sendRumble(strong: Int, weak: Int) {
        if (state != State.READY) return
        val ch = featureWriteChar ?: return
        val g = gatt ?: return

        // Send left then right. WRITE_NO_RESPONSE so they don't queue up acks.
        for (payload in listOf(SteamHaptics.payload(0, strong), SteamHaptics.payload(1, weak))) {
            try {
                ch.value = payload
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                g.writeCharacteristic(ch)
            } catch (t: Throwable) {
                Log.w(TAG, "rumble write failed: ${t.message}")
            }
        }
    }

    fun sendHeartbeat() {
        if (state != State.READY) return
        val g = gatt ?: return
        val ch = featureWriteChar ?: return
        if (!heartbeatBusy.compareAndSet(false, true)) return // previous heartbeat not yet acked
        try {
            ch.value = DISABLE_LIZARD
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val ok = g.writeCharacteristic(ch)
            if (!ok) {
                heartbeatBusy.set(false)
                Log.v(TAG, "heartbeat skipped: writeCharacteristic returned false")
            }
        } catch (t: Throwable) {
            heartbeatBusy.set(false)
            Log.w(TAG, "heartbeat write failed: ${t.message}")
        }
    }

    private fun isStaleGatt(g: BluetoothGatt): Boolean {
        if (g === gatt) return false
        Log.v(TAG, "Ignoring callback from replaced GATT client")
        try {
            g.close()
        } catch (_: Throwable) {
        }
        return true
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (isStaleGatt(g)) return
            val message = "connection state status=$status newState=$newState"
            Log.i(TAG, message)
            Diagnostics.record(TAG, message)
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onConnectionChange?.invoke(true)
                    if (refreshNextConnect) {
                        refreshNextConnect = false
                        Log.i(TAG, "Refreshing cached GATT database before re-discovering")
                        refreshGattCache(g)
                    }
                    // Request a tight connection interval (11.25–15ms) to minimize input latency.
                    // Default is ~50ms which is fine for sensors but laggy for gamepads.
                    val priOk = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    Log.i(TAG, "requestConnectionPriority(HIGH) → $priOk")

                    if (state == State.CONNECTING) {
                        state = State.MTU_REQUESTED
                        armWatchdog("request-mtu")
                        val ok = g.requestMtu(DESIRED_MTU)
                        if (!ok) {
                            Log.w(
                                TAG,
                                "requestMtu($DESIRED_MTU) returned false, skipping to discover"
                            )
                            state = State.DISCOVERING
                            g.discoverServices()
                        }
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    onConnectionChange?.invoke(false)
                    try {
                        g.close()
                    } catch (_: Throwable) {}
                    gatt = null
                    featureWriteChar = null
                    stopBatteryPolling()
                    pendingSubs.clear()
                    subsIndex = 0
                    subscriptionBusyRetries = 0
                    state = State.IDLE
                    clearWatchdog()
                    scheduleReconnect("disconnected status=$status")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (isStaleGatt(g)) return
            Log.i(TAG, "onMtuChanged: mtu=$mtu status=$status (state=$state)")
            // Guard: this callback is sometimes fired twice on Android. Only act once.
            if (state != State.MTU_REQUESTED) return
            state = State.DISCOVERING
            val ok = g.discoverServices()
            Log.i(TAG, "discoverServices → $ok")
            armWatchdog("discover-services")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (isStaleGatt(g)) return
            Log.i(TAG, "onServicesDiscovered status=$status (state=$state)")
            if (state != State.DISCOVERING) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed")
                return
            }

            // Log everything once for empirical validation
            for (svc in g.services) {
                Log.i(TAG, "Service ${svc.uuid}")
                for (ch in svc.characteristics) {
                    Log.i(TAG, "  Char ${ch.uuid}  ${describeProps(ch.properties)}")
                }
            }

            val valve = g.getService(VALVE_SERVICE_UUID)
            if (valve == null) {
                Log.e(TAG, "Valve vendor service not found")
                return
            }

            pendingSubs.clear()
            featureWriteChar = null
            batteryChar = null
            for (ch in valve.characteristics) {
                val short = shortUuid(ch.uuid) ?: continue
                val canNotify = (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                val canWrite = (
                    ch.properties and (
                        BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                        )
                    ) != 0

                if (canNotify && short in VALVE_NOTIFY_LOW..VALVE_NOTIFY_HIGH) {
                    pendingSubs.add(ch)
                }
                if (short == BATTERY_CHAR_SHORT) {
                    batteryChar = ch
                }
                if (canWrite &&
                    short in VALVE_WRITE_LOW..VALVE_WRITE_HIGH &&
                    featureWriteChar == null
                ) {
                    featureWriteChar = ch
                }
            }
            Log.i(
                TAG,
                "Found ${pendingSubs.size} notify chars; feature-write=${featureWriteChar?.uuid}"
            )

            subsIndex = 0
            state = State.SUBSCRIBING
            subscribeNext(g)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (isStaleGatt(g)) return
            Log.v(
                TAG,
                "onDescriptorWrite ${descriptor.uuid} status=$status (state=$state, idx=$subsIndex/${pendingSubs.size})"
            )
            subscriptionBusyRetries = 0
            if (state == State.SUBSCRIBING) subscribeNext(g)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (isStaleGatt(g)) return
            heartbeatBusy.set(false)
            if (status != 0) Log.w(TAG, "Write ${ch.uuid} failed: status=$status")
            // The disable-lizard write is the last handshake step.
            if (state == State.READY) clearWatchdog()
        }

        // Deprecated 3-arg overload (not the API 33+ byte[]-carrying one) — minSdk 26 means
        // the OS-side BluetoothGatt implementation on most devices only ever calls this one.
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (isStaleGatt(g)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Battery read failed: status=$status")
                return
            }
            clearWatchdog()
            val data = ch.value ?: return
            if (shortUuid(ch.uuid) != BATTERY_CHAR_SHORT) return
            if (data.size < 2) {
                Log.w(TAG, "Battery read too short (${data.size}B)")
                return
            }
            onReport?.invoke(byteArrayOf(0x43.toByte(), data[1], 0x00))
        }

        private var reportCounter = 0
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (isStaleGatt(g)) return
            val data = ch.value ?: return
            reportCounter++
            val short = shortUuid(ch.uuid)
            // BLE strips the HID Report ID prefix; prepend it back to reuse the USB parser.
            // State reports (>=40 bytes) get 0x45.
            //
            // Confirmed on hardware (2026-07-12 logcat capture) — two distinct short reports,
            // neither matching the 2-byte guess originally assumed here:
            //  - 100f6c78, 14 bytes, e.g. "01 5d ff 0f 2c 10 00 00 00 00 00 00 14 75": byte[1]
            //    (0x5d = 93) stays constant across samples seconds apart while every other
            //    byte fluctuates (counter/checksum) — almost certainly the battery percent.
            //    Re-prefixed as 0x43 to reuse SteamReportParser.parseBatteryStatus.
            //  - 100f6c79, 5 bytes, alternating "01 02 00 00 00" / "00 02 00 00 00" in
            //    lockstep with our 800ms heartbeat write — an ack/status ping-pong tied to
            //    writes, NOT battery. Forwarded as-is (unparsed).
            val toForward: ByteArray = when {
                data.size >= 40 -> {
                    val withId = ByteArray(data.size + 1)
                    withId[0] = 0x45
                    System.arraycopy(data, 0, withId, 1, data.size)
                    withId
                }

                short == BATTERY_CHAR_SHORT && data.size == 14 -> byteArrayOf(
                    0x43.toByte(),
                    data[1],
                    0x00
                )

                else -> {
                    Log.v(
                        TAG,
                        "Unrecognized short report (${data.size}B) from ${ch.uuid}: " +
                            data.joinToString(" ") { "%02x".format(it) }
                    )
                    data
                }
            }
            // Log only the first report and one every 1000 (Hz check) — way less spammy
            if (reportCounter == 1 || reportCounter % 1000 == 0) {
                Log.i(TAG, "Report #$reportCounter from ${ch.uuid}: ${data.size} bytes")
            }
            onReport?.invoke(toForward)
        }
    }

    private fun subscribeNext(g: BluetoothGatt) {
        // Covers each subscription step and the disable-lizard write that follows the last one.
        armWatchdog("subscribe idx=$subsIndex/${pendingSubs.size}")
        if (subsIndex >= pendingSubs.size) {
            // All subscriptions done — send disable lizard mode
            Log.i(TAG, "All ${pendingSubs.size} subscriptions complete, sending disable lizard")
            val ch = featureWriteChar
            state = State.READY
            // Healthy again — the next outage starts its backoff from 1s, and a later wedge has to
            // earn its own escalation.
            reconnectAttempts = 0
            consecutiveStalls = 0
            hadReadySession = true
            if (ch == null) {
                Log.w(TAG, "No feature write char; skipping disable lizard")
                startBatteryPolling(g)
                return
            }
            ch.value = DISABLE_LIZARD
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val ok = g.writeCharacteristic(ch)
            Log.i(TAG, "Disable lizard write: $ok")
            startBatteryPolling(g)
            return
        }

        val ch = pendingSubs[subsIndex++]
        val nOk = g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            Log.w(TAG, "No CCCD on ${ch.uuid}, skipping")
            subscribeNext(g)
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val wOk = g.writeDescriptor(cccd)
        Log.v(TAG, "Subscribe ${ch.uuid} idx=${subsIndex - 1} setNotify=$nOk writeDesc=$wOk")
        if (!wOk) {
            if (subscriptionBusyRetries == 0) {
                // A rejected write normally means another GATT operation is still in flight.
                // Retry this characteristic once instead of cascading the same failure through
                // every remaining subscription.
                subscriptionBusyRetries++
                subsIndex--
            } else {
                Log.w(TAG, "Giving up on ${ch.uuid} after a busy retry")
                subscriptionBusyRetries = 0
            }
            handler.postDelayed(
                { if (state == State.SUBSCRIBING) subscribeNext(g) },
                SUBSCRIBE_BUSY_RETRY_MS
            )
        }
    }

    private fun shortUuid(uuid: UUID): Long? {
        val s = uuid.toString()
        if (!s.endsWith("-1735-4313-b402-38567131e5f3")) return null
        return try {
            java.lang.Long.parseLong(s.substring(0, 8), 16)
        } catch (_: Throwable) {
            null
        }
    }

    private fun describeProps(p: Int): String {
        val parts = mutableListOf<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) parts += "READ"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) parts += "WRITE"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts += "WRITE_NR"
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) parts += "NOTIFY"
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) parts += "INDICATE"
        return parts.joinToString("|").ifEmpty { "—" }
    }

    private fun safeName(device: BluetoothDevice): String = try {
        device.name ?: "?"
    } catch (_: SecurityException) {
        "?"
    }
}
