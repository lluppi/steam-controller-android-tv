package com.steamcontroller.android.input

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/** User tuning applied to quaternion-derived angular rates. */
data class GyroTuning(
    val sensitivityX: Float = 1f,
    val sensitivityY: Float = 1f,
    val deadzoneRadS: Float = 0.02f,
    val smoothing: Float = 0.25f,
    val responseExponent: Float = 1f,
    val biasX: Float = 0f,
    val biasY: Float = 0f
)

/** Converts orientation-quaternion deltas into reusable right-stick offsets. */
class GyroAim {
    companion object {
        private const val RATE_TO_STICK = 9000f
        private const val MAX_DT_MS = 100L
        private const val CALIBRATION_DURATION_MS = 2_000L
        private const val MIN_CALIBRATION_SAMPLES = 30
    }

    var stickX: Int = 0
        private set
    var stickY: Int = 0
        private set

    private var haveSample = false
    private var lastW = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var lastTimeMs = 0L
    private var smoothedX = 0f
    private var smoothedY = 0f
    private var calibrationCount = 0
    private var calibrationStartedMs = 0L
    private var calibrationSumX = 0f
    private var calibrationSumY = 0f

    /** Forget motion history so activation never replays movement that happened while inactive. */
    fun reset() {
        haveSample = false
        smoothedX = 0f
        smoothedY = 0f
        stickX = 0
        stickY = 0
    }

    fun update(qw: Short, qx: Short, qy: Short, qz: Short, nowMs: Long, tuning: GyroTuning) {
        val rates = angularRates(qw, qx, qy, qz, nowMs)
        if (rates == null) {
            stickX = 0
            stickY = 0
            return
        }

        val targetX =
            response(
                deadzone(rates.first - tuning.biasX, tuning.deadzoneRadS),
                tuning.responseExponent
            )
        val targetY =
            response(
                deadzone(rates.second - tuning.biasY, tuning.deadzoneRadS),
                tuning.responseExponent
            )
        val blend = (1f - tuning.smoothing).coerceIn(0.05f, 1f)
        smoothedX += (targetX - smoothedX) * blend
        smoothedY += (targetY - smoothedY) * blend
        stickX = toStick(smoothedX, tuning.sensitivityX)
        stickY = toStick(smoothedY, tuning.sensitivityY)
    }

    /** Returns measured stationary bias after roughly two seconds of report samples. */
    fun calibrate(qw: Short, qx: Short, qy: Short, qz: Short, nowMs: Long): Pair<Float, Float>? {
        val rates = angularRates(qw, qx, qy, qz, nowMs) ?: return null
        if (calibrationCount == 0) calibrationStartedMs = nowMs
        calibrationSumX += rates.first
        calibrationSumY += rates.second
        calibrationCount++
        if (nowMs - calibrationStartedMs < CALIBRATION_DURATION_MS ||
            calibrationCount < MIN_CALIBRATION_SAMPLES
        ) {
            return null
        }
        val result = calibrationSumX / calibrationCount to calibrationSumY / calibrationCount
        calibrationCount = 0
        calibrationStartedMs = 0L
        calibrationSumX = 0f
        calibrationSumY = 0f
        reset()
        return result
    }

    private fun angularRates(
        qw: Short,
        qx: Short,
        qy: Short,
        qz: Short,
        nowMs: Long
    ): Pair<Float, Float>? {
        val w = qw / 32767f
        val x = qx / 32767f
        val y = qy / 32767f
        val z = qz / 32767f
        val elapsedMs = nowMs - lastTimeMs
        if (!haveSample || elapsedMs !in 1..MAX_DT_MS) {
            store(w, x, y, z, nowMs)
            return null
        }

        val dt = elapsedMs / 1000f
        val deltaW = lastW * w + lastX * x + lastY * y + lastZ * z
        var deltaX = lastW * x - w * lastX - lastY * z + lastZ * y
        var deltaZ = lastW * z - w * lastZ - lastX * y + lastY * x
        if (deltaW < 0f) {
            deltaX = -deltaX
            deltaZ = -deltaZ
        }
        store(w, x, y, z, nowMs)
        return 2f * deltaZ / dt to 2f * deltaX / dt
    }

    private fun store(w: Float, x: Float, y: Float, z: Float, timeMs: Long) {
        lastW = w
        lastX = x
        lastY = y
        lastZ = z
        lastTimeMs = timeMs
        haveSample = true
    }

    private fun deadzone(rate: Float, threshold: Float): Float {
        val magnitude = abs(rate)
        return if (magnitude <= threshold) 0f else (magnitude - threshold) * sign(rate)
    }

    private fun response(rate: Float, exponent: Float): Float =
        abs(rate).pow(exponent.coerceIn(1f, 2f)) * sign(rate)

    private fun toStick(rate: Float, sensitivity: Float): Int =
        (rate * sensitivity * RATE_TO_STICK).roundToInt().coerceIn(-32767, 32767)
}
