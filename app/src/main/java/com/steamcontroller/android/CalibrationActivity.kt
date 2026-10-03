package com.steamcontroller.android

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import com.steamcontroller.android.databinding.ActivityCalibrationBinding
import com.steamcontroller.android.input.GyroActivation
import com.steamcontroller.android.input.GyroAim
import com.steamcontroller.android.input.StickCalibration
import com.steamcontroller.android.service.ControllerService
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class CalibrationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCalibrationBinding

    private var lastLeftRawX = 0
    private var lastLeftRawY = 0
    private var lastRightRawX = 0
    private var lastRightRawY = 0

    private var leftCal = StickCalibration()
    private var rightCal = StickCalibration()
    private var gyroCalibrator: GyroAim? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_reset) {
                resetAll()
                true
            } else {
                false
            }
        }

        leftCal = Prefs.getLeftCalibration(this)
        rightCal = Prefs.getRightCalibration(this)
        bindUiFromState()

        // Manual haptic tests go directly to the physical controller on either transport.
        // Game-driven force feedback still depends on the selected virtual-device backend.
        lifecycleScope.launch {
            ControllerService.serviceStateFlow.collect { state ->
                val running = state.mode != ControllerService.InjectionMode.NONE
                binding.btnTestRumble.isEnabled = running
                binding.sliderRumbleIntensity.isEnabled = running
                binding.btnTestRumble.text =
                    if (state.rumbleSupported) {
                        getString(R.string.calib_test_rumble)
                    } else {
                        getString(R.string.calib_rumble_unsupported)
                    }
            }
        }

        binding.btnTestRumble.setOnClickListener {
            if (ControllerService.serviceStateFlow.value.mode ==
                ControllerService.InjectionMode.NONE
            ) {
                Toast.makeText(this, "Start the service first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent =
                Intent(this, ControllerService::class.java).apply {
                    action = ControllerService.ACTION_TEST_RUMBLE
                }
            startService(intent)
        }

        // Rumble intensity slider - applied live to all subsequent rumble events
        bindSlider(
            binding.sliderRumbleIntensity,
            binding.tvRumbleIntensity,
            Prefs.getRumbleIntensity(this).toFloat(),
            { "${it.toInt()}%" },
        ) { Prefs.setRumbleIntensity(this, it.toInt()) }

        // Mouse sensitivity slider - only relevant in Desktop profile but always visible
        bindSlider(
            binding.sliderMouseSensitivity,
            binding.tvMouseSensitivity,
            Prefs.getMouseSensitivity(this),
            { "%.1f×".format(it) },
        ) { Prefs.setMouseSensitivity(this, it) }

        // Trackpads-as-mouse toggle for gamepad mode.
        // UInputGamepad re-reads the pref at most every 250ms so flipping it is
        // effectively live without restarting the service.
        bindSwitch(binding.switchTrackpadAsMouse, Prefs.getTrackpadAsMouseInGamepad(this)) {
            Prefs.setTrackpadAsMouseInGamepad(this, it)
        }
        bindSwitch(binding.switchStartOnBoot, Prefs.getStartOnBoot(this)) {
            Prefs.setStartOnBoot(this, it)
        }
        bindSwitch(binding.switchStartOnConnect, Prefs.getStartOnControllerConnect(this)) {
            Prefs.setStartOnControllerConnect(this, it)
        }

        val gyroControls = binding.gyroControls
        bindSwitch(gyroControls.switchGyroEnabled, Prefs.getGyroEnabled(this)) {
            Prefs.setGyroEnabled(this, it)
        }
        bindSlider(
            gyroControls.sliderGyroSensitivity,
            gyroControls.tvGyroSensitivity,
            Prefs.getGyroSensitivity(this),
            { "%.1f× horizontal".format(it) },
        ) { Prefs.setGyroSensitivity(this, it) }
        bindSlider(
            gyroControls.sliderGyroSensitivityY,
            gyroControls.tvGyroSensitivityY,
            Prefs.getGyroSensitivityY(this),
            { "%.1f× vertical".format(it) },
        ) { Prefs.setGyroSensitivityY(this, it) }
        bindSlider(
            gyroControls.sliderGyroSmoothing,
            gyroControls.tvGyroSmoothing,
            Prefs.getGyroSmoothing(this),
            { "%.0f%% smoothing".format(it * 100f) },
        ) { Prefs.setGyroSmoothing(this, it) }
        bindSlider(
            gyroControls.sliderGyroDeadzone,
            gyroControls.tvGyroDeadzone,
            Prefs.getGyroDeadzone(this),
            { "%.3f rad/s deadzone".format(it) },
        ) { Prefs.setGyroDeadzone(this, it) }
        bindSlider(
            gyroControls.sliderGyroResponse,
            gyroControls.tvGyroResponse,
            Prefs.getGyroResponse(this),
            { "%.1f response".format(it) },
        ) { Prefs.setGyroResponse(this, it) }
        gyroControls.btnGyroCalibrate.setOnClickListener {
            if (ControllerService.stateFlow.value == null) {
                Toast.makeText(this, "Start the service first", Toast.LENGTH_SHORT).show()
            } else {
                gyroCalibrator = GyroAim()
                gyroControls.btnGyroCalibrate.text = getString(R.string.calib_gyro_calibrating)
            }
        }
        bindSwitch(gyroControls.switchGyroInvertY, Prefs.getGyroInvertY(this)) {
            Prefs.setGyroInvertY(this, it)
        }
        fun renderGyroActivation() {
            val activation = Prefs.getGyroActivation(this)
            gyroControls.btnGyroActivation.text =
                getString(R.string.calib_gyro_activation) + ": " + activation.displayName
        }
        renderGyroActivation()
        gyroControls.btnGyroActivation.setOnClickListener {
            val current = Prefs.getGyroActivation(this)
            val next = GyroActivation.ALL[(current.ordinal + 1) % GyroActivation.ALL.size]
            Prefs.setGyroActivation(this, next)
            renderGyroActivation()
        }

        // Live preview from the running service
        lifecycleScope.launch {
            ControllerService.stateFlow.filterNotNull().collect { state ->
                lastLeftRawX = state.leftJoyX.toInt()
                lastLeftRawY = state.leftJoyY.toInt()
                lastRightRawX = state.rightJoyX.toInt()
                lastRightRawY = state.rightJoyY.toInt()

                gyroCalibrator?.calibrate(
                    state.quatW,
                    state.quatX,
                    state.quatY,
                    state.quatZ,
                    SystemClock.uptimeMillis()
                )?.let { (biasX, biasY) ->
                    Prefs.setGyroBias(this@CalibrationActivity, biasX, biasY)
                    gyroCalibrator = null
                    binding.gyroControls.btnGyroCalibrate.text =
                        getString(R.string.calib_gyro_calibrate)
                    Toast.makeText(
                        this@CalibrationActivity,
                        R.string.calib_gyro_calibrated,
                        Toast.LENGTH_SHORT
                    ).show()
                }

                val (lx, ly) = leftCal.apply(lastLeftRawX, lastLeftRawY)
                val (rx, ry) = rightCal.apply(lastRightRawX, lastRightRawY)

                binding.padLeft.setPosition(lx / 32767f, ly / 32767f)
                binding.padRight.setPosition(rx / 32767f, ry / 32767f)

                binding.tvLeftRaw.text = "raw: %5d, %5d".format(lastLeftRawX, lastLeftRawY)
                binding.tvRightRaw.text = "raw: %5d, %5d".format(lastRightRawX, lastRightRawY)
            }
        }

        wireControls()
    }

    private fun bindUiFromState() {
        binding.sliderLeftDeadzone.value = leftCal.deadzonePercent.toFloat()
        binding.sliderRightDeadzone.value = rightCal.deadzonePercent.toFloat()
        binding.tvLeftDeadzone.text = "${leftCal.deadzonePercent}%"
        binding.tvRightDeadzone.text = "${rightCal.deadzonePercent}%"
        binding.switchLeftInvertY.isChecked = leftCal.invertY
        binding.switchRightInvertY.isChecked = rightCal.invertY

        binding.padLeft.deadzoneFraction = leftCal.deadzonePercent / 100f
        binding.padRight.deadzoneFraction = rightCal.deadzonePercent / 100f
    }

    private fun wireControls() {
        binding.sliderLeftDeadzone.addOnChangeListener(
            Slider.OnChangeListener { _, value, _ ->
                leftCal = leftCal.copy(deadzonePercent = value.toInt())
                binding.tvLeftDeadzone.text = "${leftCal.deadzonePercent}%"
                binding.padLeft.deadzoneFraction = leftCal.deadzonePercent / 100f
                binding.padLeft.invalidate()
                saveLeft()
            }
        )

        binding.sliderRightDeadzone.addOnChangeListener(
            Slider.OnChangeListener { _, value, _ ->
                rightCal = rightCal.copy(deadzonePercent = value.toInt())
                binding.tvRightDeadzone.text = "${rightCal.deadzonePercent}%"
                binding.padRight.deadzoneFraction = rightCal.deadzonePercent / 100f
                binding.padRight.invalidate()
                saveRight()
            }
        )

        binding.switchLeftInvertY.setOnCheckedChangeListener { _, checked ->
            leftCal = leftCal.copy(invertY = checked)
            saveLeft()
        }
        binding.switchRightInvertY.setOnCheckedChangeListener { _, checked ->
            rightCal = rightCal.copy(invertY = checked)
            saveRight()
        }

        binding.btnCalibrateAll.setOnClickListener {
            leftCal = leftCal.copy(centerX = lastLeftRawX, centerY = lastLeftRawY)
            rightCal = rightCal.copy(centerX = lastRightRawX, centerY = lastRightRawY)
            saveLeft()
            saveRight()
            android.widget.Toast
                .makeText(this, "Sticks calibrated", android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }

    /** Seeds a slider + its value label from a saved value, then saves every change live. */
    private fun bindSlider(
        slider: Slider,
        label: TextView,
        initial: Float,
        format: (Float) -> String,
        save: (Float) -> Unit,
    ) {
        slider.value = initial
        label.text = format(initial)
        slider.addOnChangeListener(
            Slider.OnChangeListener { _, value, _ ->
                label.text = format(value)
                save(value)
            },
        )
    }

    private fun bindSwitch(
        switch: CompoundButton,
        initial: Boolean,
        save: (Boolean) -> Unit,
    ) {
        switch.isChecked = initial
        switch.setOnCheckedChangeListener { _, checked -> save(checked) }
    }

    private fun saveLeft() = Prefs.setLeftCalibration(this, leftCal)

    private fun saveRight() = Prefs.setRightCalibration(this, rightCal)

    private fun resetAll() {
        leftCal = StickCalibration()
        rightCal = StickCalibration()
        saveLeft()
        saveRight()
        bindUiFromState()
    }
}
