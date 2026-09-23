package com.thiepn.scan.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlin.math.min

data class HighSpeedProcessingBudget(
    val canProcess: Boolean,
    val maxPagesPerChunk: Int,
    val retryDelayMillis: Long,
    val pausedReason: String? = null
)

class HighSpeedProcessingPolicy(
    private val context: Context
) {
    fun currentBudget(mode: ScanMode): HighSpeedProcessingBudget {
        val thermalStatus = thermalStatus()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        ) {
            return HighSpeedProcessingBudget(
                canProcess = false,
                maxPagesPerChunk = 1,
                retryDelayMillis = 120_000L,
                pausedReason = "Processing paused because the device is hot."
            )
        }

        val battery = batteryState()
        if (battery.level in 0..10 && !battery.charging) {
            return HighSpeedProcessingBudget(
                canProcess = false,
                maxPagesPerChunk = 1,
                retryDelayMillis = 300_000L,
                pausedReason = "Processing paused to protect a low battery."
            )
        }

        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryClass = activityManager.memoryClass
        val memoryChunk = when {
            memoryClass < 192 -> 2
            memoryClass < 256 -> 3
            memoryClass < 384 -> 5
            memoryClass < 512 -> 7
            else -> 12
        }

        val thermalChunk = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> memoryChunk
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE ->
                min(memoryChunk, 3)
            thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT ->
                min(memoryChunk, 6)
            else -> memoryChunk
        }

        val batteryChunk = if (
            battery.level in 11..20 &&
            !battery.charging
        ) {
            min(thermalChunk, 3)
        } else {
            thermalChunk
        }

        val modeChunk = when (mode) {
            ScanMode.BOOK -> min(batteryChunk, 4)
            ScanMode.PHOTO -> min(batteryChunk, 8)
            else -> batteryChunk
        }

        return HighSpeedProcessingBudget(
            canProcess = true,
            maxPagesPerChunk = modeChunk.coerceAtLeast(1),
            retryDelayMillis = 0L
        )
    }

    private fun thermalStatus(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.currentThermalStatus
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }

    private fun batteryState(): BatteryState {
        val manager =
            context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .coerceIn(-1, 100)

        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val status = intent?.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        ) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val charging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return BatteryState(level, charging)
    }

    private data class BatteryState(
        val level: Int,
        val charging: Boolean
    )
}
