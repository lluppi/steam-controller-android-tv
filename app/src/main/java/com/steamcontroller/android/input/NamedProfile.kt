package com.steamcontroller.android.input

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * A named profile snapshot - everything the user can tune, captured under a single name.
 *
 * Profiles live in SharedPreferences as a JSON array. Loading a profile overwrites the
 * matching live preference keys (profileId, transport, sticks, mappings, etc.) so the
 * very next service start uses the profile's settings.
 *
 * `boundPackages` powers the foreground-app auto-switch: if the currently
 * focused Android app matches any package in this profile's list, the service swaps in.
 */
data class NamedProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val profileId: Int,
    val transport: Int,
    val leftCenterX: Int,
    val leftCenterY: Int,
    val leftDeadzone: Int,
    val leftInvertY: Boolean,
    val rightCenterX: Int,
    val rightCenterY: Int,
    val rightDeadzone: Int,
    val rightInvertY: Boolean,
    val mouseSensitivity: Float,
    val trackpadAsMouse: Boolean,
    val rumbleIntensity: Int,
    /** Serialised as `SteamButton.name` to stable `XboxTarget.name` values. */
    val mapping: Map<String, String>,
    val desktopMapping: Map<String, String> = emptyMap(),
    val gyroEnabled: Boolean = false,
    val gyroActivation: Int = GyroActivation.RIGHT_PAD_TOUCH.id,
    val gyroSensitivityX: Float = 1f,
    val gyroSensitivityY: Float = 1f,
    val gyroSmoothing: Float = 0.25f,
    val gyroDeadzone: Float = 0.02f,
    val gyroResponse: Float = 1f,
    val gyroInvertY: Boolean = false,
    val gyroBiasX: Float = 0f,
    val gyroBiasY: Float = 0f,
    val boundPackages: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_NAME, name)
        put(KEY_PROFILE_ID, profileId)
        put(KEY_TRANSPORT, transport)
        put(KEY_LCX, leftCenterX)
        put(KEY_LCY, leftCenterY)
        put(KEY_LDZ, leftDeadzone)
        put(KEY_LIY, leftInvertY)
        put(KEY_RCX, rightCenterX)
        put(KEY_RCY, rightCenterY)
        put(KEY_RDZ, rightDeadzone)
        put(KEY_RIY, rightInvertY)
        put(KEY_MS, mouseSensitivity.toDouble())
        put(KEY_TAM, trackpadAsMouse)
        put(KEY_RI, rumbleIntensity)
        put(KEY_MAPPING, JSONObject(mapping as Map<*, *>))
        put(KEY_DESKTOP_MAPPING, JSONObject(desktopMapping as Map<*, *>))
        put(KEY_GYRO_ENABLED, gyroEnabled)
        put(KEY_GYRO_ACTIVATION, gyroActivation)
        put(KEY_GYRO_SENS_X, gyroSensitivityX.toDouble())
        put(KEY_GYRO_SENS_Y, gyroSensitivityY.toDouble())
        put(KEY_GYRO_SMOOTHING, gyroSmoothing.toDouble())
        put(KEY_GYRO_DEADZONE, gyroDeadzone.toDouble())
        put(KEY_GYRO_RESPONSE, gyroResponse.toDouble())
        put(KEY_GYRO_INVERT_Y, gyroInvertY)
        put(KEY_GYRO_BIAS_X, gyroBiasX.toDouble())
        put(KEY_GYRO_BIAS_Y, gyroBiasY.toDouble())
        put(KEY_BOUND, JSONArray(boundPackages))
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_PROFILE_ID = "profileId"
        private const val KEY_TRANSPORT = "transport"
        private const val KEY_LCX = "lcx"
        private const val KEY_LCY = "lcy"
        private const val KEY_LDZ = "ldz"
        private const val KEY_LIY = "liy"
        private const val KEY_RCX = "rcx"
        private const val KEY_RCY = "rcy"
        private const val KEY_RDZ = "rdz"
        private const val KEY_RIY = "riy"
        private const val KEY_MS = "mouseSens"
        private const val KEY_TAM = "trackpadAsMouse"
        private const val KEY_RI = "rumbleIntensity"
        private const val KEY_MAPPING = "mapping"
        private const val KEY_DESKTOP_MAPPING = "desktopMapping"
        private const val KEY_GYRO_ENABLED = "gyroEnabled"
        private const val KEY_GYRO_ACTIVATION = "gyroActivation"
        private const val KEY_GYRO_SENS_X = "gyroSensitivityX"
        private const val KEY_GYRO_SENS_Y = "gyroSensitivityY"
        private const val KEY_GYRO_SMOOTHING = "gyroSmoothing"
        private const val KEY_GYRO_DEADZONE = "gyroDeadzone"
        private const val KEY_GYRO_RESPONSE = "gyroResponse"
        private const val KEY_GYRO_INVERT_Y = "gyroInvertY"
        private const val KEY_GYRO_BIAS_X = "gyroBiasX"
        private const val KEY_GYRO_BIAS_Y = "gyroBiasY"
        private const val KEY_BOUND = "boundPackages"

        private fun parseMapping(obj: JSONObject?): Map<String, String> {
            val source = obj ?: return emptyMap()
            return buildMap {
                source.keys().forEach { key ->
                    XboxTarget.fromPersisted(source.opt(key))?.let { put(key, it.name) }
                }
            }
        }

        fun fromJson(obj: JSONObject): NamedProfile {
            val mapping = parseMapping(obj.optJSONObject(KEY_MAPPING))
            val desktopMapping = parseMapping(obj.optJSONObject(KEY_DESKTOP_MAPPING))

            val boundArr = obj.optJSONArray(KEY_BOUND)
            val bound = if (boundArr !=
                null
            ) {
                (0 until boundArr.length()).map { boundArr.getString(it) }
            } else {
                emptyList()
            }

            return NamedProfile(
                id = obj.optString(KEY_ID, UUID.randomUUID().toString()),
                name = obj.optString(KEY_NAME, "Profile"),
                profileId = obj.optInt(KEY_PROFILE_ID, 0),
                transport = obj.optInt(KEY_TRANSPORT, 0),
                leftCenterX = obj.optInt(KEY_LCX, 0),
                leftCenterY = obj.optInt(KEY_LCY, 0),
                leftDeadzone = obj.optInt(KEY_LDZ, 8),
                leftInvertY = obj.optBoolean(KEY_LIY, false),
                rightCenterX = obj.optInt(KEY_RCX, 0),
                rightCenterY = obj.optInt(KEY_RCY, 0),
                rightDeadzone = obj.optInt(KEY_RDZ, 8),
                rightInvertY = obj.optBoolean(KEY_RIY, false),
                mouseSensitivity = obj.optDouble(KEY_MS, 1.0).toFloat(),
                trackpadAsMouse = obj.optBoolean(KEY_TAM, true),
                rumbleIntensity = obj.optInt(KEY_RI, 100),
                mapping = mapping,
                desktopMapping = desktopMapping,
                gyroEnabled = obj.optBoolean(KEY_GYRO_ENABLED, false),
                gyroActivation =
                    obj.optInt(KEY_GYRO_ACTIVATION, GyroActivation.RIGHT_PAD_TOUCH.id),
                gyroSensitivityX = obj.optDouble(KEY_GYRO_SENS_X, 1.0).toFloat(),
                gyroSensitivityY = obj.optDouble(KEY_GYRO_SENS_Y, 1.0).toFloat(),
                gyroSmoothing = obj.optDouble(KEY_GYRO_SMOOTHING, 0.25).toFloat(),
                gyroDeadzone = obj.optDouble(KEY_GYRO_DEADZONE, 0.02).toFloat(),
                gyroResponse = obj.optDouble(KEY_GYRO_RESPONSE, 1.0).toFloat(),
                gyroInvertY = obj.optBoolean(KEY_GYRO_INVERT_Y, false),
                gyroBiasX = obj.optDouble(KEY_GYRO_BIAS_X, 0.0).toFloat(),
                gyroBiasY = obj.optDouble(KEY_GYRO_BIAS_Y, 0.0).toFloat(),
                boundPackages = bound
            )
        }

        fun listToJson(profiles: List<NamedProfile>): String =
            JSONArray().apply { profiles.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(json: String): List<NamedProfile> {
            if (json.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }
}
