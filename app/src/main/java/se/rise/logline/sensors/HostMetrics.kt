package se.rise.logline.sensors

/**
 * What the phone will say about itself as a *host* — the machine keelson runs on.
 *
 * Upstream's `subjects.yaml` scopes this family deliberately: "only what a vessel-level consumer needs
 * to make a decision — is the box healthy, how long can it keep recording", with per-core load and
 * interface counters left to a node-exporter running beside keelson rather than put on the bus. So
 * there is very little here, and that is the design.
 *
 * **Two of the family are unobtainable on Android and it is worth knowing why before trying.**
 * `cpu_load_pct` and `cpu_temperature_celsius` have no route from a normal app: `/proc/stat`,
 * `/proc/loadavg` and `/sys/class/thermal` are all `Permission denied` under the app's uid — measured
 * on a Pixel 6, not assumed — and the sanctioned APIs (`HardwarePropertiesManager.getCpuUsages` and
 * `getDeviceTemperatures`) are gated behind `DEVICE_POWER`, a signature permission. `/proc/meminfo` is
 * the one file in that set that *is* readable, which is why swap comes from here and the CPU subjects
 * do not exist.
 */
object HostMetrics {

    /**
     * Swap in use, as a percentage, from the text of `/proc/meminfo`.
     *
     * **Null rather than zero in all three of the ways this can fail**, which is the rule the battery
     * and radio samples follow for the same reason: proto3 cannot tell an absent float from `0.0`, and
     * "no swap configured" published as `0.0` reads as "swap is configured and completely free" — a
     * plausible wrong answer rather than a visible absence.
     *
     * The three: the file unreadable (a future Android tightening `/proc` further), a field missing,
     * and `SwapTotal` of zero, which is a device with swap switched off rather than a measurement of
     * one. Android normally has zram, so a real phone answers; an emulator may not.
     */
    fun swapUsedPct(meminfo: String): Float? {
        val total = meminfoKilobytes(meminfo, "SwapTotal") ?: return null
        val free = meminfoKilobytes(meminfo, "SwapFree") ?: return null
        if (total <= 0L) return null
        return ((total - free).toDouble() / total * 100.0).toFloat()
    }

    /**
     * Memory in use, as a percentage, from `ActivityManager.MemoryInfo`'s two figures.
     *
     * **`availMem`, not `freeMem`, and the difference is the whole number.** Free memory on Linux is
     * near zero at all times because the kernel spends it on cache; available memory is what could be
     * given to a process without swapping, which is what "is this box in trouble" actually asks. It is
     * the same quantity `/proc/meminfo` calls `MemAvailable`, and the reason memory comes from the
     * platform API while swap comes from the file: `ActivityManager` is a supported interface that
     * cannot be locked down under the app, where `/proc` already has been for the CPU.
     */
    fun memoryUsedPct(totalBytes: Long, availableBytes: Long): Float? {
        if (totalBytes <= 0L) return null
        val used = (totalBytes - availableBytes).coerceAtLeast(0L)
        return (used.toDouble() / totalBytes * 100.0).toFloat()
    }

    /**
     * One `/proc/meminfo` row, in kilobytes — the unit the file states and this only ever compares
     * against itself, so nothing converts it.
     *
     * Matches the key to the colon rather than by prefix: `SwapTotal` and `SwapFree` are distinct rows,
     * but so are `MemTotal` and `MemAvailable`, and a `startsWith` would have `SwapTotal` match
     * `SwapTotalSomething` if a kernel ever added one.
     */
    private fun meminfoKilobytes(meminfo: String, key: String): Long? = meminfo.lineSequence()
        .firstOrNull { it.startsWith("$key:") }
        ?.substringAfter(':')
        ?.trim()
        ?.removeSuffix("kB")
        ?.trim()
        ?.toLongOrNull()
}
