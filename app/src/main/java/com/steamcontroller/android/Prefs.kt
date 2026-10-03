package com.steamcontroller.android

import android.content.Context
import android.content.SharedPreferences
import com.steamcontroller.android.input.DEFAULT_DESKTOP_MAPPING
import com.steamcontroller.android.input.DEFAULT_MAPPING
import com.steamcontroller.android.input.GyroActivation
import com.steamcontroller.android.input.GyroTuning
import com.steamcontroller.android.input.NamedProfile
import com.steamcontroller.android.input.SteamButton
import com.steamcontroller.android.input.StickCalibration
import com.steamcontroller.android.input.XboxTarget
import com.steamcontroller.android.uinput.GamepadProfile

enum class Transport(
    val id: Int,
    val displayName: String,
) {
    USB(0, "USB / Puck"),
    BLUETOOTH(1, "Bluetooth"),
    ;

    companion object {
        fun fromId(id: Int) = values().firstOrNull { it.id == id } ?: USB
    }
}

object Prefs {
    private const val NAME = "steam_controller_prefs"
    private const val KEY_PROFILE_ID = "gamepad_profile_id"
    private const val KEY_LAST_GAMEPAD_PROFILE_ID = "last_gamepad_profile_id"
    private const val KEY_TRANSPORT = "transport"
    private const val KEY_BT_ADDRESS = "bt_device_address"

    private enum class StickSide(
        val key: String,
    ) {
        LEFT("l"),
        RIGHT("r"),
    }

    private const val KEY_RUMBLE_INTENSITY = "rumble_intensity" // 0..100
    private const val KEY_MOUSE_SENSITIVITY = "mouse_sensitivity_x10" // 1..30 → 0.1x..3.0x
    private const val KEY_TRACKPAD_AS_MOUSE = "trackpad_as_mouse_gamepad" // sidecar mouse in gamepad mode
    private const val PREF_GYRO_ENABLED = "gyro_enabled"
    private const val PREF_GYRO_SENSITIVITY = "gyro_sensitivity_x10"
    private const val PREF_GYRO_SENSITIVITY_Y = "gyro_sensitivity_y_x10"
    private const val PREF_GYRO_SMOOTHING = "gyro_smoothing_pct"
    private const val PREF_GYRO_DEADZONE = "gyro_deadzone_x1000"
    private const val PREF_GYRO_RESPONSE = "gyro_response_x10"
    private const val PREF_GYRO_BIAS_X = "gyro_bias_x"
    private const val PREF_GYRO_BIAS_Y = "gyro_bias_y"
    private const val PREF_GYRO_ACTIVATION = "gyro_activation"
    private const val PREF_GYRO_INVERT_Y = "gyro_invert_y"
    private const val KEY_NAMED_PROFILES = "named_profiles_json"
    private const val KEY_ACTIVE_NAMED_PROFILE_ID = "active_named_profile_id"

