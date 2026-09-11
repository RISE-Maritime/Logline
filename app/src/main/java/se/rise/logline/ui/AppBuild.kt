package se.rise.logline.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What this install actually is.
 *
 * `versionName` comes from `version.properties` and is bumped by hand; `versionCode` is derived from
 * the commit count, so the number in brackets on the About row identifies the commit this APK was
 * built from. That is what makes this screen worth having: an APK gets passed around this fleet, and
 * "is this the same build?" has to be answerable from the phone rather than from the machine that
 * built it. Until the code was derived it read `1` for every build ever made, which answered nothing.
 *
 * Read through `PackageManager` rather than `BuildConfig`, which is off in this project (AGP 8+
 * defaults it off and nothing here has needed it). That is not merely the cheaper option: the install
 * and update instants are facts about *this* install and exist nowhere in a generated class.
 */
data class AppBuild(
    val versionName: String,
    val versionCode: Long,
    /**
     * A debug build, from `FLAG_DEBUGGABLE`.
     *
     * Deliberately not inferred from the signature: a release here is unsigned whenever the keystore
     * is absent, which is the ordinary case for anyone without the key, so an unsigned APK says
     * nothing about which build type it is.
     */
    val debuggable: Boolean,
    val applicationId: String,
    val installedAtMillis: Long,
    val updatedAtMillis: Long,
)

/**
 * Reads the package's own record of itself.
 *
 * The one `Context`-touching part of this screen, so it is called from `App()` and the result handed
 * down — screens here take data and lambdas.
 */
fun appBuildOf(context: Context): AppBuild {
    val packageName = context.packageName
    val manager = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        manager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        manager.getPackageInfo(packageName, 0)
    }
    return AppBuild(
        versionName = info.versionName ?: "unknown",
        versionCode = info.longVersionCode,
        debuggable = (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        applicationId = packageName,
        installedAtMillis = info.firstInstallTime,
        updatedAtMillis = info.lastUpdateTime,
    )
}

/** The one line the Setup row carries: `1.0 (1) · debug build`. */
internal fun buildSummary(build: AppBuild): String =
    "${build.versionName} (${build.versionCode}) · ${buildTypeOf(build)} build"

internal fun buildTypeOf(build: AppBuild): String = if (build.debuggable) "debug" else "release"

/**
 * A date somebody can compare against when a build was made.
 *
 * `Locale.ROOT` for the same reason every other figure in this app goes through it: the default
 * locale on a Swedish phone renders differently, and two phones must not disagree about what the same
 * instant is called. `formatClock` in [Format.kt] is time-of-day only and is not this.
 */
private val BUILD_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withLocale(Locale.ROOT)

internal fun formatBuildDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(BUILD_DATE_FORMAT)
