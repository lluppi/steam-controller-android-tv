package com.steamcontroller.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.steamcontroller.android.Prefs

/**
 * Starts the controller service when the device boots.
 *
 * Only useful in combination with the user knowing how to bring Shizuku back: Android 9 predates
 * wireless-debugging pairing, so Shizuku is dead after a reboot and the service cannot create a
 * virtual device until it is restarted from adb. Rather than silently doing nothing, the status
 * card reports exactly that (see ControllerService.refreshLinkStatus).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.getStartOnBoot(context)) {
            Log.i(TAG, "start on boot disabled")
            return
        }
        Log.i(TAG, "boot completed — starting controller service")
        try {
            context.startForegroundService(Intent(context, ControllerService::class.java))
        } catch (t: Throwable) {
            // Android is allowed to refuse a foreground service start here; the user can always
            // start it from the app, and the notification will say what happened.
            Log.w(TAG, "Could not start service on boot: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
