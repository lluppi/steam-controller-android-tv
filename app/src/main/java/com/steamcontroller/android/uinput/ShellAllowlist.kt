package com.steamcontroller.android.uinput

/**
 * The only commands [UInputService] will run as the shell uid. The service holds shell
 * privileges, so it exposes exactly what the app uses instead of a general escape hatch:
 *
 * - `mkdir -p` / `screencap -p` inside the screenshots directory (SystemActions)
 * - `settings get|put|delete secure show_ime_with_hard_keyboard` (UInputGamepad)
 *
 * Adding a system action means adding its exact shape here.
 */
internal object ShellAllowlist {
    const val TIMEOUT_MS = 15_000L
    const val MAX_OUTPUT_BYTES = 64 * 1024

    const val SCREENSHOT_DIR = "/sdcard/Pictures/Screenshots"
    private const val IME_SETTING = "show_ime_with_hard_keyboard"

    private val SCREENSHOT_FILE = Regex("""[A-Za-z0-9_\-]+\.png""")
    private val INT_VALUE = Regex("""-?\d{1,10}""")

    fun isAllowed(cmd: Array<String>): Boolean {
        val args = cmd.toList()
        return when {
            args == listOf("mkdir", "-p", SCREENSHOT_DIR) -> {
                true
            }

            args.size == 3 && args[0] == "screencap" && args[1] == "-p" -> {
                isScreenshotPath(args[2])
            }

            args.size >= 4 && args[0] == "settings" && args[2] == "secure" && args[3] == IME_SETTING -> {
                when (args[1]) {
                    "get", "delete" -> args.size == 4
                    "put" -> args.size == 5 && INT_VALUE.matches(args[4])
                    else -> false
                }
            }

            else -> {
                false
            }
        }
    }

    private fun isScreenshotPath(path: String): Boolean {
        val prefix = "$SCREENSHOT_DIR/"
        return path.startsWith(prefix) && SCREENSHOT_FILE.matches(path.removePrefix(prefix))
    }
}
