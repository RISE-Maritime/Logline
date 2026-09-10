package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Test
import se.rise.logline.ui.AppBuild
import se.rise.logline.ui.buildSummary
import se.rise.logline.ui.buildTypeOf
import se.rise.logline.ui.formatBuildDate
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * What the About screen says about this install.
 *
 * `appBuildOf` needs a `Context` and so cannot be reached from a JVM test — but everything the screen
 * actually prints is pure, which is why the reading and the formatting are separated in the first
 * place.
 */
class AppBuildTest {

    private fun build(
        versionName: String = "1.0",
        versionCode: Long = 1,
        debuggable: Boolean = true,
        installedAtMillis: Long = 0,
        updatedAtMillis: Long = 0,
    ) = AppBuild(
        versionName = versionName,
        versionCode = versionCode,
        debuggable = debuggable,
        applicationId = "se.rise.logline",
        installedAtMillis = installedAtMillis,
        updatedAtMillis = updatedAtMillis,
    )

    /**
     * The Setup row's whole job: telling two APKs apart without a tap.
     *
     * The build type is in it because a debug APK is what gets passed around this fleet, and a phone
     * holding one should not have to be asked twice.
     */
    @Test
    fun `the summary names the version, the code and the build type`() {
        assertEquals("1.0 (1) · debug build", buildSummary(build()))
        assertEquals(
            "1.2 (7) · release build",
            buildSummary(build(versionName = "1.2", versionCode = 7, debuggable = false)),
        )
    }

    @Test
    fun `the build type is the debuggable flag and nothing else`() {
        assertEquals("debug", buildTypeOf(build(debuggable = true)))
        // A release here is unsigned whenever the keystore is absent, so the signature says nothing.
        assertEquals("release", buildTypeOf(build(debuggable = false)))
    }

    /**
     * A date two people can compare over a radio.
     *
     * `Locale.ROOT`, for the reason every other figure in this app goes through it: a Swedish phone's
     * default locale renders differently, and the same instant must not have two names.
     */
    @Test
    fun `the date is the same on a comma-decimal phone`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("sv-SE"))
            assertEquals(expected(NOON), formatBuildDate(NOON))
        } finally {
            Locale.setDefault(original)
        }
    }

    /**
     * The About screen draws `Updated` only when it differs from `Installed`, which rests on the two
     * formatting identically for one instant. A fresh install has both.
     */
    @Test
    fun `one instant formats one way`() {
        assertEquals(formatBuildDate(NOON), formatBuildDate(NOON))
    }

    /** The zone is the phone's, so the expectation has to be built the same way rather than pinned. */
    private fun expected(epochMillis: Long): String {
        val at: ZonedDateTime = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
        return "%04d-%02d-%02d %02d:%02d".format(
            Locale.ROOT,
            at.year,
            at.monthValue,
            at.dayOfMonth,
            at.hour,
            at.minute,
        )
    }

    private companion object {
        /** 2026-09-10T12:00:00Z, an ordinary instant with no daylight-saving edge in it. */
        const val NOON = 1_788_004_800_000L
    }
}
