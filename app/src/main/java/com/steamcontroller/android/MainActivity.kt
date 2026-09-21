package com.steamcontroller.android

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.steamcontroller.android.bt.BluetoothHidManager
import com.steamcontroller.android.databinding.ActivityMainBinding
import com.steamcontroller.android.service.ControllerService
import com.steamcontroller.android.shizuku.ShizukuStarterService
import com.steamcontroller.android.uinput.GamepadProfile
import com.steamcontroller.android.uinput.UInputNative
import com.steamcontroller.android.usb.UsbConnectionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"

    /** Pause between stopping and restarting the service on a reconnect. */
    private val SERVICE_RESTART_DELAY_MS = 1200L
    private lateinit var binding: ActivityMainBinding
    private var serviceRunning = false
    private var pairedBtDevices: List<BluetoothDevice> = emptyList()

    // Tracks whether the current "started" session has reached a working injection mode.
    // Used so the modeFlow observer doesn't mistake StateFlow's initial NONE replay for
    // an external service stop right after the user pressed Start.
    private var hasSeenActiveMode = false

    private val usbPermissionAction = "com.steamcontroller.android.USB_PERMISSION"
    private val githubRepoUrl = "https://github.com/SonicDX12/SteamController-Android"
    private val shizukuPackage = ShizukuStarterService.SHIZUKU_PACKAGE
    private val tvSettingsStubPackage = "com.google.android.tv.frameworkpackagestubs"

    private val usbReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    usbPermissionAction -> {
                        val granted = intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED,
                            false
                        )
                        if (granted) {
                            val device: UsbDevice? = intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE
                            )
                            device?.let { startControllerService(it) }
                        } else {
                            log("USB permission denied")
                            Toast.makeText(
                                this@MainActivity,
                                "USB permission denied",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        device?.let { onDeviceAttached(it) }
                    }

                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        stopControllerService()
                        updateStatus()
                        log("Controller disconnected")
                    }
                }
            }
        }

    private val shizukuRequestCode = 1001

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                log("Shizuku permission granted")
                updateShizukuStatus(true)
                checkAndRequestUsb()
            } else {
                log("Shizuku permission denied")
                Toast.makeText(
                    this,
                    getString(R.string.shizuku_permission_denied),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvSubtitle.text = "${binding.tvSubtitle.text} · v${BuildConfig.VERSION_NAME}"

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        val filter =
            IntentFilter().apply {
                addAction(usbPermissionAction)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        binding.btnToggleService.setOnClickListener {
            if (serviceRunning) {
                stopControllerService()
            } else {
                checkPermissionsAndStart()
            }
        }

        binding.btnDebug.setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }

        binding.btnCalibration.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }

        binding.btnMapping.setOnClickListener {
            startActivity(Intent(this, MappingActivity::class.java))
        }

        binding.btnGameProfiles.setOnClickListener {
            startActivity(Intent(this, ProfilesActivity::class.java))
        }

        setupTransportDropdown()
        setupActionCardFocusMemory()

        // The status card starts Shizuku when needed, otherwise it reconnects the controller.
        binding.statusPillCard?.setOnClickListener { handleStatusAction() }
        setupControlModeToggle()
        requestNotificationPermissionIfNeeded()

        binding.btnRefreshBt.setOnClickListener {
            log("Refreshing Bluetooth devices…")
            ensureBluetoothPermissionThenRefresh()
        }

        binding.btnHelp.setOnClickListener { showConnectionHelpDialog() }
        binding.btnGithub.setOnClickListener { openGithubRepo() }

        // One collector for the service's whole state. It used to be four (mode, profile, battery,
        // link) that could disagree with each other, and every new piece of state meant hand-syncing
        // another collector.
        lifecycleScope.launch {
            ControllerService.serviceStateFlow.collect { state ->
                // Mode → the toggle button, and detect the service being stopped externally (e.g. via
                // the notification action) so the button flips back to "Start".
                //
                // Subtlety: a fresh `collect` on a StateFlow immediately replays its current value,
                // which is NONE when no service ever ran. If the user taps Start before that replay
                // runs, we would see mode==NONE && serviceRunning==true and wrongly reset the button.
                // `hasSeenActiveMode` defends against that: a NONE only counts as "stopped" once a
                // working mode has been seen in this session.
                if (state.mode != ControllerService.InjectionMode.NONE) {
                    hasSeenActiveMode = true
                    // Catch-up sync: the activity was re-entered while the service was already up.
                    if (!serviceRunning) {
                        serviceRunning = true
                        binding.btnToggleService.text = getString(R.string.btn_stop)
                    }
                } else if (serviceRunning && hasSeenActiveMode) {
                    serviceRunning = false
                    hasSeenActiveMode = false
                    binding.btnToggleService.text = getString(R.string.btn_start)
                    log("Service stopped")
                }

                // Profile changes (e.g. cycling via the notification action) move the control-mode
                // toggle as well.
                state.profileId?.let { id -> syncControlModeToggle(GamepadProfile.fromId(id)) }

                binding.tvBattery.text =
                    state.batteryPercent?.let { "Battery: $it%" } ?: "Battery: —"

                refreshModeLabel()
                updateStatus()
            }
        }

        // Handle intent if launched by USB attach event
        intent?.let { handleIntent(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            device?.let { onDeviceAttached(it) }
        }
    }

    /**
     * TV focus memory for the bottom action row.
     *
     * Going UP from a card to the Start/Stop button and then back DOWN should return you to
     * the card you came from. Once Stop's DOWN hop is explicit (see the television layout)
     * the framework's geometric search no longer picks the remembered card, so track it here.
     */
    private fun setupActionCardFocusMemory() {
        val cards =
            listOf(
                binding.btnCalibration,
                binding.btnMapping,
                binding.btnGameProfiles,
                binding.btnDebug
            )
        // Matches the layout's default; used until a card has been visited this session.
        binding.btnToggleService.nextFocusDownId = binding.btnCalibration.id
        for (card in cards) {
            card.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) binding.btnToggleService.nextFocusDownId = card.id
            }
        }
    }

    private fun setupTransportDropdown() {
        // Reflect the saved transport in the toggle group
        val current = Prefs.getTransport(this)
        val initialButtonId =
            when (current) {
                Transport.USB -> R.id.btnTransportUsb
                Transport.BLUETOOTH -> R.id.btnTransportBt
            }
        binding.toggleTransport.check(initialButtonId)

        // On TV the end icon inside the dropdown field is its own focus stop, and DOWN from it
        // jumped straight past the "Start Service" button to the bottom card row (the field's
        // nextFocusDown never applied, because focus was on the icon rather than the field).
        // The field itself still opens the picker, so take the icon out of the focus order.
        // Reached by id because TextInputLayout's endIconView is package-private.
        binding.tilBtDevice
            .findViewById<View?>(com.google.android.material.R.id.text_input_end_icon)
            ?.isFocusable = false

        updateBtPickerVisibility(current)

        binding.toggleTransport.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener // only react when something is selected
            val picked =
                when (checkedId) {
                    R.id.btnTransportUsb -> Transport.USB
                    R.id.btnTransportBt -> Transport.BLUETOOTH
                    else -> return@addOnButtonCheckedListener
                }
            if (picked == Prefs.getTransport(this)) return@addOnButtonCheckedListener // no-op
            Prefs.setTransport(this, picked)
            updateBtPickerVisibility(picked)
            // Status pill shows the active transport — refresh on change
            updateShizukuStatus(hasShizukuAccess())
            log("Transport set: ${picked.displayName}")
            if (serviceRunning) {
                Toast.makeText(this, "Restart the service to apply", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateBtPickerVisibility(t: Transport) {
        if (t == Transport.BLUETOOTH) {
            binding.btDeviceRow.visibility = View.VISIBLE
            ensureBluetoothPermissionThenRefresh()
        } else {
            binding.btDeviceRow.visibility = View.GONE
        }
    }

    private fun ensureBluetoothPermissionThenRefresh() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.BLUETOOTH_CONNECT
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.BLUETOOTH_SCAN
            }
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 9002)
        } else {
            refreshBluetoothDevices()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 9002 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            refreshBluetoothDevices()
        }
    }

    private fun refreshBluetoothDevices() {
        val mgr = getSystemService(BluetoothManager::class.java)
        if (mgr?.adapter?.isEnabled != true) {
            log("Bluetooth disabled — enable it in settings")
            Toast.makeText(this, "Enable Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }
        pairedBtDevices = BluetoothHidManager(this).listPairedSteamControllers()
        if (pairedBtDevices.isEmpty()) {
            binding.dropdownBtDevice.setAdapter(
                nonFilteringAdapter(listOf("No paired Steam Controller found"))
            )
            binding.dropdownBtDevice.setText("No paired Steam Controller found", false)
            log("No Steam Controller paired — pair via Android Bluetooth settings first")
            return
        }
        // Show just the friendly name — the MAC address took an extra wrapped line
        // and the user never types it manually. Address is still saved to Prefs.
        val labels =
            pairedBtDevices.map { dev ->
                try {
                    dev.name
                } catch (_: SecurityException) {
                    null
                } ?: "Unknown"
            }
        binding.dropdownBtDevice.setAdapter(nonFilteringAdapter(labels))
        binding.dropdownBtDevice.threshold = 0

        val savedAddress = Prefs.getBluetoothAddress(this)
        val currentIdx = pairedBtDevices.indexOfFirst {
            it.address == savedAddress
        }.coerceAtLeast(0)
        binding.dropdownBtDevice.setText(labels[currentIdx], false)
        Prefs.setBluetoothAddress(this, pairedBtDevices[currentIdx].address)
        Prefs.setBluetoothName(this, labels[currentIdx].takeUnless { it == "Unknown" })

        binding.dropdownBtDevice.setOnItemClickListener { _, _, position, _ ->
            val picked = pairedBtDevices[position]
            Prefs.setBluetoothAddress(this, picked.address)
            Prefs.setBluetoothName(this, labels[position].takeUnless { it == "Unknown" })
            log("BT device: ${picked.address}")
        }
    }

    /**
     * ArrayAdapter with a no-op Filter so every item is always shown when the dropdown opens,
     * regardless of the text already in the field. Works around an M3 quirk where filtering
     * is applied even after [MaterialAutoCompleteTextView.setSimpleItems].
     */
    private fun nonFilteringAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, items) {
            private val noFilter =
                object : Filter() {
                    override fun performFiltering(constraint: CharSequence?): FilterResults =
                        FilterResults().apply {
                            values = items
                            count = items.size
                        }

                    override fun publishResults(
                        constraint: CharSequence?,
                        results: FilterResults?
                    ) {
                        notifyDataSetChanged()
                    }
                }

            override fun getFilter(): Filter = noFilter
        }

    private fun refreshModeLabel() {
        val state = ControllerService.serviceStateFlow.value
        val backend = UInputNative.backendName(state.backend)
        val profileName = Prefs.getProfile(this).displayName

        binding.tvMode.text =
            when (state.mode) {
                ControllerService.InjectionMode.UINPUT,
                ControllerService.InjectionMode.UHID
                -> {
                    "Mode: $profileName ($backend) ✓"
                }

                // Say *why* input is limited instead of leaving it as an unexplained "limited".
                ControllerService.InjectionMode.SHIZUKU_INJECT -> {
                    buildString {
                        append("Mode: Shizuku inject — some apps ignore input")
                        if (state.backendDetail.isNotEmpty()) append("\n${state.backendDetail}")
                    }
                }

                ControllerService.InjectionMode.NONE -> {
                    "Mode: —"
                }
            }
    }

    private fun showConnectionHelpDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_connection_help, null)

        fun fillBullet(id: Int, html: String) {
            val row = view.findViewById<View>(id)
            val tv = row.findViewById<android.widget.TextView>(R.id.bulletText)
            tv.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
        }

        fillBullet(
            R.id.bulletPuckRight,
            "<b>Puck (right slot)</b> — hold <b>A + R1 + Steam</b>, chime + white LED."
        )
        fillBullet(
            R.id.bulletPuckLeft,
            "<b>Puck (left slot)</b> — hold <b>A + L1 + Steam</b>, chime + white LED."
        )
        fillBullet(
            R.id.bulletBluetooth,
            "<b>Bluetooth</b> — hold <b>B + R1 + Steam</b>, chime + blue LED."
        )
        fillBullet(
            R.id.bulletWiredOff,
            "Controller is <b>off</b> — plug it into the device. Chime + green LED."
        )
        fillBullet(
            R.id.bulletWiredOn,
            "Controller is <b>on</b> in another mode — hold <b>Steam</b> while plugging it in. Chime + green LED."
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help_dialog_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted =
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9001)
        }
    }

    /** Control mode toggle between the Xbox 360 gamepad and Desktop devices. */
    private fun setupControlModeToggle() {
        syncControlModeToggle(Prefs.getProfile(this))

        binding.toggleControlMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val picked =
                when (checkedId) {
                    R.id.btnModeGamepad -> Prefs.getLastGamepadProfile(this)
                    R.id.btnModeDesktop -> GamepadProfile.MOUSE
                    else -> return@addOnButtonCheckedListener
                }
            if (picked.id == Prefs.getProfile(this).id) return@addOnButtonCheckedListener
            Prefs.setProfile(this, picked)
            syncControlModeToggle(picked)
            log("Control mode → ${picked.displayName}")
            if (serviceRunning) {
                Toast.makeText(this, "Restart the service to apply", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Programmatically sync the Gamepad/Desktop toggle to the given profile (no re-emit feedback loop). */
    private fun syncControlModeToggle(profile: GamepadProfile) {
        val targetId = if (profile.isMouseMode) R.id.btnModeDesktop else R.id.btnModeGamepad
        if (binding.toggleControlMode.checkedButtonId != targetId) {
            binding.toggleControlMode.check(targetId)
        }
    }

    private fun openGithubRepo() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubRepoUrl)))
        } catch (t: Throwable) {
            Toast.makeText(this, "No browser app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissionsAndStart() {
        when {
            !Shizuku.pingBinder() -> {
                log("Shizuku not running")
                Toast.makeText(
                    this,
                    getString(R.string.shizuku_not_running),
                    Toast.LENGTH_LONG
                ).show()
            }

            Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                Shizuku.requestPermission(shizukuRequestCode)
            }

            else -> {
                updateShizukuStatus(true)
                when (Prefs.getTransport(this)) {
                    Transport.USB -> checkAndRequestUsb()
                    Transport.BLUETOOTH -> startBluetoothService()
                }
            }
        }
    }

    private fun startBluetoothService() {
        if (Prefs.getBluetoothAddress(this) == null) {
            Toast.makeText(this, "Select a paired Bluetooth device first", Toast.LENGTH_LONG).show()
            return
        }
        Prefs.setUserStoppedService(this, false)
        val intent = Intent(this, ControllerService::class.java)
        startForegroundService(intent)
        serviceRunning = true
        // Don't fake "connected" here — stateFlow will flip it once a real HID frame lands.
        binding.btnToggleService.text = getString(R.string.btn_stop)
        log("Service started (Bluetooth)")
    }

    private fun checkAndRequestUsb() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val device =
            usbManager.deviceList.values.firstOrNull {
                it.vendorId == UsbConnectionManager.STEAM_VID
            }

        if (device == null) {
            log("No Steam Controller found — plug it in first")
            Toast.makeText(this, "No Steam Controller detected", Toast.LENGTH_LONG).show()
            return
        }

        if (usbManager.hasPermission(device)) {
            startControllerService(device)
        } else {
            val permIntent =
                PendingIntent.getBroadcast(
                    this,
                    0,
                    Intent(usbPermissionAction),
                    PendingIntent.FLAG_IMMUTABLE
                )
            usbManager.requestPermission(device, permIntent)
            log("Requesting USB permission...")
        }
    }

    private fun onDeviceAttached(device: UsbDevice) {
        if (device.vendorId != UsbConnectionManager.STEAM_VID) return
        log("Steam Controller attached")
        checkPermissionsAndStart()
    }

    private fun startControllerService(device: UsbDevice) {
        Prefs.setUserStoppedService(this, false)
        val intent =
            Intent(this, ControllerService::class.java).apply {
                putExtra(ControllerService.EXTRA_DEVICE, device)
            }
        startForegroundService(intent)
        serviceRunning = true
        // Pill colour + status text flip when stateFlow emits the first parsed HID frame.
        binding.btnToggleService.text = getString(R.string.btn_stop)
        log("Service started")
    }

    private fun stopControllerService() {
        val intent =
            Intent(this, ControllerService::class.java).apply {
                action = ControllerService.ACTION_STOP
            }
        startService(intent)
        serviceRunning = false
        hasSeenActiveMode = false
        updateStatus()
        binding.btnToggleService.text = getString(R.string.btn_start)
        log("Service stopped")
    }

    private fun handleStatusAction() {
        if (!isShizukuInstalled()) {
            openShizukuInstall()
            return
        }
        val running = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        when {
            !running -> startShizukuService()

            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                Shizuku.requestPermission(shizukuRequestCode)

            else -> restartService()
        }
    }

    private fun startShizukuService() {
        if (!ShizukuStarterService.isEnabledInSettings(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.shizuku_starter_prompt_title)
                .setMessage(R.string.shizuku_starter_prompt_message)
                .setPositiveButton(R.string.shizuku_starter_prompt_open_settings) { _, _ ->
                    openAccessibilitySettings()
                }
                .setNeutralButton(R.string.shizuku_starter_prompt_open_shizuku) { _, _ ->
                    openShizukuApp()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        Prefs.setUserStoppedService(this, false)
        ShizukuStarterService.arm()
        Toast.makeText(this, R.string.shizuku_starter_armed, Toast.LENGTH_SHORT).show()
        openShizukuApp()
    }

    private fun openAccessibilitySettings() {
        val direct = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val resolvedPackage = direct.resolveActivity(packageManager)?.packageName
        val target =
            if (resolvedPackage != null && resolvedPackage != tvSettingsStubPackage) {
                direct
            } else {
                Intent(Settings.ACTION_SETTINGS)
            }
        try {
            startActivity(target)
            if (target !== direct) {
                Toast.makeText(this, R.string.shizuku_starter_settings_path, Toast.LENGTH_LONG)
                    .show()
            }
        } catch (_: Throwable) {
            Toast.makeText(this, R.string.shizuku_starter_settings_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun openShizukuApp() {
        val intent = packageManager.getLaunchIntentForPackage(shizukuPackage)
        if (intent == null) {
            Toast.makeText(this, R.string.main_shizuku_open_failed, Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(this, R.string.main_shizuku_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun isShizukuInstalled(): Boolean = try {
        packageManager.getPackageInfo(shizukuPackage, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Throwable) {
        true
    }

    private fun openShizukuInstall() {
        val targets =
            listOf(
                "market://details?id=$shizukuPackage",
                "https://play.google.com/store/apps/details?id=$shizukuPackage",
                "https://github.com/RikkaApps/Shizuku/releases/latest"
            )
        for (target in targets) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                return
            } catch (_: Throwable) {
            }
        }
        Toast.makeText(this, R.string.main_shizuku_store_failed, Toast.LENGTH_LONG).show()
    }

    private fun hasShizukuAccess(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    private fun updateShizukuStatus(ok: Boolean) {
        val transport = Prefs.getTransport(this).displayName
        val prerequisite =
            when {
                ok -> "ready"
                !isShizukuInstalled() -> "not installed — tap to install"
                !shizukuRunning() -> "not running — tap to start"
                else -> "permission required — tap to grant"
            }
        binding.tvShizukuStatus.text = "Shizuku: $prerequisite  •  $transport"
    }

    private fun shizukuRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    /**
     * Drives both the bottom-of-card "Controller: ..." label AND the top status pill colour.
     * The pill flips to a green tint as soon as HID frames are actually flowing, which is
     * a much more honest signal than "the user pressed Start".
     */

    /**
     * The status card. Driven by [ControllerService.linkStatusFlow], not by "is the service
     * running" — a green card with a silent controller is indistinguishable from a broken one, and
     * that is exactly what the old version showed. Only LINKED gets the connected styling.
     */
    private fun updateStatus() {
        val serviceState = ControllerService.serviceStateFlow.value
        val status = serviceState.link
        binding.tvControllerStatus.text =
            when (status.state) {
                ControllerService.LinkState.LINKED ->
                    if (serviceState.reportRateHz > 0) {
                        "${getString(R.string.status_ready)} · ${serviceState.reportRateHz} Hz"
                    } else {
                        getString(R.string.status_ready)
                    }

                ControllerService.LinkState.STALE ->
                    getString(R.string.status_link_lost, status.detail)

                ControllerService.LinkState.DISCONNECTED ->
                    if (status.detail.isEmpty()) {
                        getString(R.string.status_disconnected)
                    } else {
                        getString(R.string.status_disconnected_reason, status.detail)
                    }
            }

        val connected = status.state == ControllerService.LinkState.LINKED
        val containerColor =
            if (connected) {
                ContextCompat.getColor(this, R.color.status_connected_container)
            } else {
                ContextCompat.getColor(this, R.color.status_idle_container)
            }
        val textColor =
            if (connected) {
                ContextCompat.getColor(this, R.color.status_connected_on_container)
            } else {
                ContextCompat.getColor(this, R.color.status_idle_on_container)
            }

        binding.statusPillCard?.setCardBackgroundColor(containerColor)
        binding.tvShizukuStatus.setTextColor(textColor)
    }

    /**
     * Reconnect from the status card. Stop then start, which covers every failure mode: no
     * controller paired, controller asleep, or a handshake that stalled and never recovered.
     */
    private fun restartService() {
        log("Reconnecting…")
        Toast.makeText(this, R.string.reconnecting, Toast.LENGTH_SHORT).show()
        if (serviceRunning && Prefs.getTransport(this) == Transport.BLUETOOTH) {
            startService(
                Intent(this, ControllerService::class.java).apply {
                    action = ControllerService.ACTION_RECONNECT
                }
            )
            return
        }
        if (serviceRunning) {
            startService(
                Intent(this, ControllerService::class.java).apply {
                    action = ControllerService.ACTION_RESTART
                }
            )
            serviceRunning = false
        }
        lifecycleScope.launch {
            delay(SERVICE_RESTART_DELAY_MS)
            checkPermissionsAndStart()
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        val current = binding.tvLog.text.toString()
        val lines = current.lines().takeLast(9)
        binding.tvLog.text = (lines + msg).joinToString("\n")
    }

    override fun onResume() {
        super.onResume()
        updateShizukuStatus(hasShizukuAccess())

        // Sync Control Mode toggle — profile may have changed from the notification while paused
        syncControlModeToggle(Prefs.getProfile(this))
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }
}
