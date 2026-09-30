package com.klin.read.util

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * Runtime capability checks used to tune the app for watches.
 *
 * The UI does not change; what changes is how much animation and how much text
 * is kept in memory. On a watch with roughly 256 MB available the difference is
 * the gap between usable and unusable.
 */
object DeviceProfile {

    /**
     * True when the device cannot afford the full animation set.
     *
     * Decided by available memory and by whether this is a watch, rather than by
     * API level: a low-end phone benefits from the same reduction, and a modern
     * watch does not need it.
     */
    fun isLowMemory(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        val lowRam = am?.isLowRamDevice == true
        // Below ~1 GB installed, animations and large caches are a liability.
        val smallTotal = info.totalMem in 1..(1_100L * 1024 * 1024)
        return lowRam || smallTotal
    }

    /** True when the app is running on a watch-sized screen. */
    fun isWatch(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode and
            Configuration.UI_MODE_TYPE_MASK
        if (uiMode == Configuration.UI_MODE_TYPE_WATCH) return true

        // Some watch ROMs report a normal uiMode, so fall back to screen size.
        val metrics = context.resources.displayMetrics
        val smallestDp = minOf(
            metrics.widthPixels / metrics.density,
            metrics.heightPixels / metrics.density
        )
        return smallestDp < 320
    }

    /** True when the display is a round watch face. */
    fun isRound(context: Context): Boolean =
        context.resources.configuration.isScreenRound

    /**
     * How many paragraphs to keep rendered at once.
     *
     * Paged modes slice the chapter into screens; a smaller number means less
     * laid-out text per frame at the cost of more page turns.
     */
    fun paragraphsPerPage(context: Context): Int =
        if (isWatch(context)) 8 else 16

    /**
     * Whether to skip cross-fades and spring animations.
     *
     * Animations are the first thing to cut on a watch because they cost frames
     * without adding reading value.
     */
    fun reduceAnimations(context: Context): Boolean =
        isLowMemory(context) || isWatch(context)

    /** Human-readable summary, used in the settings screen's about section. */
    fun describe(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        val totalMb = info.totalMem / (1024 * 1024)
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        val shape = if (isRound(context)) "圆形" else "方形"
        return "内存 ${totalMb}MB · $abi · $shape"
    }
}
