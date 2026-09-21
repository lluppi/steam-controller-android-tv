package com.steamcontroller.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.steamcontroller.android.MainActivity
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.R
import com.steamcontroller.android.Transport
import com.steamcontroller.android.bt.BluetoothHidManager
import rikka.shizuku.Shizuku

/** Starts the controller service at boot or when the configured Bluetooth controller connects. */
class AutoStartReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AutoStartReceiver"
        private const val ADVISORY_CHANNEL_ID = "steam_controller_autostart"
        private const val ADVISORY_NOTIFICATION_ID = 2
        private const val ADVISORY_COOLDOWN_MS = 60_000L

        @Volatile private var lastAdvisoryAt = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> onBoot(context)
            BluetoothDevice.ACTION_ACL_CONNECTED -> onControllerConnected(context, intent)
        }
    }

    private fun onBoot(context: Context) {
        Prefs.setUserStoppedService(context, false)
        if (Prefs.getStartOnBoot(context)) start(context, "boot")
    }

    private fun onControllerConnected(context: Context, intent: Intent) {
        if (!Prefs.getStartOnControllerConnect(context) ||
            Prefs.getTransport(context) != Transport.BLUETOOTH
        ) {
            return
        }
        val device =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
        if (!isConfiguredController(context, device)) return
        start(context, "controller connect")
    }

    private fun isConfiguredController(context: Context, device: BluetoothDevice): Boolean {
        val savedAddress = Prefs.getBluetoothAddress(context)
        val savedName = Prefs.getBluetoothName(context)
        return try {
            device.address == savedAddress ||
                (savedName != null && device.name == savedName) ||
                (
                    savedName == null &&
                        device.name?.let { name ->
                            BluetoothHidManager.NAME_HINTS.any {
                                name.contains(it, ignoreCase = true)
                            }
                        } == true
                    )
        } catch (_: SecurityException) {
            false
        }
    }

    private fun start(context: Context, reason: String) {
        if (Prefs.getUserStoppedService(context)) {
            Log.i(TAG, "Auto-start ($reason) skipped: deliberately stopped")
            return
        }
        if (ControllerService.isRunning) return
        if (!isShizukuReady()) {
            Log.i(TAG, "Auto-start ($reason) skipped: Shizuku is not ready")
            postShizukuAdvisory(context)
            return
        }
        try {
            context.startForegroundService(Intent(context, ControllerService::class.java))
            Log.i(TAG, "Auto-start ($reason) requested")
        } catch (t: Throwable) {
            Log.w(TAG, "Auto-start ($reason) failed: ${t.message}")
        }
    }

    private fun isShizukuReady(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    private fun postShizukuAdvisory(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAdvisoryAt < ADVISORY_COOLDOWN_MS) return
        lastAdvisoryAt = now

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                ADVISORY_CHANNEL_ID,
                context.getString(R.string.autostart_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
        val openApp =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        val text = context.getString(R.string.autostart_shizuku_advisory_text)
        val notification =
            Notification.Builder(context, ADVISORY_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.autostart_shizuku_advisory_title))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .build()
        try {
            manager.notify(ADVISORY_NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not post auto-start advisory: ${t.message}")
        }
    }
}
