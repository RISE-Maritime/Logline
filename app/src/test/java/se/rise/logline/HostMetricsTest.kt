package se.rise.logline

import se.rise.logline.sensors.HostMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The host's own health, and the one rule that matters: **absent is null, never zero.**
 *
 * proto3 cannot tell an absent float from `0.0`, so a device with swap switched off publishing `0.0`
 * would be read as "swap is configured and completely free" — a plausible wrong answer, which is the
 * failure this whole family of guards exists to prevent. The same argument the battery and radio
 * samples make, applied to two readings that have more ways of being absent than most.
 */
class HostMetricsTest {

    /** `/proc/meminfo` as a Pixel 6 actually writes it, read under the app's own uid. */
    private val pixel6 = """
        MemTotal:        7787844 kB
        MemFree:          201324 kB
        MemAvailable:    3602508 kB
        Buffers:           12345 kB
        SwapTotal:       3145724 kB
        SwapFree:        1018308 kB
    """.trimIndent()

    @Test
    fun `swap in use is what is not free, as a percentage`() {
        // (3 145 724 − 1 018 308) / 3 145 724 = 67.63%
        assertEquals(67.63f, HostMetrics.swapUsedPct(pixel6)!!, 0.01f)
    }

    /**
     * Three ways to have no answer, and all three are null.
     *
     * The last is the one worth the test: a device with swap switched off has `SwapTotal: 0`, which is
     * a configuration rather than a measurement. Reporting it as 0% used would say swap exists and is
     * entirely free.
     */
    @Test
    fun `no swap figure is absent rather than zero`() {
        assertNull("an unreadable file", HostMetrics.swapUsedPct(""))
        assertNull("a missing field", HostMetrics.swapUsedPct("MemTotal: 7787844 kB\nSwapTotal: 100 kB"))
        assertNull("swap switched off", HostMetrics.swapUsedPct("SwapTotal: 0 kB\nSwapFree: 0 kB"))
    }

    /**
     * Keys match to the colon, not by prefix.
     *
     * `SwapTotal` and `SwapFree` are neighbours, and so are `MemTotal` and `MemAvailable` — a
     * `startsWith` without the colon would take the first row that happened to begin the same way, and
     * a kernel adding `SwapTotalHigh` beside them would silently change what this reports.
     */
    @Test
    fun `a longer key beginning the same way is not mistaken for the one asked for`() {
        val awkward = "SwapTotalHigh:   999 kB\nSwapTotal:      1000 kB\nSwapFree:        250 kB"

        assertEquals(75.0f, HostMetrics.swapUsedPct(awkward)!!, 0.01f)
    }

    /**
     * Memory used is total minus **available**, and available is not free.
     *
     * Free memory on Linux sits near zero at all times because the kernel spends it on cache — the
     * Pixel 6 sample above reports 201 MB free against 3.6 GB available. Using `MemFree` would have a
     * perfectly healthy phone reading 97% used for its entire life.
     */
    @Test
    fun `memory used is measured against what is available, not what is free`() {
        // (7 787 844 − 3 602 508) / 7 787 844 = 53.74%, in bytes.
        val total = 7_787_844L * 1024
        val available = 3_602_508L * 1024

        assertEquals(53.74f, HostMetrics.memoryUsedPct(total, available)!!, 0.01f)
        // What `MemFree` would have given instead, stated so the difference is on the record.
        assertEquals(97.4f, HostMetrics.memoryUsedPct(total, 201_324L * 1024)!!, 0.1f)
    }

    @Test
    fun `no total means no percentage`() {
        assertNull(HostMetrics.memoryUsedPct(0L, 0L))
        assertNull(HostMetrics.memoryUsedPct(-1L, 100L))
    }

    /** Available above total is nonsense from the platform, not a negative usage. */
    @Test
    fun `usage never goes below zero`() {
        assertEquals(0f, HostMetrics.memoryUsedPct(1000L, 2000L)!!, 0.001f)
    }
}
