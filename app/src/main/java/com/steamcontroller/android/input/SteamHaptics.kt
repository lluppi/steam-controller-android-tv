package com.steamcontroller.android.input

/** Steam haptic-pulse command shared by Bluetooth and USB transports. */
object SteamHaptics {
    fun payload(padId: Int, magnitude: Int): ByteArray {
        val mag = magnitude.coerceIn(0, 0xFFFF)
        if (mag == 0) {
            return byteArrayOf(0x8F.toByte(), padId.toByte(), 0, 0, 0, 0, 0, 0)
        }

        val totalPeriodUs = 6250
        val dutyPct = 25 + (mag * 72 / 0xFFFF)
        val highPeriod = (totalPeriodUs * dutyPct / 100).coerceIn(1, totalPeriodUs - 1)
        val lowPeriod = totalPeriodUs - highPeriod
        val repeat = 0xFFFF
        return byteArrayOf(
            0x8F.toByte(),
            padId.toByte(),
            (highPeriod and 0xFF).toByte(),
            (highPeriod shr 8 and 0xFF).toByte(),
            (lowPeriod and 0xFF).toByte(),
            (lowPeriod shr 8 and 0xFF).toByte(),
            (repeat and 0xFF).toByte(),
            (repeat shr 8 and 0xFF).toByte()
        )
    }
}
