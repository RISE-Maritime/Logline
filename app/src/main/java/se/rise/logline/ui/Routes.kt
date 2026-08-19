package se.rise.logline.ui

import se.rise.logline.config.Settings

/**
 * Every NavHost route, and the two prefixes the Zenoh sessions are scoped by.
 *
 * These were bare string literals at fourteen `composable(...)` declarations and as many
 * `navigate(...)` calls, which was survivable — until two of them became *load-bearing beyond
 * navigation*. `ChecklistSync` and `PlatformSync` are started and stopped by prefix-matching the
 * current route (protocol specification §3.5 forbids holding an interface's liveliness token while
 * not serving it, so the platform session genuinely may not outlive its screens). A route renamed
 * without touching the matching prefix therefore gives a screen that opens perfectly, draws
 * perfectly, and never opens a session — no crash, no empty-state, no log line, just a scan that
 * finds nothing on the bus for the rest of the process's life.
 *
 * Collecting them here does not by itself prevent that. What prevents it is that [ALL] makes the
 * route set enumerable, so `NavigationRoutesTest` can assert which routes each prefix actually
 * captures — a rename then fails a test instead of failing in the field.
 *
 * **Kept free of Android types on purpose.** These are patterns, not paths: building a path for a
 * parameterised route needs `Uri.encode`, whose `android.jar` stub throws "not mocked" in a JVM test,
 * and that would take the whole file out of unit-test reach for the sake of three string templates.
 * Path building stays at the call site in `MainActivity`.
 */
object Routes {

    // -- top level: the five tabs, and the only routes that carry the navigation bar --------------

    const val MAIN = "main"
    const val LIVE = "live"
    const val ANNOTATIONS = "annotations"
    const val RECORDINGS = "recordings"
    const val SETUP = "setup"

    // -- pushed on top of a tab, with a back arrow ------------------------------------------------

    const val ANNOTATION_BUTTONS = "annotations/edit"
    const val SETTINGS = "settings"
    const val SCAN_QR = "scan-qr"

    /** Argument is the [se.rise.logline.keelson.PublishedSubject] *enum name*, not the subject. */
    const val SUBJECT_QOS = "qos/{entry}"

    const val CHECKLISTS = "checklists"
    const val CHECKLIST = "checklist/{procedureId}"

    const val RIGS = "calibration"
    const val RIG = "calibration/rig/{entityId}"
    const val SENSOR_MOUNT = "calibration/rig/{entityId}/sensor/{index}"

    /**
     * Every route the graph declares.
     *
     * Order is the order they are declared in `MainActivity`, which makes the two lists diffable by
     * eye. A route missing from here is not a compile error — it is only a gap in what the test can
     * see — so add both together.
     */
    val ALL = listOf(
        MAIN, SETUP, RECORDINGS, LIVE, SUBJECT_QOS, ANNOTATIONS, ANNOTATION_BUTTONS,
        CHECKLISTS, CHECKLIST, RIGS, RIG, SENSOR_MOUNT, SCAN_QR, SETTINGS,
    )

    /**
     * Scopes the checklist session. Covers the list *and* one procedure.
     *
     * Keyed on the prefix rather than either route because stepping between them must not cycle the
     * session — opening a procedure from the list would otherwise close the session and open a new
     * one, losing the presence state that had just been gathered.
     */
    const val CHECKLIST_PREFIX = "checklist"

    /** Scopes the platform session across the rig list, the editor and a sensor, for the same reason. */
    const val RIG_PREFIX = "calibration"

    // -- paths, built from the patterns above ----------------------------------------------------
    //
    // The argument is substituted rather than the path being spelled again, so a renamed pattern takes
    // its navigate() calls with it. Callers pass an already-encoded id where one is needed: `Uri.encode`
    // is an Android call and this file stays clear of those — see the note at the top.

    fun subjectQos(entryName: String): String = SUBJECT_QOS.replace("{entry}", entryName)

    fun rig(encodedEntityId: String): String = RIG.replace("{entityId}", encodedEntityId)

    fun sensorMount(encodedEntityId: String, index: Int): String =
        SENSOR_MOUNT.replace("{entityId}", encodedEntityId).replace("{index}", index.toString())

    fun inChecklists(route: String?): Boolean = route?.startsWith(CHECKLIST_PREFIX) == true

    fun inRigScreens(route: String?): Boolean = route?.startsWith(RIG_PREFIX) == true

    // -- the whole decision, not just the routing half -------------------------------------------
    //
    // The two sessions are not symmetrical and it matters. The platform session opens on the route
    // alone; the checklist session additionally needs the feature switched on and an operator to
    // attribute events to, because joining a shared checklist anonymously would put ticks on the
    // board that no station can resolve to a person.
    //
    // Both live here as pure functions so the condition is testable, rather than only existing inline
    // in a `LaunchedEffect` where the only way to exercise it is to open the screen on a phone with
    // the right settings — which is precisely the check that never gets done.

    /**
     * Whether the checklist session should be open.
     *
     * [Settings.hasChecklistIdentity] is the second gate, and it is why watching this screen on a
     * phone with checklists switched off proves nothing either way about the routing.
     */
    fun shouldSyncChecklists(route: String?, settings: Settings): Boolean =
        inChecklists(route) && settings.checklistEnabled && settings.hasChecklistIdentity()

    /** Whether the platform session should be open. The route is the whole of it. */
    fun shouldSyncPlatforms(route: String?): Boolean = inRigScreens(route)
}
