package com.steamcontroller.android.shizuku

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.service.ControllerService
import rikka.shizuku.Shizuku

/**
 * Performs the two Shizuku wireless-start clicks that cannot receive D-pad focus on Android TV.
 * The service sees only Shizuku, stays inert until explicitly armed, expires after 45 seconds,
 * and disarms after selecting the discovered adb port.
 */
class ShizukuStarterService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        disarm()
        handler.removeCallbacksAndMessages(null)
        controllerStartHandler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        disarm()
        handler.removeCallbacksAndMessages(null)
        controllerStartHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isArmed() || event?.packageName?.toString() != SHIZUKU_PACKAGE) return
        scheduleAttempts()
    }

    private fun scheduleAttempts() {
        handler.removeCallbacksAndMessages(null)
        ATTEMPT_DELAYS_MS.forEach { delay ->
            handler.postDelayed({ if (isArmed()) attempt() }, delay)
        }
    }

    private fun attempt() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != SHIZUKU_PACKAGE) return
        try {
            if (!stage2Done) {
                findPortButton(root)?.let { node ->
                    if (node.packageName?.toString() != SHIZUKU_PACKAGE) return@let
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "selected wireless-debugging port ${node.text}: $clicked")
                    if (clicked) {
                        stage2Done = true
                        disarm()
                    }
                    return
                }
            }
            if (!stage1Done) {
                findStartButton(root)?.let { node ->
                    if (node.packageName?.toString() != SHIZUKU_PACKAGE) return@let
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "clicked Shizuku Start: $clicked")
                    if (clicked) {
                        stage1Done = true
                        awaitShizukuAndStartController()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "click attempt failed", t)
        }
    }

    private fun awaitShizukuAndStartController() {
        val deadline = SystemClock.elapsedRealtime() + ARM_WINDOW_MS
        controllerStartHandler.removeCallbacksAndMessages(null)
        fun check() {
            val ready =
                try {
                    Shizuku.pingBinder() &&
                        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                } catch (_: Throwable) {
                    false
                }
            if (ready) {
                if (!Prefs.getUserStoppedService(this) && !ControllerService.isRunning) {
                    try {
                        startForegroundService(Intent(this, ControllerService::class.java))
                    } catch (t: Throwable) {
                        Log.w(TAG, "could not start controller service", t)
                    }
                }
            } else if (SystemClock.elapsedRealtime() < deadline) {
                controllerStartHandler.postDelayed({ check() }, SHIZUKU_READY_POLL_MS)
            }
        }
        check()
    }

    private fun findStartButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val byId =
            root.findAccessibilityNodeInfosByViewId(ID_BUTTON1)
                ?.filter {
                    it.isClickable && it.isEnabled && it.isVisibleToUser &&
                        hasCardAncestor(it)
                }
                .orEmpty()
        return byId.firstOrNull(::hasSiblingButtons) ?: byId.firstOrNull() ?: findByLabel(root)
    }

    private fun findPortButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val buttons = ArrayList<AccessibilityNodeInfo>()
        DIALOG_BUTTON_IDS.forEach { id ->
            root.findAccessibilityNodeInfosByViewId(id)?.let(buttons::addAll)
        }
        return buttons.firstOrNull { node ->
            node.isClickable && node.isEnabled && node.isVisibleToUser &&
                !hasCardAncestor(node) &&
                node.text?.toString()?.trim()?.toIntOrNull()?.let { it in 1..65535 } == true
        }
    }

    private fun hasSiblingButtons(node: AccessibilityNodeInfo): Boolean {
        val parent = node.parent ?: return false
        var button2 = false
        var button3 = false
        for (index in 0 until parent.childCount) {
            when (parent.getChild(index)?.viewIdResourceName) {
                ID_BUTTON2 -> button2 = true
                ID_BUTTON3 -> button3 = true
            }
        }
        return button2 && button3
    }

    private fun hasCardAncestor(node: AccessibilityNodeInfo): Boolean {
        var current = node.parent
        repeat(MAX_ANCESTOR_HOPS) {
            current ?: return false
            if (current?.className?.toString()?.contains("CardView") == true) return true
            current = current?.parent
        }
        return false
    }

    private fun findByLabel(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByText(FALLBACK_LABEL)?.firstOrNull { node ->
            node.isClickable && node.isEnabled && node.isVisibleToUser &&
                node.className == "android.widget.Button" &&
                FALLBACK_LABEL.equals(node.text?.toString()?.trim(), ignoreCase = true) &&
                hasCardAncestor(node)
        }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        private const val TAG = "ShizukuStarter"
        private const val ID_BUTTON1 = "android:id/button1"
        private const val ID_BUTTON2 = "android:id/button2"
        private const val ID_BUTTON3 = "android:id/button3"
        private const val FALLBACK_LABEL = "Start"
        private const val MAX_ANCESTOR_HOPS = 12
        private const val ARM_WINDOW_MS = 45_000L
        private const val SHIZUKU_READY_POLL_MS = 500L
        private val DIALOG_BUTTON_IDS = arrayOf(ID_BUTTON3, ID_BUTTON2, ID_BUTTON1)
        private val ATTEMPT_DELAYS_MS = longArrayOf(300L, 900L, 1800L, 3000L, 5000L, 8000L)
        private val handler = Handler(Looper.getMainLooper())
        private val controllerStartHandler = Handler(Looper.getMainLooper())

        @Volatile private var instance: ShizukuStarterService? = null

        @Volatile private var armedUntil = 0L

        @Volatile private var stage1Done = false

        @Volatile private var stage2Done = false

        fun isConnected(): Boolean = instance != null

        fun isEnabledInSettings(context: Context): Boolean {
            val manager =
                context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                    ?: return false
            val expected = ComponentName(context, ShizukuStarterService::class.java)
            return manager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val service = info.resolveInfo?.serviceInfo ?: return@any false
                    ComponentName(service.packageName, service.name) == expected
                }
        }

        fun arm() {
            armedUntil = SystemClock.elapsedRealtime() + ARM_WINDOW_MS
            stage1Done = false
            stage2Done = false
            instance?.scheduleAttempts()
            Log.i(TAG, "armed for ${ARM_WINDOW_MS}ms")
        }

        fun disarm() {
            armedUntil = 0L
        }

        private fun isArmed(): Boolean = SystemClock.elapsedRealtime() < armedUntil
    }
}