    private const val KEY_SAVED_SHOW_IME_HARD_KB = "saved_show_ime_with_hard_keyboard"
    private const val KEY_START_ON_BOOT = "start_on_boot"
    private const val KEY_START_ON_CONNECT = "start_on_controller_connect"
    private const val KEY_USER_STOPPED_SERVICE = "user_stopped_service"
    private const val KEY_BT_NAME = "bt_name"
    private const val KEY_PREF_SCHEMA = "pref_schema"
    private const val PREF_SCHEMA = 1

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE).also(::migrate)

    private fun migrate(prefs: SharedPreferences) {
        if (prefs.getInt(KEY_PREF_SCHEMA, 0) >= PREF_SCHEMA) return
        val stored = prefs.all
        val edit = prefs.edit()

        fun copyString(
            oldKey: String,
            newKey: String,
        ) {
            if (!stored.containsKey(newKey)) {
                (stored[oldKey] as? String)?.let { edit.putString(newKey, it) }
            }
        }

        fun copyBoolean(
            oldKey: String,
            newKey: String,
        ) {
            if (!stored.containsKey(newKey)) {
                (stored[oldKey] as? Boolean)?.let { edit.putBoolean(newKey, it) }
            }
        }

        copyString("bt_device_name", KEY_BT_NAME)
        copyBoolean("autostart_on_boot", KEY_START_ON_BOOT)
        copyBoolean("autostart_on_controller_connect", KEY_START_ON_CONNECT)

        SteamButton.values().forEach { source ->
            val key = mapKey(MAP_PREFIX, source)
            val value = stored[key]
            if (value is Number) {
                XboxTarget.fromPersisted(value)?.let { edit.putString(key, it.name) }
            }
        }
        edit.putInt(KEY_PREF_SCHEMA, PREF_SCHEMA).apply()
    }

    fun getProfile(context: Context): GamepadProfile {
        val id = prefs(context).getInt(KEY_PROFILE_ID, GamepadProfile.XBOX_360.id)
        return GamepadProfile.fromId(id)
    }

    fun setProfile(
        context: Context,
        profile: GamepadProfile,
    ) {
        val edit = prefs(context).edit().putInt(KEY_PROFILE_ID, profile.id)
        // Remember the last *non-Mouse* profile so the Gamepad/Desktop toggle on
        // the new UI can revert to it when the user flips back to Gamepad mode.
        if (!profile.isMouseMode) {
            edit.putInt(KEY_LAST_GAMEPAD_PROFILE_ID, profile.id)
        }
        edit.apply()
    }

    /** The last gamepad profile the user actively chose (defaults to Xbox 360). */
    fun getLastGamepadProfile(context: Context): GamepadProfile {
        val id = prefs(context).getInt(KEY_LAST_GAMEPAD_PROFILE_ID, GamepadProfile.XBOX_360.id)
        val p = GamepadProfile.fromId(id)
        return if (p.isMouseMode) GamepadProfile.XBOX_360 else p
    }

    // ─── Start on boot ───────────────────────────────────────────────────────

    /**
     * Whether the controller service starts itself at boot. On by default: this is a background
     * input service, so the cost is a notification. It cannot come up fully until Shizuku is
     * running, which after a reboot means an adb command on Android 9 - the status card says so
     * rather than leaving the user to guess.
     */
    fun getStartOnBoot(context: Context): Boolean = prefs(context).getBoolean(KEY_START_ON_BOOT, true)

    fun setStartOnBoot(
        context: Context,
        enabled: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_START_ON_BOOT, enabled).apply()
    }

    fun getStartOnControllerConnect(context: Context): Boolean = prefs(context).getBoolean(KEY_START_ON_CONNECT, true)

    fun setStartOnControllerConnect(
        context: Context,
        enabled: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_START_ON_CONNECT, enabled).apply()
    }

    fun getUserStoppedService(context: Context): Boolean = prefs(context).getBoolean(KEY_USER_STOPPED_SERVICE, false)

    fun setUserStoppedService(
        context: Context,
        stopped: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_USER_STOPPED_SERVICE, stopped).apply()
    }

    fun getTransport(context: Context): Transport = Transport.fromId(prefs(context).getInt(KEY_TRANSPORT, Transport.USB.id))

    fun setTransport(
        context: Context,
        t: Transport,
    ) {
        prefs(context).edit().putInt(KEY_TRANSPORT, t.id).apply()
    }

    fun getBluetoothAddress(context: Context): String? = prefs(context).getString(KEY_BT_ADDRESS, null)

    fun setBluetoothAddress(
        context: Context,
        address: String?,
    ) {
        prefs(context).edit().putString(KEY_BT_ADDRESS, address).apply()
    }

    fun getBluetoothName(context: Context): String? = prefs(context).getString(KEY_BT_NAME, null)

    fun setBluetoothName(
        context: Context,
        name: String?,
    ) {
        prefs(context).edit().putString(KEY_BT_NAME, name).apply()
    }

    fun getLeftCalibration(context: Context) = getCalibration(context, StickSide.LEFT)

    fun setLeftCalibration(
        context: Context,
        calibration: StickCalibration,
    ) {
        setCalibration(context, StickSide.LEFT, calibration)
    }

    fun getRightCalibration(context: Context) = getCalibration(context, StickSide.RIGHT)

    fun setRightCalibration(
        context: Context,
        calibration: StickCalibration,
    ) {
        setCalibration(context, StickSide.RIGHT, calibration)
    }

    private fun getCalibration(
        context: Context,
        side: StickSide,
    ): StickCalibration =
        prefs(context).run {
            StickCalibration(
                centerX = getInt("calib_${side.key}_cx", 0),
                centerY = getInt("calib_${side.key}_cy", 0),
                deadzonePercent = getInt("calib_${side.key}_dz", 8),
                invertY = getBoolean("calib_${side.key}_invy", false),
            )
        }

    private fun setCalibration(
        context: Context,
        side: StickSide,
        calibration: StickCalibration,
    ) {
        prefs(context)
            .edit()
            .putInt("calib_${side.key}_cx", calibration.centerX)
            .putInt("calib_${side.key}_cy", calibration.centerY)
            .putInt("calib_${side.key}_dz", calibration.deadzonePercent)
            .putBoolean("calib_${side.key}_invy", calibration.invertY)
            .apply()
    }

    // ─── Rumble intensity (0..100, % of game-requested magnitude) ───────────
    fun getRumbleIntensity(context: Context): Int = prefs(context).getInt(KEY_RUMBLE_INTENSITY, 100).coerceIn(0, 100)

    fun setRumbleIntensity(
        context: Context,
        percent: Int,
    ) {
        prefs(context).edit().putInt(KEY_RUMBLE_INTENSITY, percent.coerceIn(0, 100)).apply()
    }

    /** Mouse cursor sensitivity multiplier, 0.1x..3.0x. */
    fun getMouseSensitivity(context: Context): Float = (prefs(context).getInt(KEY_MOUSE_SENSITIVITY, 10).coerceIn(1, 30)) / 10f

    fun setMouseSensitivity(
        context: Context,
        multiplier: Float,
    ) {
        val v = (multiplier * 10f).toInt().coerceIn(1, 30)
        prefs(context).edit().putInt(KEY_MOUSE_SENSITIVITY, v).apply()
    }

    /** Whether the right trackpad / left trackpad drive a sidecar mouse cursor while a gamepad profile is active. */
    fun getTrackpadAsMouseInGamepad(context: Context): Boolean = prefs(context).getBoolean(KEY_TRACKPAD_AS_MOUSE, true)

    fun setTrackpadAsMouseInGamepad(
        context: Context,
        enabled: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_TRACKPAD_AS_MOUSE, enabled).apply()
    }

    fun getGyroEnabled(context: Context): Boolean = prefs(context).getBoolean(PREF_GYRO_ENABLED, false)

    fun setGyroEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        prefs(context).edit().putBoolean(PREF_GYRO_ENABLED, enabled).apply()
    }

    fun getGyroSensitivity(context: Context): Float = prefs(context).getInt(PREF_GYRO_SENSITIVITY, 10).coerceIn(1, 30) / 10f

    fun setGyroSensitivity(
        context: Context,
        sensitivity: Float,
    ) {
        val stored = (sensitivity * 10f).toInt().coerceIn(1, 30)
        prefs(context).edit().putInt(PREF_GYRO_SENSITIVITY, stored).apply()
    }

    fun getGyroSensitivityY(context: Context): Float {
        val fallback = prefs(context).getInt(PREF_GYRO_SENSITIVITY, 10)
        return prefs(context).getInt(PREF_GYRO_SENSITIVITY_Y, fallback).coerceIn(1, 30) / 10f
    }

    fun setGyroSensitivityY(
        context: Context,
        sensitivity: Float,
    ) {
        prefs(context)
            .edit()
            .putInt(PREF_GYRO_SENSITIVITY_Y, (sensitivity * 10f).toInt().coerceIn(1, 30))
            .apply()
    }

    fun getGyroSmoothing(context: Context): Float = prefs(context).getInt(PREF_GYRO_SMOOTHING, 25).coerceIn(0, 90) / 100f

    fun setGyroSmoothing(
        context: Context,
        smoothing: Float,
    ) {
        prefs(context)
            .edit()
            .putInt(PREF_GYRO_SMOOTHING, (smoothing * 100f).toInt().coerceIn(0, 90))
            .apply()
    }

    fun getGyroDeadzone(context: Context): Float = prefs(context).getInt(PREF_GYRO_DEADZONE, 20).coerceIn(0, 100) / 1000f

    fun setGyroDeadzone(
        context: Context,
        deadzone: Float,
    ) {
        prefs(context)
            .edit()
            .putInt(PREF_GYRO_DEADZONE, (deadzone * 1000f).toInt().coerceIn(0, 100))
            .apply()
    }

    fun getGyroResponse(context: Context): Float = prefs(context).getInt(PREF_GYRO_RESPONSE, 10).coerceIn(10, 20) / 10f

    fun setGyroResponse(
        context: Context,
        response: Float,
    ) {
        prefs(context)
            .edit()
            .putInt(PREF_GYRO_RESPONSE, (response * 10f).toInt().coerceIn(10, 20))
            .apply()
    }

    fun setGyroBias(
        context: Context,
        x: Float,
        y: Float,
    ) {
        prefs(context)
            .edit()
            .putFloat(PREF_GYRO_BIAS_X, x)
            .putFloat(PREF_GYRO_BIAS_Y, y)
            .apply()
    }

    fun getGyroTuning(context: Context): GyroTuning =
        GyroTuning(
            sensitivityX = getGyroSensitivity(context),
            sensitivityY = getGyroSensitivityY(context),
            deadzoneRadS = getGyroDeadzone(context),
            smoothing = getGyroSmoothing(context),
            responseExponent = getGyroResponse(context),
            biasX = prefs(context).getFloat(PREF_GYRO_BIAS_X, 0f),
            biasY = prefs(context).getFloat(PREF_GYRO_BIAS_Y, 0f),
        )

    fun getGyroActivation(context: Context): GyroActivation =
        GyroActivation.fromId(
            prefs(context).getInt(PREF_GYRO_ACTIVATION, GyroActivation.RIGHT_PAD_TOUCH.id),
        )

    fun setGyroActivation(
        context: Context,
        activation: GyroActivation,
    ) {
        prefs(context).edit().putInt(PREF_GYRO_ACTIVATION, activation.id).apply()
    }

    fun getGyroInvertY(context: Context): Boolean = prefs(context).getBoolean(PREF_GYRO_INVERT_Y, false)

    fun setGyroInvertY(
        context: Context,
        invert: Boolean,
    ) {
        prefs(context).edit().putBoolean(PREF_GYRO_INVERT_Y, invert).apply()
    }

    // ─── Button mapping ──────────────────────────────────────────────────────
    // Gamepad and desktop mappings are the same table under different key prefixes.
    private const val MAP_PREFIX = "map_"
    private const val DESKTOP_MAP_PREFIX = "desktop_map_"

    private fun mapKey(prefix: String, source: SteamButton) = "$prefix${source.name}"

    /** Reads a stored target, rewriting a legacy/unknown stored value to the canonical name. */
    private fun readMapping(
        context: Context,
        prefix: String,
        defaults: Map<SteamButton, XboxTarget>,
        source: SteamButton,
    ): XboxTarget {
        val key = mapKey(prefix, source)
        val default = defaults[source] ?: XboxTarget.NONE
        val stored = prefs(context).all[key]
        val target = XboxTarget.fromPersisted(stored) ?: default
        if (stored != null && stored != target.name) {
            prefs(context).edit().putString(key, target.name).apply()
        }
        return target
    }

    private fun writeMapping(
        context: Context,
        prefix: String,
        source: SteamButton,
        target: XboxTarget,
    ) {
        prefs(context).edit().putString(mapKey(prefix, source), target.name).apply()
    }

    private fun clearMappings(
        context: Context,
        prefix: String,
    ) {
        val edit = prefs(context).edit()
        SteamButton.values().forEach { edit.remove(mapKey(prefix, it)) }
        edit.apply()
    }

    fun getMapping(
        context: Context,
        source: SteamButton,
    ): XboxTarget = readMapping(context, MAP_PREFIX, DEFAULT_MAPPING, source)

    fun setMapping(
        context: Context,
        source: SteamButton,
        target: XboxTarget,
    ) = writeMapping(context, MAP_PREFIX, source, target)

    fun getAllMappings(context: Context): Map<SteamButton, XboxTarget> =
        SteamButton.values().associateWith { getMapping(context, it) }

    fun resetMappings(context: Context) = clearMappings(context, MAP_PREFIX)

    fun getDesktopMapping(
        context: Context,
        source: SteamButton,
    ): XboxTarget = readMapping(context, DESKTOP_MAP_PREFIX, DEFAULT_DESKTOP_MAPPING, source)

    fun setDesktopMapping(
        context: Context,
        source: SteamButton,
        target: XboxTarget,
    ) = writeMapping(context, DESKTOP_MAP_PREFIX, source, target)

    fun getAllDesktopMappings(context: Context): Map<SteamButton, XboxTarget> =
        SteamButton.values().associateWith { getDesktopMapping(context, it) }

    fun resetDesktopMappings(context: Context) = clearMappings(context, DESKTOP_MAP_PREFIX)

    // ─── Named profiles ──────────────────────────────────────────────────────
    // Profiles are stored as a single JSON array under KEY_NAMED_PROFILES.
    // Loading a profile overwrites every "live" preference key it captures.

    fun listNamedProfiles(context: Context): List<NamedProfile> =
        NamedProfile.listFromJson(prefs(context).getString(KEY_NAMED_PROFILES, "") ?: "")

    fun saveNamedProfile(
        context: Context,
        profile: NamedProfile,
    ) {
        val existing = listNamedProfiles(context).toMutableList()
        val idx = existing.indexOfFirst { it.id == profile.id }
        if (idx >= 0) existing[idx] = profile else existing.add(profile)
        persistNamedProfiles(context, existing)
    }

    fun deleteNamedProfile(
        context: Context,
        id: String,
    ) {
        val existing = listNamedProfiles(context).filter { it.id != id }
        persistNamedProfiles(context, existing)
        if (getActiveNamedProfileId(context) == id) setActiveNamedProfileId(context, null)
    }

    private fun persistNamedProfiles(
        context: Context,
        profiles: List<NamedProfile>,
    ) {
        prefs(
            context,
        ).edit().putString(KEY_NAMED_PROFILES, NamedProfile.listToJson(profiles)).apply()
    }

    fun getActiveNamedProfileId(context: Context): String? = prefs(context).getString(KEY_ACTIVE_NAMED_PROFILE_ID, null)

    fun setActiveNamedProfileId(
        context: Context,
        id: String?,
    ) {
        prefs(context).edit().putString(KEY_ACTIVE_NAMED_PROFILE_ID, id).apply()
    }

    /** Snapshot every live preference into a new NamedProfile under `name`.
     *  Note: transport (USB/BT) is intentionally NOT captured - it's a physical-link
     *  choice, not a game-tuning one, so loading a profile shouldn't drag the user
     *  back to USB when they're docked over BT. The field stays in NamedProfile
     *  schema for backward compat with V1.2 saves but isn't written or applied. */
    fun captureCurrentAsProfile(
        context: Context,
        name: String,
        existingId: String? = null,
    ): NamedProfile {
        val leftCal = getLeftCalibration(context)
        val rightCal = getRightCalibration(context)
        val mappingMap =
            SteamButton.values().associate {
                it.name to getMapping(context, it).name
            }
        val desktopMappingMap =
            SteamButton.values().associate {
                it.name to getDesktopMapping(context, it).name
            }
        val gyro = getGyroTuning(context)
        val boundPkgs =
            existingId?.let { id ->
                listNamedProfiles(context).firstOrNull { it.id == id }?.boundPackages
            }
                ?: emptyList()
        return NamedProfile(
            id =
                existingId ?: java.util.UUID
                    .randomUUID()
                    .toString(),
            name = name,
            profileId = getProfile(context).id,
            transport = 0, // unused - see captureCurrentAsProfile kdoc
            leftCenterX = leftCal.centerX,
            leftCenterY = leftCal.centerY,
            leftDeadzone = leftCal.deadzonePercent,
            leftInvertY = leftCal.invertY,
            rightCenterX = rightCal.centerX,
            rightCenterY = rightCal.centerY,
            rightDeadzone = rightCal.deadzonePercent,
            rightInvertY = rightCal.invertY,
            mouseSensitivity = getMouseSensitivity(context),
            trackpadAsMouse = getTrackpadAsMouseInGamepad(context),
            rumbleIntensity = getRumbleIntensity(context),
            mapping = mappingMap,
            desktopMapping = desktopMappingMap,
            gyroEnabled = getGyroEnabled(context),
            gyroActivation = getGyroActivation(context).id,
            gyroSensitivityX = gyro.sensitivityX,
            gyroSensitivityY = gyro.sensitivityY,
            gyroSmoothing = gyro.smoothing,
            gyroDeadzone = gyro.deadzoneRadS,
            gyroResponse = gyro.responseExponent,
            gyroInvertY = getGyroInvertY(context),
            gyroBiasX = gyro.biasX,
            gyroBiasY = gyro.biasY,
            boundPackages = boundPkgs,
        )
    }

    /** Apply a stored profile to the live preferences. Caller should restart the service.
     *  Transport (USB/BT) is intentionally NOT touched - the user picks the link in
     *  the main UI, profiles only configure the controller behaviour. */
    fun applyNamedProfile(
        context: Context,
        profile: NamedProfile,
    ) {
        setProfile(context, GamepadProfile.fromId(profile.profileId))
        setLeftCalibration(
            context,
            StickCalibration(
                centerX = profile.leftCenterX,
                centerY = profile.leftCenterY,
                deadzonePercent = profile.leftDeadzone,
                invertY = profile.leftInvertY,
            ),
        )
        setRightCalibration(
            context,
            StickCalibration(
                centerX = profile.rightCenterX,
                centerY = profile.rightCenterY,
                deadzonePercent = profile.rightDeadzone,
                invertY = profile.rightInvertY,
            ),
        )
        setMouseSensitivity(context, profile.mouseSensitivity)
        setTrackpadAsMouseInGamepad(context, profile.trackpadAsMouse)
        setRumbleIntensity(context, profile.rumbleIntensity)
        setGyroEnabled(context, profile.gyroEnabled)
        setGyroActivation(context, GyroActivation.fromId(profile.gyroActivation))
        setGyroSensitivity(context, profile.gyroSensitivityX)
        setGyroSensitivityY(context, profile.gyroSensitivityY)
        setGyroSmoothing(context, profile.gyroSmoothing)
        setGyroDeadzone(context, profile.gyroDeadzone)
        setGyroResponse(context, profile.gyroResponse)
        setGyroInvertY(context, profile.gyroInvertY)
        setGyroBias(context, profile.gyroBiasX, profile.gyroBiasY)
        SteamButton.values().forEach { btn ->
            profile.mapping[btn.name]?.let { stored ->
                setMapping(context, btn, XboxTarget.fromPersisted(stored) ?: XboxTarget.NONE)
            }
            profile.desktopMapping[btn.name]?.let { stored ->
                setDesktopMapping(
                    context,
                    btn,
                    XboxTarget.fromPersisted(stored) ?: XboxTarget.NONE,
                )
            }
        }
        setActiveNamedProfileId(context, profile.id)
    }

    // ─── show_ime_with_hard_keyboard override ──────────────────────────────────
    // Stores the pre-override value of the Settings.Secure key so it can be restored
    // when the controller disconnects. Written once per override (not overwritten
    // while an override is already pending), so a crash-without-restore doesn't
    // clobber the true original on the next connect/disconnect cycle.
    fun getSavedShowImeHardKeyboard(context: Context): String? = prefs(context).getString(KEY_SAVED_SHOW_IME_HARD_KB, null)

    fun setSavedShowImeHardKeyboard(
        context: Context,
        value: String,
    ) {
        prefs(context).edit().putString(KEY_SAVED_SHOW_IME_HARD_KB, value).apply()
    }

    fun clearSavedShowImeHardKeyboard(context: Context) {
        prefs(context).edit().remove(KEY_SAVED_SHOW_IME_HARD_KB).apply()
    }
}
