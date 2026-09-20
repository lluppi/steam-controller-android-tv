package com.steamcontroller.android.service

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.R
import com.steamcontroller.android.Transport
import com.steamcontroller.android.bt.BluetoothHidManager
import com.steamcontroller.android.input.GamepadMapper
import com.steamcontroller.android.input.ShizukuInputInjector
import com.steamcontroller.android.parser.Buttons
import com.steamcontroller.android.parser.SteamControllerState
import com.steamcontroller.android.parser.SteamReportParser
import com.steamcontroller.android.service.UsageStatsHelper
import com.steamcontroller.android.uinput.UInputGamepad
import com.steamcontroller.android.uinput.UInputNative
import com.steamcontroller.android.usb.HidReportReader
import com.steamcontroller.android.usb.SteamHidProtocol
import com.steamcontroller.android.usb.UsbConnectionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ControllerService : Service() {
    enum class InjectionMode {
        NONE,
        UINPUT,
        UHID,
        SHIZUKU_INJECT
        ;

        /**
         * True when frames go through a real virtual input device. UINPUT and UHID are
         * interchangeable from the app's point of view — they differ only in which kernel
         * interface the shell-UID service used to create the device (see cpp/output_backend.h).
         */
        val isVirtualDevice: Boolean get() = this == UINPUT || this == UHID
    }

    /** What the UI should say about the link to the controller. */
    enum class LinkState {
        /** Nothing usable: service not started, or no controller paired. */
        DISCONNECTED,

        /** Frames are arriving: the only state that earns a green status card. */
        LINKED,

        /**
         * Connected at the Bluetooth level but no frames arriving. This is where the controller
         * sits when it has gone to sleep, or when the GATT handshake stalled — the old UI showed
         * a healthy card through both, which looks identical to a broken device.
         */
        STALE
    }

    data class LinkStatus(val state: LinkState, val detail: String)

    companion object {
        private const val TAG = "ControllerService"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "steam_controller"
        const val EXTRA_DEVICE = "usb_device"
        const val ACTION_STOP = "com.steamcontroller.android.STOP"
        const val ACTION_NEXT_PROFILE = "com.steamcontroller.android.NEXT_PROFILE"
        const val ACTION_TEST_RUMBLE = "com.steamcontroller.android.TEST_RUMBLE"
        private const val TEST_RUMBLE_DURATION_MS = 5000L

        /** How often the link-liveness status is recomputed. */
        private const val LINK_POLL_MS = 1500L

        /** No frames for this long means the link is not actually delivering. */
        private const val FRAME_STALE_MS = 3000L

        // Observed by DebugActivity / MainActivity for live display
        private val _stateFlow = MutableStateFlow<SteamControllerState?>(null)
        val stateFlow: StateFlow<SteamControllerState?> = _stateFlow.asStateFlow()

        private val _rawReportFlow = MutableStateFlow<ByteArray?>(null)
        val rawReportFlow: StateFlow<ByteArray?> = _rawReportFlow.asStateFlow()

        private val _modeFlow = MutableStateFlow(InjectionMode.NONE)
        val modeFlow: StateFlow<InjectionMode> = _modeFlow.asStateFlow()

        // Emits the active emulated profile id whenever it changes (start, cycle from notif, etc.)
        private val _profileFlow = MutableStateFlow<Int?>(null)
        val profileFlow: StateFlow<Int?> = _profileFlow.asStateFlow()

        // Controller battery as 0..100, or null if unknown. Updated when a HID report carries it.
        private val _batteryFlow = MutableStateFlow<Int?>(null)
        val batteryFlow: StateFlow<Int?> = _batteryFlow.asStateFlow()

        // Which output backend the shell-UID service selected, and what it saw while
        // probing. Surfaced by the UI so a backend downgrade is never silent.
        private val _backendIdFlow = MutableStateFlow(UInputNative.Backend.NONE)
        val backendIdFlow: StateFlow<Int> = _backendIdFlow.asStateFlow()

        private val _backendDetailFlow = MutableStateFlow("")
        val backendDetailFlow: StateFlow<String> = _backendDetailFlow.asStateFlow()

        // Whether games can rumble through the active backend. Defaults to true so the
        // control stays usable until a backend is actually chosen.
        private val _rumbleSupportedFlow = MutableStateFlow(true)
        val rumbleSupportedFlow: StateFlow<Boolean> = _rumbleSupportedFlow.asStateFlow()

        // What the UI should say about the link to the controller.
        private val _linkStatusFlow =
            MutableStateFlow(LinkStatus(LinkState.DISCONNECTED, "not started"))
        val linkStatusFlow: StateFlow<LinkStatus> = _linkStatusFlow.asStateFlow()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var usbManager: UsbConnectionManager
    private lateinit var btManager: BluetoothHidManager
    private val legacyInjector = ShizukuInputInjector()
    private lateinit var uinput: UInputGamepad
    private var mode: InjectionMode = InjectionMode.NONE

    // Guards against a duplicate start re-initializing on top of a live connection: a second GATT
    // client against a controller that accepts one makes the handshake stall at the first
    // subscription, which presents as "the controller went unresponsive".
    private var initializing = false
    private var initializedTransport: Transport? = null

    // Link health. `lastFrameAt` is the only honest signal that the controller is actually there;
    // `btConnected` is what the Bluetooth stack thinks, which can be true on a link that is
    // delivering nothing.
    @Volatile private var lastFrameAt = 0L

    @Volatile private var btConnected = false

    // Diagnostic for the trackpad clicks (see onHidFrame).
    private var lastRawLogButtons = 0
    private var lastRawLogMs = 0L
    private var reader: HidReportReader? = null
    private var heartbeatJob: Job? = null

    // Bits whose changes must pass through the debounce mechanism before being injected.
    // Mechanical switches only — the SC2026's grip squeeze sensors (GRIP_LT/RT), trackpad
    // touch flags (TP_*) and stick touch flags (LS_TOUCH/RS_TOUCH) are capacitive and
    // inherently noisy when the controller is held; if those are mapped, the debounce
    // counter will keep resetting and the mapping will misbehave (known limitation).
    // L4/L5/R4/R5 ARE included — they are real mechanical back-paddle switches.
    private val INJECTABLE_MASK =
        Buttons.A or Buttons.B or Buttons.X or Buttons.Y or
            Buttons.LB or Buttons.RB or
            Buttons.LT_FULL or Buttons.RT_FULL or
            Buttons.MENU or Buttons.VIEW or Buttons.STEAM or Buttons.QUICK_ACCESS or
            Buttons.LS or Buttons.RS or
            Buttons.L4 or Buttons.L5 or Buttons.R4 or Buttons.R5 or
            Buttons.DPAD_UP or Buttons.DPAD_DOWN or Buttons.DPAD_LEFT or Buttons.DPAD_RIGHT

    // Debounce window. USB=333Hz so 5 frames = ~15ms. BT=~150Hz so 3 frames = ~20ms.
    // Tuned to filter capacitive noise without adding perceptible button latency.
    private val DEBOUNCE_FRAMES = 3
    private var confirmedState: SteamControllerState? = null
    private var pendingButtons = 0
    private var pendingFrames = 0

    override fun onCreate() {
        super.onCreate()
        usbManager = UsbConnectionManager(this)
        btManager = BluetoothHidManager(this)
        uinput = UInputGamepad(this, Prefs.getProfile(this))
        uinput.onRumble = { strong, weak -> forwardRumble(strong, weak) }
        createNotificationChannel()
        _profileFlow.value = Prefs.getProfile(this).id

        // Refresh the foreground notification whenever the controller's battery level changes.
        // StateFlow only emits on actual value changes, so this triggers ~once per percent dropped.
        scope.launch {
            _batteryFlow.collect { refreshNotification() }
        }

        startForegroundAppMonitor()

        // Link liveness ticker. Drives the honest status card: frames in the last few seconds, or
        // an explicit reason why not.
        scope.launch {
            while (isActive) {
                refreshLinkStatus()
                delay(LINK_POLL_MS)
            }
        }
    }

    /**
     * Recompute what to tell the user about the link.
     *
     * Deliberately pessimistic: `LINKED` requires recent frames, because a bonded controller that
     * is silent is indistinguishable from a broken one and the UI used to show a green card either
     * way.
     */
    private fun refreshLinkStatus() {
        val status =
            when {
                mode == InjectionMode.NONE ->
                    LinkStatus(LinkState.DISCONNECTED, "service stopped")

                mode == InjectionMode.SHIZUKU_INJECT ->
                    LinkStatus(LinkState.STALE, "inject fallback — most apps ignore input")

                lastFrameAt != 0L &&
                    android.os.SystemClock.uptimeMillis() - lastFrameAt < FRAME_STALE_MS ->
                    LinkStatus(LinkState.LINKED, "")

                btManager.listPairedSteamControllers().isEmpty() ->
                    LinkStatus(
                        LinkState.DISCONNECTED,
                        "no Steam Controller paired — pair it in Bluetooth settings"
                    )

                !btConnected ->
                    LinkStatus(
                        LinkState.DISCONNECTED,
                        "controller not connected — press Steam to wake it"
                    )

                else ->
                    LinkStatus(
                        LinkState.STALE,
                        "link stalled — controller asleep, or hold B + R1 + Steam for a blue LED"
                    )
            }
        _linkStatusFlow.value = status
    }

    // ─── Foreground-app auto-switch (V1.2 Phase 2b) ────────────────────────────
    private var foregroundAppMonitorJob: Job? = null
    private var lastForegroundPackage: String? = null

    /**
     * Poll UsageStatsManager every 1.5s for the focused app. On change, look up
     * a matching named-profile binding and live-switch the gamepad profile.
     * Silently inert if the user hasn't granted PACKAGE_USAGE_STATS.
     */
    private fun startForegroundAppMonitor() {
        foregroundAppMonitorJob?.cancel()
        foregroundAppMonitorJob =
            scope.launch {
                while (isActive) {
                    try {
                        tickForegroundAppMonitor()
                    } catch (t: Throwable) {
                        Log.w(TAG, "Foreground monitor tick failed: ${t.message}")
                    }
                    delay(1500)
                }
            }
    }

    private var loggedNoUsagePerm = false

    private fun tickForegroundAppMonitor() {
        if (!UsageStatsHelper.hasPermission(this)) {
            if (!loggedNoUsagePerm) {
                Log.w(TAG, "Auto-switch inert: PACKAGE_USAGE_STATS not granted")
                loggedNoUsagePerm = true
            }
            return
        }
        loggedNoUsagePerm = false

        val current = UsageStatsHelper.getCurrentForegroundApp(this) ?: return
        if (current == lastForegroundPackage) return
        Log.v(TAG, "Foreground changed: $lastForegroundPackage → $current")
        lastForegroundPackage = current
        // Ignore self — opening our own UI shouldn't trigger anything.
        if (current == packageName) return

        val bound =
            Prefs
                .listNamedProfiles(this)
                .firstOrNull { current in it.boundPackages }
        if (bound == null) {
            Log.v(TAG, "  no profile bound to $current")
            return
        }

        // Gate: skip only if BOTH the active named-profile id AND the live emulated
        // gamepad already match the binding. Without the live-profile check we'd skip
        // when the user has manually picked a different gamepad variant via the radios
        // (which doesn't clear activeNamedProfileId).
        val liveProfileMatches = Prefs.getProfile(this).id == bound.profileId
        val activeMatches = Prefs.getActiveNamedProfileId(this) == bound.id
        if (activeMatches && liveProfileMatches) {
            Log.v(TAG, "  '${bound.name}' already applied — skipping")
            return
        }

        Log.i(
            TAG,
            "Auto-switch → '${bound.name}' (foreground=$current, activeMatches=$activeMatches, liveMatches=$liveProfileMatches)"
        )
        Prefs.applyNamedProfile(this, bound)
        announceProfileLoaded(bound.name)

        // Live-swap the gamepad profile (only meaningful for a virtual device).
        if (mode.isVirtualDevice && !profileSwitchInFlight) {
            profileSwitchInFlight = true
            scope.launch {
                try {
                    val gp =
                        com.steamcontroller.android.uinput.GamepadProfile
                            .fromId(bound.profileId)
                    uinput.switchProfile(gp)
                    _profileFlow.value = bound.profileId
                    refreshNotification()
                } finally {
                    profileSwitchInFlight = false
                }
            }
        } else {
            _profileFlow.value = bound.profileId
            refreshNotification()
        }
    }

    /**
     * Surface the auto-switch to the user via:
     *  1. A LENGTH_LONG Toast on the main thread (cheapest signal, shows over the
     *     launching app — might be missed if the user is head-down, hence #2).
     *  2. The foreground-service notification text gets the profile name appended
     *     (persistent until the next swap), so the user can always pull the shade
     *     to confirm which preset is live.
     */
    private fun announceProfileLoaded(profileName: String) {
        Log.i(TAG, "announceProfileLoaded: $profileName")
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            android.widget.Toast
                .makeText(
                    applicationContext,
                    "Game Profile loaded: $profileName",
                    android.widget.Toast.LENGTH_LONG
                ).show()
        }
        // Notification refresh happens via refreshNotification() in the caller
        // after _profileFlow.value is updated.
    }

    private var lastRumbleStrong = -1
    private var lastRumbleWeak = -1
    private var lastRumbleSentAt = 0L

    /**
     * Manual rumble test: pulse both motors at full strength for TEST_RUMBLE_DURATION_MS,
     * then stop. Triggered by the "Test rumble" button in CalibrationActivity.
     */
    private fun testRumble() {
        Log.i(TAG, "Test rumble requested")
        // Bypass the throttle by resetting last-sent timestamps
        lastRumbleSentAt = 0
        forwardRumble(0xFFFF, 0xFFFF)
        scope.launch {
            delay(TEST_RUMBLE_DURATION_MS)
            lastRumbleSentAt = 0
            forwardRumble(0, 0)
        }
    }

    /**
     * Called when a game triggers a rumble effect on the virtual gamepad.
     * Magnitudes are 0..65535. Forwarded to the controller via the active transport.
     *
     * Throttled: we re-send at most every 50ms if the magnitudes change, or every
     * 200ms if they're the same (keep-alive for long-lasting effects).
     */
    private fun forwardRumble(strong: Int, weak: Int) {
        // Apply user-configured intensity (0..100% of game-requested magnitude)
        val intensity = Prefs.getRumbleIntensity(this)
        val scaledStrong = (strong * intensity / 100).coerceIn(0, 0xFFFF)
        val scaledWeak = (weak * intensity / 100).coerceIn(0, 0xFFFF)

        val now = System.currentTimeMillis()
        val changed = (scaledStrong != lastRumbleStrong || scaledWeak != lastRumbleWeak)
        val tooSoon = (now - lastRumbleSentAt) < (if (changed) 50 else 200)
        if (tooSoon) return
        lastRumbleStrong = scaledStrong
        lastRumbleWeak = scaledWeak
        lastRumbleSentAt = now

        Log.v(
            TAG,
            "Rumble → controller: strong=$scaledStrong weak=$scaledWeak (intensity=$intensity%)"
        )
        when (Prefs.getTransport(this)) {
            Transport.BLUETOOTH -> {
                btManager.sendRumble(scaledStrong, scaledWeak)
            }

            Transport.USB -> {
                // USB rumble = feature report via controlTransfer. Not implemented yet —
                // requires identifying the exact SC2026 feature report ID (likely 0x8F
                // per hid-steam.c) and payload format. Same byte structure as BT.
                Log.v(TAG, "USB rumble not implemented yet")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_NEXT_PROFILE) {
            cycleProfile()
            return START_STICKY
        }

        if (intent?.action == ACTION_TEST_RUMBLE) {
            testRumble()
            return START_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        val device =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_DEVICE)
            }

        // A duplicate start must be a no-op — see the fields above. A start for a *different*
        // transport is a deliberate restart (the UI tells the user to restart to apply a transport
        // change), so that one is allowed through.
        val requestedTransport = Prefs.getTransport(this)
        if (initializing ||
            (mode != InjectionMode.NONE && initializedTransport == requestedTransport)
        ) {
            Log.i(
                TAG,
                "Ignoring duplicate start (initializing=$initializing, mode=$mode, " +
                    "transport=$requestedTransport)"
            )
            return START_STICKY
        }

        scope.launch { initialize(device) }
        return START_STICKY
    }

    private suspend fun initialize(device: UsbDevice?) {
        val transport = Prefs.getTransport(this@ControllerService)
        Log.i(TAG, "Initializing with transport=$transport")
        chooseInjectionMode()

        val ok =
            when (transport) {
                Transport.USB -> initUsb(device)
                Transport.BLUETOOTH -> initBluetooth()
            }
        if (!ok) {
            Log.e(TAG, "Transport init failed, stopping service")
            initializing = false
            initializedTransport = null
            stopSelf()
            return
        }
        initializing = false
        initializedTransport = transport
        Log.i(TAG, "Controller service running, injection mode = $mode")
    }

    private suspend fun initUsb(device: UsbDevice?): Boolean {
        val dev = device ?: usbManager.findSteamController()
        if (dev == null) {
            Log.e(TAG, "No Steam Controller found over USB")
            return false
        }
        if (!usbManager.connect(dev)) {
            Log.e(TAG, "Failed to connect USB device")
            return false
        }
        val conn = usbManager.connection!!
        val ep = usbManager.endpointIn!!

        SteamHidProtocol.disableLizardMode(conn)

        heartbeatJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(800)
                    SteamHidProtocol.heartbeat(conn)
                }
            }

        reader =
            HidReportReader(
                conn,
                ep,
                onReport = { state, raw -> onHidFrame(state, raw) },
                onError = { msg -> Log.e(TAG, "USB read error: $msg") }
            )
        reader?.start(scope)
        return true
    }

    private fun initBluetooth(): Boolean {
        if (!btManager.isBluetoothAvailable) {
            Log.e(TAG, "Bluetooth disabled or unavailable")
            return false
        }
        val address = Prefs.getBluetoothAddress(this)
        if (address == null) {
            Log.e(TAG, "No paired Bluetooth Steam Controller selected")
            return false
        }

        // Resolve the controller from the bonded list instead of trusting the cached address.
        // A BLE peripheral is given a fresh random address every time it is paired, so after a
        // re-pair the cached address refers to a device that no longer exists: getRemoteDevice()
        // still returns an object for it, connectGatt() then never succeeds, and the app retries
        // forever. This is why clearing app data used to be part of the fix-up ritual.
        val bonded = btManager.listPairedSteamControllers()
        val device = bonded.firstOrNull { it.address == address } ?: bonded.firstOrNull()
        if (device == null) {
            Log.e(TAG, "No bonded Steam Controller found (cached address: $address)")
            return false
        }
        if (device.address != address) {
            Log.i(TAG, "Controller address changed ($address → ${device.address}), updating")
            Prefs.setBluetoothAddress(this, device.address)
        }

        btManager.connect(
            device,
            onReport = { raw ->
                val state = SteamReportParser.parse(raw) ?: SteamReportParser.parseRaw(raw)
                onHidFrame(state, raw)
            },
            onConnectionChange = { connected ->
                btConnected = connected
                Log.i(TAG, "BT connection state: $connected")
                refreshLinkStatus()
            }
        )

        heartbeatJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(800)
                    btManager.sendHeartbeat()
                }
            }
        return true
    }

    private fun onHidFrame(state: SteamControllerState, raw: ByteArray) {
        _stateFlow.value = state
        _rawReportFlow.value = raw

        // Frames are the proof of life. Flip to LINKED immediately rather than waiting for the
        // ticker, so a reconnected controller goes green the moment it starts talking.
        lastFrameAt = android.os.SystemClock.uptimeMillis()
        if (_linkStatusFlow.value.state != LinkState.LINKED) refreshLinkStatus()

        // Diagnostic: the left trackpad click does not show up in the button field at all (the
        // mask is unchanged across left-pad clicks), so dump the whole state report whenever the
        // mask changes. Diffing these against a click identifies the byte that carries it.
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (state.buttons != lastRawLogButtons || nowMs - lastRawLogMs > 5000L) {
            lastRawLogButtons = state.buttons
            lastRawLogMs = nowMs
            Log.i(
                TAG,
                "raw[${raw.size}] mask=0x${state.buttons.toString(16)} " +
                    raw.joinToString(" ") { "%02x".format(it) }
            )
        }

        // Dedicated battery/charge report (id 0x43) — works on both USB and BT,
        // percent is already 0-100. Sole battery source: bytes 44-45 of the 0x45 state
        // report were assumed to be a static battery field but turned out to be live,
        // fast-changing data (empirically: flickers 0%/99% on USB), so that guess isn't used.
        SteamReportParser.parseBatteryStatus(raw)?.let { status ->
            if (_batteryFlow.value != status.percent) _batteryFlow.value = status.percent
        }

        if (raw.isNotEmpty() && (raw[0].toInt() and 0xFF) == 0x45) {
            handleState(state)
        }
    }

    // Try a virtual-output backend first (a real InputDevice, so games honour it): uinput
    // where the shell UID may open /dev/uinput, uhid where it may not. Fall back to inject.
    private suspend fun chooseInjectionMode() {
        // 1. Virtual output device via the Shizuku user service.
        try {
            uinput.bind()
            for (attempt in 0 until 20) {
                if (uinput.isReady) break
                delay(150)
            }
            // Published before setMode() so the UI label can read both when the mode
            // change reaches it — see MainActivity.refreshModeLabel().
            _backendIdFlow.value = uinput.backendId
            _backendDetailFlow.value = uinput.backendDetail
            if (uinput.isReady) {
                setMode(
                    if (uinput.backendId == UInputNative.Backend.UHID) {
                        InjectionMode.UHID
                    } else {
                        InjectionMode.UINPUT
                    }
                )
                Log.i(
                    TAG,
                    "Using ${UInputNative.backendName(uinput.backendId)} virtual gamepad " +
                        "(${Prefs.getProfile(
                            this@ControllerService
                        ).displayName}) — ${uinput.backendDetail}"
                )
                return
            }
            Log.w(
                TAG,
                "No virtual output backend (${uinput.backendDetail}), falling back to inject"
            )
            uinput.unbind()
        } catch (t: Throwable) {
            _backendDetailFlow.value = "virtual output error: ${t.message}"
            Log.w(TAG, "virtual output bind failed: ${t.message}, falling back to inject")
        }

        // 2. Fallback: legacy injectInputEvent via Shizuku reflection
        if (legacyInjector.init()) {
            setMode(InjectionMode.SHIZUKU_INJECT)
            Log.i(TAG, "Using legacy Shizuku injectInputEvent (games may filter this)")
        } else {
            setMode(InjectionMode.NONE)
            Log.e(TAG, "No injection method available")
        }
    }

    private fun setMode(newMode: InjectionMode) {
        mode = newMode
        _modeFlow.value = newMode
        // Rumble travels over force feedback from the virtual device, which only the uinput
        // backend implements — and only while that device is actually alive.
        _rumbleSupportedFlow.value = newMode.isVirtualDevice && uinput.rumbleSupported
        refreshNotification()
    }

    private fun refreshNotification() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr?.notify(NOTIFICATION_ID, buildNotification())
    }

    /**
     * Triggered by the notification action: cycle to the next profile (Xbox360 → XboxOne → DS4 → DualSense → ...).
     * Only meaningful in uinput mode; in fallback inject mode the profile is ignored.
     */
    // Guards against re-entrant taps on the "Switch profile" notification action while
    // a previous switch is still tearing down / recreating uinput devices.
    @Volatile private var profileSwitchInFlight = false

    private fun cycleProfile() {
        if (profileSwitchInFlight) {
            Log.w(TAG, "Cycle profile ignored: a switch is already in progress")
            return
        }
        profileSwitchInFlight = true

        val profiles =
            com.steamcontroller.android.uinput.GamepadProfile
                .values()
        val current = Prefs.getProfile(this)
        val next = profiles[(current.ordinal + 1) % profiles.size]
        Prefs.setProfile(this, next)
        Log.i(TAG, "Cycle profile: ${current.displayName} → ${next.displayName}")

        // Reset baseline state immediately so any buttons "held" during the swap
        // don't get injected via the now-defunct device.
        confirmedState = null
        pendingButtons = 0
        pendingFrames = 0

        // Run the actual device teardown/recreate off the service main thread —
        // it's a blocking binder + native ioctl pair that can take 100ms+.
        // Doing it on the main thread risks ANR / lost broadcast intents and was
        // the most likely cause of the "I can't change profile until I reboot" bug.
        scope.launch {
            try {
                if (mode.isVirtualDevice) {
                    val ok = uinput.switchProfile(next)
                    if (!ok) {
                        Log.w(
                            TAG,
                            "switchProfile failed; the gamepad may need a service restart"
                        )
                    }
                }
                _profileFlow.value = next.id
                refreshNotification()
            } finally {
                profileSwitchInFlight = false
            }
        }
    }

    private fun handleState(state: SteamControllerState) {
        if (mode == InjectionMode.NONE) return

        // First frame = baseline
        if (confirmedState == null) {
            confirmedState = state
            pendingButtons = state.buttons and INJECTABLE_MASK
            pendingFrames = 0
            return
        }

        // Button debounce: only inject after DEBOUNCE_FRAMES consecutive stable frames
        val injectableBits = state.buttons and INJECTABLE_MASK
        val buttonsConfirmedThisFrame: Boolean
        if (injectableBits == pendingButtons) {
            pendingFrames++
            if (pendingFrames >= DEBOUNCE_FRAMES &&
                injectableBits != (confirmedState!!.buttons and INJECTABLE_MASK)
            ) {
                confirmedState = state
                buttonsConfirmedThisFrame = true
            } else {
                buttonsConfirmedThisFrame = false
            }
        } else {
            pendingButtons = injectableBits
            pendingFrames = 1
            buttonsConfirmedThisFrame = false
        }

        when (mode) {
            InjectionMode.UINPUT,
            InjectionMode.UHID
            -> {
                // Combine confirmed buttons with current raw axes — the frame is atomic.
                // Desktop / mouse mode bypasses the gamepad debounce: the trackpad touch flag
                // (TP_RT) is capacitive and excluded from the debounce, so using confirmed
                // buttons would freeze the cursor whenever the touch flag couldn't propagate.
                val frameToSend =
                    if (Prefs.getProfile(this).isMouseMode) {
                        state
                    } else {
                        state.copy(buttons = confirmedState!!.buttons)
                    }
                uinput.pushFrame(frameToSend)
            }

            InjectionMode.SHIZUKU_INJECT -> {
                // Axes every frame for smoothness, with live-reloaded calibration
                legacyInjector.injectMotion(
                    GamepadMapper.axes(
                        state,
                        Prefs.getLeftCalibration(this),
                        Prefs.getRightCalibration(this)
                    )
                )
                // Buttons only on debounced change
                if (buttonsConfirmedThisFrame) {
                    GamepadMapper.buttons(state, confirmedState!!).forEach { (keyCode, down) ->
                        legacyInjector.injectKey(keyCode, down)
                    }
                }
            }

            InjectionMode.NONE -> { /* unreachable */ }
        }
    }

    override fun onDestroy() {
        try {
            uinput.unbind()
        } catch (_: Throwable) {
        }
        scope.cancel()
        reader?.stop()
        usbManager.disconnect()
        try {
            btManager.disconnect()
        } catch (_: Throwable) {
        }
        _modeFlow.value = InjectionMode.NONE
        initializing = false
        initializedTransport = null
        // Reset state + battery so MainActivity's "is the controller actually here?"
        // observer flips back to disconnected on stop.
        _stateFlow.value = null
        _batteryFlow.value = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows controller status and active emulation profile"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val profile = Prefs.getProfile(this)
        val battery = _batteryFlow.value

        val modeText =
            when (mode) {
                InjectionMode.UINPUT,
                InjectionMode.UHID
                -> getString(R.string.notif_mode_uinput, profile.displayName)

                InjectionMode.SHIZUKU_INJECT -> getString(R.string.notif_mode_inject)

                InjectionMode.NONE -> getString(R.string.notif_starting)
            }
        val title = getString(R.string.notification_title)
        // Append the active named profile name so the user can pull the shade and
        // see which preset auto-switch loaded for them.
        val activeProfileName =
            Prefs.getActiveNamedProfileId(this)?.let { id ->
                Prefs.listNamedProfiles(this).firstOrNull { it.id == id }?.name
            }
        val baseLine = if (battery != null) "$modeText  •  🔋 $battery%" else modeText
        val text = if (activeProfileName != null) "$baseLine\n🎯 $activeProfileName" else baseLine

        // Tap on the notification → open MainActivity
        val openIntent =
            Intent(this, com.steamcontroller.android.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val openPi =
            PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        val stopIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, ControllerService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE
            )
        val stopAction =
            Notification.Action
                .Builder(
                    android.graphics.drawable.Icon
                        .createWithResource(this, android.R.drawable.ic_media_pause),
                    getString(android.R.string.cancel),
                    stopIntent
                ).build()

        // "Switch profile" action: cycles to the next emulated controller (virtual devices only).
        val nextProfileIntent =
            PendingIntent.getService(
                this,
                2,
                Intent(this, ControllerService::class.java).apply { action = ACTION_NEXT_PROFILE },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        val nextLabel =
            if (mode.isVirtualDevice) {
                val profiles =
                    com.steamcontroller.android.uinput.GamepadProfile
                        .values()
                val next = profiles[(profile.ordinal + 1) % profiles.size]
                "→ ${next.displayName}"
            } else {
                "Switch profile"
            }
        val switchAction =
            Notification.Action
                .Builder(
                    android.graphics.drawable.Icon
                        .createWithResource(this, android.R.drawable.ic_menu_rotate),
                    nextLabel,
                    nextProfileIntent
                ).build()

        val icon =
            when (mode) {
                InjectionMode.UINPUT,
                InjectionMode.UHID
                -> android.R.drawable.ic_media_play

                InjectionMode.SHIZUKU_INJECT -> android.R.drawable.ic_media_play

                InjectionMode.NONE -> android.R.drawable.stat_notify_sync
            }

        val builder =
            Notification
                .Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(icon)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)

        // Only show the switch action when a virtual device is active — pointless in fallback or starting
        if (mode.isVirtualDevice) {
            builder.addAction(switchAction)
        }
        builder.addAction(stopAction)

        return builder.build()
    }
}
