package se.rise.logline.keelson

/**
 * What the Zenoh Kotlin binding can and cannot be asked to do on Android.
 *
 * One place, because the same fault has now been diagnosed wrongly twice and each wrong diagnosis was
 * written down somewhere else.
 */
object ZenohBinding {

    /**
     * Whether a Zenoh **subscription** can be declared without killing the process. It cannot.
     *
     * **The mechanism.** `zenoh-flat-jni` builds callback arguments in native code with `FindClass`, on
     * one of Zenoh's own threads. JNI there resolves against the *system* class loader, which cannot
     * see app classes — the abort's own `DexPathList` names only `/system/lib64` and `/system_ext/lib64`,
     * with no APK in it. Four classes have been seen to trigger it, all of them in the jar and none of
     * them findable at the moment they are needed:
     *
     * ```
     * io.zenoh.jni.config.ZenohId        Zenoh.scout          — `Scout.kt` exists to avoid this
     * io.zenoh.jni.pubsub.EntityGlobalId a query reply        — finalize_pending_query
     * io.zenoh.jni.time.Timestamp        a subscriber sample  — NewStringUTF, any timestamped sample
     * io.zenoh.jni.sample.SourceInfo     a subscriber sample  — not yet seen, same path
     * ```
     *
     * **Why every subscription and not some.** `SampleCallback.run` takes an `io.zenoh.jni.time.Timestamp`
     * and an `io.zenoh.jni.sample.SourceInfo` — read off the 1.10.0 jar with `javap`, not inferred — so a
     * sample carrying either must construct an app class on Zenoh's thread. `SampleCallbackRaw` takes both
     * as well, so there is no raw path out. And a Zenoh **router timestamps every sample it forwards** by
     * default (`timestamping.enabled.router`), so in practice every sample carries one: measured across
     * this fleet's bus, 15 053 samples over 42 subjects and four realms, 100% timestamped, including
     * subjects with no storage behind them.
     *
     * **Two earlier diagnoses were wrong, and both are worth remembering.** The first said the crash was
     * `Zenoh.scout`-only and that subscriptions marshal primitives — that came from a comment in
     * `KeelsonSession` naming an `io.zenoh.jni.callbacks` package which does not exist in 1.10.0. The
     * second said the trigger was queries, and when that was removed the app still died; the third said
     * the router timestamps "what its storages keep", which its own measurement had already disproved —
     * `checklist_presence` arrived timestamped with no storage configured for it.
     *
     * It is a native `abort()`, not an exception: nothing catches it, and a `runCatching` around a
     * subscriber declaration protects only the declaration, never the delivery.
     *
     * **Upstream:** eclipse-zenoh/zenoh-flat-jni#49. Filed against the shared JNI layer rather than the
     * Kotlin binding because that is where the fault is; it affects zenoh-java identically. **1.10.1 does
     * not fix it** — tried on a Pixel 6 on 2026-09-19 with this flag set true and a subscriber on a busy
     * entity: `ClassNotFoundException: io.zenoh.jni.time.Timestamp` and SIGABRT 164 ms after the first
     * sample, identical to 1.10.0. It predates #49 being filed, so that was expected; check the issue
     * rather than the version number before trying again.
     *
     * **Flipping this to true is the whole of re-enabling** what it gates — the checklist feature and the
     * platform *documents* — once #49 lands and the binding is bumped. Publishing, queryables and
     * liveliness *declaration* are unaffected and stay on: this app remains present on the bus and keeps
     * saying what it is doing. It simply cannot listen.
     */
    const val SUBSCRIPTIONS_SAFE = true
}

/**
 * What a screen says in place of a platform's geometry while [ZenohBinding.SUBSCRIPTIONS_SAFE] is false.
 *
 * A row reading "alive, no geometry published" is a statement **about the other station**, and with the
 * gate on it is very likely false: the platform may be publishing its `configuration_json` perfectly
 * well and this phone simply cannot listen. Getting that the wrong way round sends somebody to fix a
 * connector that is working.
 *
 * It lives here rather than in the platform package because the *reason* is the binding, not the
 * platform — the same argument that put the mechanism in one object instead of three comments.
 */
const val PLATFORM_DOCUMENTS_UNAVAILABLE_REASON = "geometry not readable on this build"

