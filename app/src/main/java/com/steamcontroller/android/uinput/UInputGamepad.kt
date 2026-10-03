package com.steamcontroller.android.uinput

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.steamcontroller.android.BuildConfig
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.input.DEFAULT_ACTION_LAYER
import com.steamcontroller.android.input.GyroActivation
import com.steamcontroller.android.input.GyroAim
import com.steamcontroller.android.input.GyroTuning
import com.steamcontroller.android.input.MOUSE_LEFT_PAD_CLICK_BIT
import com.steamcontroller.android.input.MOUSE_MODE_FIXED_DPAD
import com.steamcontroller.android.input.MOUSE_RIGHT_PAD_CLICK_BIT
import com.steamcontroller.android.input.MouseTarget
import com.steamcontroller.android.input.SteamButton
import com.steamcontroller.android.input.StickCalibration
import com.steamcontroller.android.input.SystemActions
import com.steamcontroller.android.input.XboxTarget
import com.steamcontroller.android.parser.Buttons
import com.steamcontroller.android.parser.SteamControllerState
import rikka.shizuku.Shizuku
import kotlin.math.abs

// High-level Kotlin API for the virtual gamepad + mouse/keyboard sidecar.
// Binds UInputService through Shizuku, translates SC2026 state into frames, and reports
// which output backend the shell-UID process ended up using (uinput or uhid).
class UInputGamepad(
    private val context: Context,
    initialProfile: GamepadProfile,
) {
    private val TAG = "UInputGamepad"

    companion object {
        // Sentinel meaning "the setting had no value before we touched it" (settings get
        // returns "null" as a string in that case) - restored by deleting the key, not by
        // writing the literal string "null".
        private const val SHOW_IME_UNSET_SENTINEL = "__unset__"
        private const val ACTION_LAYER_HOLD_MS = 250L
        private const val TRIGGER_PRESS_THRESHOLD = 30_000
        private const val TRIGGER_RELEASE_THRESHOLD = 28_500
        private const val TRIGGER_DEBOUNCE_MS = 12L
    }

    private class AnalogButtonDebouncer {
        private var pressed = false
        private var candidate = false
        private var candidateSinceMs = 0L

        fun update(
            value: Int,
            nowMs: Long,
        ): Boolean {
            val next = value >= if (pressed) TRIGGER_RELEASE_THRESHOLD else TRIGGER_PRESS_THRESHOLD
            if (next != candidate) {
                candidate = next
                candidateSinceMs = nowMs
            } else if (candidate != pressed && nowMs - candidateSinceMs >= TRIGGER_DEBOUNCE_MS) {
                pressed = candidate
            }
            return pressed
        }
    }

    private var service: IUInputService? = null
    private var bound = false

    @Volatile private var profile: GamepadProfile = initialProfile

    // Which backend the shell-UID process selected, and what it found while probing.
    // Read after isReady; shown in the UI so a backend downgrade is never silent.
    @Volatile var backendId: Int = UInputNative.Backend.NONE
        private set

    @Volatile var backendDetail: String = ""
        private set

    @Volatile var rumbleSupported: Boolean = false
        private set

    /** Set by ControllerService to receive rumble commands from games. (strong, weak) ∈ [0, 65535]. */
    var onRumble: ((strong: Int, weak: Int) -> Unit)? = null

    private fun handleSpecialAction(target: XboxTarget) {
        when (target) {
            XboxTarget.SCREENSHOT -> {
                SystemActions.takeScreenshot(context) { cmd ->
                    try {
                        service?.runShellCommand(cmd) ?: -1
                    } catch (_: Throwable) {
                        -1
                    }
                }
            }

            else -> {}
        }
    }

    // Button state of the last sidecar/desktop frame sent. Tracked so an all-quiet frame
    // can be dropped without dropping the frame that *releases* the last key - comparing
    // against zero instead would leave a released key stuck down in the kernel.
    private var lastSentKeys: Int = 0

    // Diagnostic for the trackpad sidecar: it is silent when the touch flags never arrive,
    // which looks exactly like "the trackpads do nothing". Logs on every change of the button
    // mask (so a brief click cannot be missed) plus a slow heartbeat.
    private var lastSidecarLogMs: Long = 0
    private var lastSidecarButtons: Int = -1

    private var rumbleThread: Thread? = null

    @Volatile private var rumbleThreadRunning = false

    // Calibration + mapping cache - refreshed every refreshIntervalMs instead of every frame
    @Volatile private var cachedLeftCal: StickCalibration = StickCalibration.DEFAULT

    @Volatile private var cachedRightCal: StickCalibration = StickCalibration.DEFAULT

    @Volatile private var cachedMapping: Map<SteamButton, XboxTarget> = emptyMap()

    @Volatile private var cachedDesktopMapping: Map<SteamButton, XboxTarget> = emptyMap()

    @Volatile private var lastCalRefresh: Long = 0
    private val calRefreshIntervalMs = 250L // ~4 Hz refresh, plenty for live tuning

    // Edge detection uses source bits rather than the raw report because trigger mappings are
    // derived from analog values and do not have trustworthy digital bits on this firmware.
    private var lastSourceButtons: Long = 0L
    private var layerPressedAt = 0L
    private var layerWasPressed = false
    private var guideTapPending = false
    private val leftTriggerButton = AnalogButtonDebouncer()
    private val rightTriggerButton = AnalogButtonDebouncer()

    // Mouse-mode state: previous trackpad position (for delta) and sensitivity cache.
    private var lastRightPadX: Int = 0
    private var lastRightPadY: Int = 0
    private var rightPadHadContact: Boolean = false

    // Left trackpad → scroll wheel state (used in gamepad sidecar mode).
    private var lastLeftPadY: Int = 0
    private var leftPadHadContact: Boolean = false

    @Volatile private var cachedMouseSensitivity: Float = 1f

    private val gyro = GyroAim()

    @Volatile private var cachedGyroEnabled = false

    @Volatile private var cachedGyroTuning = GyroTuning()

    @Volatile private var cachedGyroActivation = GyroActivation.RIGHT_PAD_TOUCH

    @Volatile private var cachedGyroInvertY = false

    @Volatile private var cachedTrackpadAsMouse: Boolean = true

    // Trigger / pad scroll: accumulator so we can convert continuous 0..32767 deltas into discrete wheel ticks
    private var scrollAccumulator: Int = 0

    private val args =
        Shizuku
            .UserServiceArgs(
                ComponentName(context.packageName, UInputService::class.java.name),
            ).daemon(false)
            .processNameSuffix("uinput")
            .debuggable(false)
            // Bumped from 1: a stale user-service process would be running the previous AIDL
            // (canCreateDevice) and silently fail every call below.
            .version(2)

    @Volatile private var deviceReady = false

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?,
            ) {
                val svc = IUInputService.Stub.asInterface(binder)
                service = svc
                Log.i(TAG, "UInputService connected")
                // Binder calls can block - do them off the main thread
                Thread {
                    try {
                        val backend = svc.selectBackend(Prefs.getBackendPref(context))
                        backendId = backend
                        backendDetail = svc.getBackendDetail() ?: ""
                        rumbleSupported = svc.supportsRumble()
                        Log.i(TAG, "backend=${UInputNative.backendName(backend)} - $backendDetail")
                        if (backend == UInputNative.Backend.NONE) {
                            Log.e(TAG, "No usable input backend on this device")
                        } else {
                            val ok = svc.createGamepad(profile.id)
                            deviceReady = ok
                            Log.i(TAG, "createGamepad(${profile.displayName}) → $ok")
                            if (ok) applyShowImeOverride(svc)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "init failed: ${t.message}")
                    }
                }.start()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                deviceReady = false
                // Critical: without this, bind() short-circuits on `if (bound) return` forever and the
                // app can never re-establish its user service - a killed service used to leave it
                // permanently unable to create devices, with no way back except restarting the app.
                bound = false
                Log.w(TAG, "UInputService disconnected - will re-bind on the next start")
            }

            // API 26+: the binding died, or connected to nothing. Same recovery as above.
            override fun onBindingDied(name: ComponentName?) {
                service = null
                deviceReady = false
                bound = false
                Log.w(TAG, "UInputService binding died - will re-bind on the next start")
            }

            override fun onNullBinding(name: ComponentName?) {
                service = null
                deviceReady = false
                bound = false
                Log.w(TAG, "UInputService returned a null binding")
            }
        }

    fun bind() {
        if (bound) return
        Shizuku.bindUserService(args, connection)
        bound = true
        startRumbleThread()
    }

    fun unbind() {
        if (!bound) return
        stopRumbleThread()
        try {
            service?.let { restoreShowImeOverride(it) }
        } catch (_: Throwable) {
        }
        try {
            service?.destroy()
        } catch (_: Throwable) {
        }
        Shizuku.unbindUserService(args, connection, true)
        bound = false
        service = null
    }

    // Android's InputManager treats a paired/connected HID keyboard-classified device as a
    // hardware keyboard and suppresses the on-screen keyboard for every text field, system-wide,
    // for as long as it's attached. The Steam Controller's own USB/BT HID interfaces can trigger
    // this classification independently of our uinput device (e.g. a legacy "boot keyboard" HID
    // interface used for lizard mode, auto-bound by the kernel/Bluetooth stack outside our app's
    // control). Forcing Settings.Secure.show_ime_with_hard_keyboard=1 via the shell UID makes
    // Android show the soft keyboard regardless. Reverted on unbind() so a real Bluetooth
    // keyboard paired later behaves normally.
    private fun applyShowImeOverride(svc: IUInputService) {
        try {
            if (Prefs.getSavedShowImeHardKeyboard(context) == null) {
                val current =
                    try {
                        svc.runShellCommandForOutput(
                            arrayOf("settings", "get", "secure", "show_ime_with_hard_keyboard"),
                        )
                    } catch (_: Throwable) {
                        null
                    }
                val toSave =
                    current?.takeIf { it.isNotBlank() && it != "null" } ?: SHOW_IME_UNSET_SENTINEL
                Prefs.setSavedShowImeHardKeyboard(context, toSave)
            }
            svc.runShellCommand(
                arrayOf("settings", "put", "secure", "show_ime_with_hard_keyboard", "1"),
            )
            Log.i(TAG, "show_ime_with_hard_keyboard forced on")
        } catch (t: Throwable) {
            Log.w(TAG, "applyShowImeOverride failed: ${t.message}")
        }
    }

    private fun restoreShowImeOverride(svc: IUInputService) {
        try {
            val saved = Prefs.getSavedShowImeHardKeyboard(context) ?: return
            if (saved == SHOW_IME_UNSET_SENTINEL) {
                svc.runShellCommand(
                    arrayOf("settings", "delete", "secure", "show_ime_with_hard_keyboard"),
                )
            } else {
                svc.runShellCommand(
                    arrayOf("settings", "put", "secure", "show_ime_with_hard_keyboard", saved),
                )
            }
            Prefs.clearSavedShowImeHardKeyboard(context)
            Log.i(TAG, "show_ime_with_hard_keyboard restored to '$saved'")
        } catch (t: Throwable) {
            Log.w(TAG, "restoreShowImeOverride failed: ${t.message}")
        }
    }

    /**
     * Poll the user service for FF (rumble) events triggered by games.
     * Runs at 50 Hz - Android emits FF events at the game's frame rate (~60 Hz)
     * so this is fast enough without burning binder calls.
     */
    private fun startRumbleThread() {
        if (rumbleThreadRunning) return
        rumbleThreadRunning = true
        rumbleThread =
            Thread({
                while (rumbleThreadRunning) {
                    try {
                        val svc = service
                        if (svc != null && deviceReady) {
                            val rumble = svc.pollForceFeedback()
                            if (rumble != null && rumble.size == 2) {
                                onRumble?.invoke(rumble[0], rumble[1])
                            }
                        }
                    } catch (_: Throwable) {
                        // IPC may fail during unbind, ignore
                    }
                    try {
                        Thread.sleep(20)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }, "uinput-ff-poll").apply {
                isDaemon = true
                start()
            }
    }

    private fun stopRumbleThread() {
        rumbleThreadRunning = false
        rumbleThread?.interrupt()
        rumbleThread = null
    }

    val isReady get() = service != null && deviceReady

    /**
     * Swap the emulated controller profile without unbinding the user service.
     * The native code recreates the /dev/uinput device with the new VID/PID.
     * Returns true on success.
     */
    fun switchProfile(newProfile: GamepadProfile): Boolean {
        val svc = service ?: return false
        return try {
            val ok = svc.createGamepad(newProfile.id)
            if (ok) {
                profile = newProfile
                // The backend recreates its devices on switch and forgets its own cached
                // state; drop ours too so the next frame can't be mistaken for a no-op.
                lastSentKeys = 0
                Log.i(TAG, "Switched profile to ${newProfile.displayName}")
            } else {
                Log.e(TAG, "createGamepad(${newProfile.displayName}) returned false")
            }
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "switchProfile failed: ${t.message}")
            false
        }
    }

    // Push one HID frame from the SC2026 parser, translated to Xbox 360 layout.
    fun pushFrame(state: SteamControllerState) {
        val svc = service ?: return

        // Refresh cached calibrations + button mapping from prefs at most every 250ms
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastCalRefresh > calRefreshIntervalMs) {
            cachedLeftCal = Prefs.getLeftCalibration(context)
            cachedRightCal = Prefs.getRightCalibration(context)
            cachedMapping = Prefs.getAllMappings(context)
            cachedDesktopMapping = Prefs.getAllDesktopMappings(context)
            cachedMouseSensitivity = Prefs.getMouseSensitivity(context)
            cachedTrackpadAsMouse = Prefs.getTrackpadAsMouseInGamepad(context)
            cachedGyroEnabled = Prefs.getGyroEnabled(context)
            cachedGyroTuning = Prefs.getGyroTuning(context)
            cachedGyroActivation = Prefs.getGyroActivation(context)
            cachedGyroInvertY = Prefs.getGyroInvertY(context)
            lastCalRefresh = now
        }

        if (profile.isMouseMode) {
            pushMouseFrame(svc, state)
            return
        }

        val leftCal = cachedLeftCal
        val rightCal = cachedRightCal

        // Apply the user-configurable button mapping.
        //   target.mask > 0       → regular Xbox button bit (OR into the bitmask).
        //   target.keyBit >= 0    → sidecar keyboard key (OR into the sidecar key bitmask).
        //   target.triggerSide!=0 → force LT (1) / RT (2) axis to max.
        //   target.mask < 0       → special action, edge-triggered on press.
        var xboxButtons = 0
        var sidecarMappedKeys = 0
        var ltOverride = 0
        var rtOverride = 0
        val sourceButtons = sourceButtons(state)
        val layerSource = cachedMapping.entries.firstOrNull { it.value == XboxTarget.GUIDE_LAYER }
        val layerPressed =
            layerSource?.let { sourceButtons and (1L shl it.key.ordinal) != 0L } == true
        if (layerPressed && !layerWasPressed) layerPressedAt = now
        if (!layerPressed && layerWasPressed && now - layerPressedAt < ACTION_LAYER_HOLD_MS) {
            guideTapPending = true
        }
        val layerActive = layerPressed && now - layerPressedAt >= ACTION_LAYER_HOLD_MS
        layerWasPressed = layerPressed
        if (guideTapPending) {
            xboxButtons = xboxButtons or XboxTarget.MODE.mask
            guideTapPending = false
        }

        for ((source, baseTarget) in cachedMapping) {
            if (baseTarget == XboxTarget.GUIDE_LAYER) continue
            val target = if (layerActive) DEFAULT_ACTION_LAYER[source] ?: baseTarget else baseTarget
            val sourceBit = 1L shl source.ordinal
            val pressed = sourceButtons and sourceBit != 0L
            when {
                target.mask > 0 && pressed -> {
                    xboxButtons = xboxButtons or target.mask
                }

                target.keyBit >= 0 && pressed -> {
                    sidecarMappedKeys = sidecarMappedKeys or (1 shl target.keyBit)
                }

                target.triggerSide == 1 && pressed -> {
                    ltOverride = 255
                }

                target.triggerSide == 2 && pressed -> {
                    rtOverride = 255
                }

                target.mask < 0 && target.keyBit < 0 && target.triggerSide == 0 -> {
                    val wasPressed = lastSourceButtons and sourceBit != 0L
                    if (pressed && !wasPressed) handleSpecialAction(target)
                }
            }
        }
        lastSourceButtons = sourceButtons

        // SC2026 sticks are already in Int16 range - direct passthrough
        // SC2026 triggers are 0-32767 → scale down to Xbox 0-255.
        // ltOverride/rtOverride bump the axis to max when a remapped source is pressed.
        val ltAnalog = (state.leftTrigger * 255 / 32767).coerceIn(0, 255)
        val rtAnalog = (state.rightTrigger * 255 / 32767).coerceIn(0, 255)
        val lt = maxOf(ltAnalog, ltOverride)
        val rt = maxOf(rtAnalog, rtOverride)

        val dpadX =
            when {
                state.isButtonPressed(Buttons.DPAD_RIGHT) -> 1
                state.isButtonPressed(Buttons.DPAD_LEFT) -> -1
                else -> 0
            }
        val dpadY =
            when {
                state.isButtonPressed(Buttons.DPAD_DOWN) -> 1
                state.isButtonPressed(Buttons.DPAD_UP) -> -1
                else -> 0
            }
        val (lxCal, lyCalRaw) = leftCal.apply(state.leftJoyX.toInt(), state.leftJoyY.toInt())
        val (rxCal, ryCalRaw) = rightCal.apply(state.rightJoyX.toInt(), state.rightJoyY.toInt())

        // SC2026 reports Y positive = up; Linux input ABS_Y convention is Y positive = down.
        val ly = -lyCalRaw.coerceAtLeast(-32767)
        var rx = rxCal
        var ry = -ryCalRaw.coerceAtLeast(-32767)

        if (cachedGyroEnabled) {
            val activation = cachedGyroActivation
            if (activation.mask == 0 || state.isButtonPressed(activation.mask)) {
                gyro.update(
                    state.quatW,
                    state.quatX,
                    state.quatY,
                    state.quatZ,
                    now,
                    cachedGyroTuning,
                )
                rx = (rx + gyro.stickX).coerceIn(-32767, 32767)
                val gyroY = if (cachedGyroInvertY) -gyro.stickY else gyro.stickY
                ry = (ry + gyroY).coerceIn(-32767, 32767)
            } else {
                gyro.reset()
            }
        } else {
            gyro.reset()
        }

        try {
            svc.sendFrame(
                xboxButtons,
                lxCal,
                ly,
                rx,
                ry,
                lt,
                rt,
                dpadX,
                dpadY,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "sendFrame IPC failed: ${t.message}")
        }

        // Sidecar mouse + keyboard while a gamepad profile is active.
        // - cachedTrackpadAsMouse gates trackpad-driven cursor + scroll
        // - mapped keyboard keys (sidecarMappedKeys) always flow through, even when
        //   the trackpad-as-mouse toggle is off, so back-paddle keyboard mappings work.
        pushSidecarFrame(svc, state, sidecarMappedKeys)
    }

    /**
     * Sidecar frame for gamepad mode: trackpad-as-mouse + keyboard targets for
     * back paddles. Skips emitting anything when nothing happens this frame -
     * keeping the mouse fd idle is critical so Android IME focus isn't stolen by
     * a phantom cursor (same rationale as in Desktop mode).
     */
    private fun pushSidecarFrame(
        svc: IUInputService,
        state: SteamControllerState,
        mappedKeys: Int,
    ) {
        val (relX, relY, scrollTicks) =
            if (cachedTrackpadAsMouse) {
                val (rx, ry) = computeRightPadDelta(state)
                Triple(rx, ry, computeLeftPadScroll(state))
            } else {
                // Still reset accumulators / contact flags so a re-enable mid-session
                // doesn't trigger a phantom delta on first touch.
                rightPadHadContact = false
                leftPadHadContact = false
                scrollAccumulator = 0
                Triple(0, 0, 0)
            }

        var keys = mappedKeys
        // Either trackpad click → left mouse click (only active when the sidecar mouse is on).
        // The right pad is the one driving the cursor, so its click is the primary one, and the
        // left pad click matches it so "click the pad" behaves the same on both.
        if (cachedTrackpadAsMouse &&
            (
                state.isButtonPressed(MOUSE_LEFT_PAD_CLICK_BIT) ||
                    state.isButtonPressed(MOUSE_RIGHT_PAD_CLICK_BIT)
            )
        ) {
            keys = keys or (1 shl MouseTarget.BTN_LEFT.bit)
        }

        // Deliver the sidecar frame before formatting diagnostics so debug logging never sits
        // between a physical pad event and the virtual mouse or keyboard report.
        sendMouseFrameIfChanged(svc, relX, relY, scrollTicks, keys, state.buttons)

        val nowMs = android.os.SystemClock.uptimeMillis()
        if (BuildConfig.DEBUG &&
            (state.buttons != lastSidecarButtons || nowMs - lastSidecarLogMs > 5000L)
        ) {
            lastSidecarLogMs = nowMs
            lastSidecarButtons = state.buttons
            Log.i(
                TAG,
                "sidecar: tpAsMouse=$cachedTrackpadAsMouse " +
                    "touchRt=${state.isButtonPressed(Buttons.TP_RT)} " +
                    "touchLt=${state.isButtonPressed(Buttons.TP_LT)} " +
                    "padXY=(${state.rightPadX},${state.rightPadY}) " +
                    "rel=($relX,$relY) scroll=$scrollTicks keys=$keys " +
                    "buttons=0x${state.buttons.toString(16)}'",
            )
        }
    }

    /**
     * Hand a sidecar/desktop mouse+keyboard frame to the service, unless nothing about it changed.
     *
     * The guard is not just tidiness: a mouse node that reports every frame keeps the cursor
     * "active", and Android then routes DPAD events to it instead of the focused IME (see the
     * native sendMouseFrame). It compares against the last *sent* state rather than against zero,
     * so the frame that releases a key is never the one that gets skipped.
     *
     * Shared by both mouse modes - they differ only in how `keys` was built.
     */
    private fun sendMouseFrameIfChanged(
        svc: IUInputService,
        relX: Int,
        relY: Int,
        scrollTicks: Int,
        keys: Int,
        mask: Int,
    ) {
        if (relX == 0 && relY == 0 && scrollTicks == 0 && keys == lastSentKeys) return
        lastSentKeys = keys
        if (BuildConfig.DEBUG && keys != 0) {
            // The exact key mask handed to the native layer. "The left pad emits a click" was
            // twice inferred from the button mask and twice wrong - this is the authoritative
            // record of what was actually transmitted.
            Log.i(TAG, "mouse frame: keys=0x${keys.toString(16)} mask=0x${mask.toString(16)}")
        }
        try {
            svc.sendMouseFrame(relX, relY, scrollTicks, keys)
        } catch (t: Throwable) {
            Log.e(TAG, "sendMouseFrame IPC failed: ${t.message}")
        }
    }

    /** Right trackpad delta (in mouse-cursor units). Resets cleanly on lift-off. */
    private fun computeRightPadDelta(state: SteamControllerState): Pair<Int, Int> {
        val touching = state.isButtonPressed(Buttons.TP_RT)
        val curX = state.rightPadX.toInt()
        val curY = state.rightPadY.toInt()
        // Lifting a finger does not clear the touch flag in the same report that zeroes the
        // coordinates: the pad reads (0,0) for a frame or two while the flag is still set.
        // Treating that as motion computes a delta of -(last position), which drags the cursor
        // straight back to where the swipe began the moment the finger leaves the pad. A real
        // finger sitting exactly on the pad's electrical centre is not a thing.
        if (!touching || (curX == 0 && curY == 0)) {
            rightPadHadContact = false
            return 0 to 0
        }
        var relX = 0
        var relY = 0
        if (rightPadHadContact) {
            val sens = cachedMouseSensitivity
            relX = ((curX - lastRightPadX) / 128f * sens).toInt()
            // SC2026 Y up positive → mouse Y down positive: invert
            relY = (-(curY - lastRightPadY) / 128f * sens).toInt()
        }
        lastRightPadX = curX
        lastRightPadY = curY
        rightPadHadContact = true
        return relX to relY
    }

    /** Left trackpad vertical → wheel ticks. One tick per ~1000 accumulator units. */
    private fun sourceButtons(state: SteamControllerState): Long {
        val now = android.os.SystemClock.uptimeMillis()
        var pressed = 0L
        SteamButton.values().forEach { source ->
            val isPressed =
                when (source) {
                    SteamButton.LT -> leftTriggerButton.update(state.leftTrigger, now)
                    SteamButton.RT -> rightTriggerButton.update(state.rightTrigger, now)
                    else -> source.isPressed(state)
                }
            if (isPressed) pressed = pressed or (1L shl source.ordinal)
        }
        return pressed
    }

    private fun computeLeftPadScroll(state: SteamControllerState): Int {
        val touching = state.isButtonPressed(Buttons.TP_LT)
        val curY = state.leftPadY.toInt()
        val curX = state.leftPadX.toInt()
        // Same lift artefact as the right pad: zeroed coordinates with the touch flag still set
        // would add one big reverse step to the scroll accumulator as the finger leaves.
        if (!touching || (curX == 0 && curY == 0)) {
            leftPadHadContact = false
            scrollAccumulator = 0
            return 0
        }
        if (leftPadHadContact) {
            // Y positive = up on SC2026; scroll wheel positive = up → keep sign.
            scrollAccumulator += (curY - lastLeftPadY) / 8
        }
        lastLeftPadY = curY
        leftPadHadContact = true
        if (abs(scrollAccumulator) < 1000) return 0
        val ticks = scrollAccumulator / 1000
        scrollAccumulator -= ticks * 1000
        return ticks
    }

    /**
     * Desktop / mouse mode: right trackpad → cursor delta, triggers → scroll wheel,
     * face/system buttons → mapped keys, DPAD → arrow keys, left pad click → right mouse.
     * Special actions (e.g. SCREENSHOT) still fire via the gamepad mapping.
     */
    private fun pushMouseFrame(
        svc: IUInputService,
        state: SteamControllerState,
    ) {
        // Right trackpad → cursor delta; left trackpad vertical → scroll wheel.
        // Same helpers as the gamepad sidecar mode so the gesture is identical.
        val (relX, relY) = computeRightPadDelta(state)
        val scrollTicks = computeLeftPadScroll(state)

        // ── Key/mouse-button bitmask ─────────────────────────────────────────
        var keys = 0

        val sourceButtons = sourceButtons(state)

        // Desktop actions use the same persisted target IDs as gamepad mode, filtered to
        // keyboard and pointer actions by keyBit.
        for ((source, target) in cachedDesktopMapping) {
            if (target.keyBit >= 0 && sourceButtons and (1L shl source.ordinal) != 0L) {
                keys = keys or (1 shl target.keyBit)
            }
        }

        // Fixed DPAD → arrow keys
        for ((mask, target) in MOUSE_MODE_FIXED_DPAD) {
            if (state.isButtonPressed(mask)) keys = keys or (1 shl target.bit)
        }

        // Left trackpad click → right mouse click
        if (state.isButtonPressed(MOUSE_LEFT_PAD_CLICK_BIT)) {
            keys = keys or (1 shl MouseTarget.BTN_RIGHT.bit)
        }

        // Special actions (screenshot) still honoured via the gamepad mapping table -
        // keeps QA → screenshot working even in mouse mode.
        for ((source, target) in cachedDesktopMapping) {
            if (target.mask < 0 && target.keyBit < 0 && target.triggerSide == 0) {
                val sourceBit = 1L shl source.ordinal
                val pressed = sourceButtons and sourceBit != 0L
                val wasPressed = lastSourceButtons and sourceBit != 0L
                if (pressed && !wasPressed) handleSpecialAction(target)
            }
        }
        lastSourceButtons = sourceButtons

        // Same "nothing changed → don't send" rule as the gamepad-mode sidecar: a frame
        // that keeps the mouse node reporting makes Android hold the cursor active and
        // route DPAD to it instead of the focused IME.
        sendMouseFrameIfChanged(svc, relX, relY, scrollTicks, keys, state.buttons)
    }
}
