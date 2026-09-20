package com.steamcontroller.android.uinput

// Thin JNI binding. Loaded by the Shizuku user service process (UID shell).
//
// The class name is historical: this fronts every output backend — `uinput` when the
// shell UID may open /dev/uinput, `uhid` otherwise. Which one is in use is decided by
// selectBackend(). See cpp/output_backend.h for the contract.
object UInputNative {
    init {
        System.loadLibrary("uinput_jni")
    }

    /** Chosen backend — must match BackendId in cpp/output_backend.h. */
    object Backend {
        const val NONE = 0
        const val UINPUT = 1
        const val UHID = 2
    }

    /** Requested backend for [selectBackend] — must match BackendPref in cpp/output_backend.h. */
    object Pref {
        const val AUTO = 0
        const val UINPUT = 1
        const val UHID = 2
    }

    /** Display name for a [Backend] id — used in logs and the UI. */
    fun backendName(id: Int): String =
        when (id) {
            Backend.UINPUT -> "uinput"
            Backend.UHID -> "uhid"
            else -> "none"
        }

    /** Probe the backends and adopt one. Returns the chosen [Backend] id. */
    external fun selectBackend(preferred: Int): Int

    external fun currentBackend(): Int

    /** Probe result for every backend, e.g. "/dev/uinput: Permission denied, /dev/uhid: ok". */
    external fun backendDetail(): String

    /** Whether games can receive rumble through the selected backend. */
    external fun supportsRumble(): Boolean

    external fun createDevice(profileId: Int): Boolean

    /** Returns [strongMagnitude, weakMagnitude] in 0..65535, or null if no FF event is pending. */
    external fun pollFFEvent(): IntArray?

    external fun sendFrame(
        buttons: Int,
        leftX: Int,
        leftY: Int,
        rightX: Int,
        rightY: Int,
        leftTrigger: Int,
        rightTrigger: Int,
        dpadX: Int,
        dpadY: Int,
    )

    /** Desktop mode frame. `keys` is a MouseTarget bitmask. */
    external fun sendMouseFrame(
        relX: Int,
        relY: Int,
        scrollY: Int,
        keys: Int,
    )

    external fun destroy()
}
