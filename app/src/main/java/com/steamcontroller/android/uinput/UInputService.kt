package com.steamcontroller.android.uinput

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

// Bound by Shizuku.bindUserService() — this code runs in a separate process with the
// shell UID (2000), which is what gives access to the virtual-output device nodes.
//
// IMPORTANT: must have a no-arg constructor. Shizuku v13+ also tries the Context
// constructor first; either is acceptable.
class UInputService : IUInputService.Stub {
    companion object {
        private const val TAG = "UInputService"

        /** How often the liveness watchdog checks for client silence. */
        private const val WATCHDOG_TICK_MS = 5_000L

        /**
         * Silence longer than this means the app that owns us is gone. Generous on purpose: the
         * app polls at 50 Hz while its service runs, so this only trips on a killed client.
         */
        private const val CLIENT_IDLE_TIMEOUT_MS = 60_000L
    }

    @Suppress("unused")
    constructor() : super() {
        Log.i(TAG, "UInputService instantiated (no-arg)")
    }

    @Suppress("unused")
    constructor(context: Context?) : super() {
        Log.i(TAG, "UInputService instantiated (Context=$context)")
    }

    // ── Client liveness watchdog ───────────────────────────────────────────────
    //
    // This process runs as the shell uid and holds the uhid devices. If the app is killed
    // abruptly (an adb install does exactly this) nothing calls destroy(), so the process would
    // survive as an orphan with its devices still registered — which is how duplicate gamepads
    // accumulate and how RetroArch ends up bound to a pad whose owner no longer exists.
    //
    // The app polls us continuously while it is alive (the rumble poll runs at 50 Hz), so silence
    // for a while means the client is gone and we should exit. The app recovers: it notices the
    // binder death and re-binds on the next start.
    private val lastCallAt = AtomicLong(System.currentTimeMillis())

    // Starts after lastCallAt is initialised (property and init order is declaration order), and
    // covers both constructors: Shizuku may use either.
    init {
        startLivenessWatchdog()
    }

    private fun startLivenessWatchdog() {
        val thread =
            Thread(
                {
                    while (true) {
                        try {
                            Thread.sleep(WATCHDOG_TICK_MS)
                            val idle = System.currentTimeMillis() - lastCallAt.get()
                            if (idle > CLIENT_IDLE_TIMEOUT_MS) {
                                Log.i(
                                    TAG,
                                    "no client calls for ${idle}ms — exiting so the devices are " +
                                        "released instead of being orphaned"
                                )
                                try {
                                    UInputNative.destroy()
                                } catch (_: Throwable) {
                                }
                                System.exit(0)
                            }
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                    }
                },
                "uinput-liveness"
            )
        thread.isDaemon = true
        thread.start()
    }

    override fun selectBackend(preferred: Int): Int = try {
        UInputNative.selectBackend(preferred)
    } catch (t: Throwable) {
        Log.e(TAG, "selectBackend failed: ${t.message}")
        UInputNative.Backend.NONE
    }

    override fun getBackend(): Int = try {
        UInputNative.currentBackend()
    } catch (t: Throwable) {
        Log.e(TAG, "getBackend failed: ${t.message}")
        UInputNative.Backend.NONE
    }

    override fun getBackendDetail(): String = try {
        UInputNative.backendDetail()
    } catch (t: Throwable) {
        Log.e(TAG, "getBackendDetail failed: ${t.message}")
        "unavailable: ${t.message}"
    }

    override fun supportsRumble(): Boolean = try {
        UInputNative.supportsRumble()
    } catch (t: Throwable) {
        Log.e(TAG, "supportsRumble failed: ${t.message}")
        false
    }

    override fun createGamepad(profileId: Int): Boolean = try {
        UInputNative.createDevice(profileId)
    } catch (t: Throwable) {
        Log.e(TAG, "createDevice failed: ${t.message}")
        false
    }

    override fun sendFrame(
        buttons: Int,
        leftStickX: Int,
        leftStickY: Int,
        rightStickX: Int,
        rightStickY: Int,
        leftTrigger: Int,
        rightTrigger: Int,
        dpadX: Int,
        dpadY: Int
    ) {
        lastCallAt.set(System.currentTimeMillis())
        try {
            UInputNative.sendFrame(
                buttons,
                leftStickX,
                leftStickY,
                rightStickX,
                rightStickY,
                leftTrigger,
                rightTrigger,
                dpadX,
                dpadY
            )
        } catch (t: Throwable) {
            Log.e(TAG, "sendFrame failed: ${t.message}")
        }
    }

    override fun sendMouseFrame(relX: Int, relY: Int, scrollY: Int, keys: Int) {
        lastCallAt.set(System.currentTimeMillis())
        try {
            UInputNative.sendMouseFrame(relX, relY, scrollY, keys)
        } catch (t: Throwable) {
            Log.e(TAG, "sendMouseFrame failed: ${t.message}")
        }
    }

    override fun pollForceFeedback(): IntArray? {
        // Called at 50 Hz by the app while it is alive — the main proof of life.
        lastCallAt.set(System.currentTimeMillis())
        return try {
            UInputNative.pollFFEvent()
        } catch (t: Throwable) {
            Log.e(TAG, "pollFFEvent failed: ${t.message}")
            null
        }
    }

    override fun runShellCommand(cmd: Array<String>?): Int {
        if (cmd.isNullOrEmpty()) return -1
        return try {
            val proc =
                ProcessBuilder(cmd.toList())
                    .redirectErrorStream(true)
                    .start()
            val exit = proc.waitFor()
            Log.i(TAG, "runShellCommand ${cmd.joinToString(" ")} → exit=$exit")
            exit
        } catch (t: Throwable) {
            Log.e(TAG, "runShellCommand failed: ${t.message}")
            -1
        }
    }

    override fun runShellCommandForOutput(cmd: Array<String>?): String? {
        if (cmd.isNullOrEmpty()) return null
        return try {
            val proc =
                ProcessBuilder(cmd.toList())
                    .redirectErrorStream(true)
                    .start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            output.trim()
        } catch (t: Throwable) {
            Log.e(TAG, "runShellCommandForOutput failed: ${t.message}")
            null
        }
    }

    override fun destroy() {
        try {
            UInputNative.destroy()
        } catch (t: Throwable) {
            Log.e(TAG, "destroy native failed: ${t.message}")
        }
        // Shizuku contract: destroy() must terminate the process
        System.exit(0)
    }
}
