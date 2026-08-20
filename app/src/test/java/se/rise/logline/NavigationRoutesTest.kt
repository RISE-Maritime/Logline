package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.config.Settings
import se.rise.logline.ui.Routes
import se.rise.logline.ui.components.TopLevel

/**
 * What the two Zenoh sessions are actually scoped to.
 *
 * `ChecklistSync` and `PlatformSync` are started and stopped by prefix-matching the current route
 * rather than by naming a destination, because the list and the detail of each are separate
 * destinations and tying a session to either would cycle it every time somebody stepped between them.
 *
 * That works, and it fails **silently** when it stops working. A route renamed without touching its
 * prefix leaves a screen that opens, draws and scans perfectly while never opening a session at all —
 * no crash, no error state, no log line, just an empty result that is indistinguishable from an empty
 * bus. It survived moving Rigs and Checklists under the Setup tab; nothing about the code said it
 * would, and the only check was opening the screen on a phone and watching for the native library to
 * load.
 *
 * So these assert the *membership* of each prefix, not merely that it matches something. Equality is
 * the point: a route added that unintentionally begins with `calibration` is caught by the same
 * assertion as a route renamed out from under it, and both force a decision rather than a surprise.
 */
class NavigationRoutesTest {

    private fun settings(enabled: Boolean, operatorId: String, operatorName: String) = Settings(
        realm = "rise",
        entityId = "phone",
        routerEndpoints = listOf("tls/router.example.com:443"),
        locationSource = "phone",
        imuSource = "phone",
        checklistEnabled = enabled,
        operatorId = operatorId,
        operatorName = operatorName,
    )

    @Test
    fun `the checklist session is scoped to exactly the checklist screens`() {
        assertEquals(
            setOf(Routes.CHECKLISTS, Routes.CHECKLIST),
            Routes.ALL.filter { Routes.inChecklists(it) }.toSet(),
        )
    }

    @Test
    fun `the platform session is scoped to exactly the rig screens`() {
        assertEquals(
            setOf(Routes.RIGS, Routes.RIG, Routes.SENSOR_MOUNT),
            Routes.ALL.filter { Routes.inRigScreens(it) }.toSet(),
        )
    }

    /**
     * The failure this whole file exists for, stated directly: a prefix matching nothing is a session
     * that never opens.
     */
    @Test
    fun `neither prefix matches nothing`() {
        assertTrue(
            "no route starts with the checklist prefix — that session can never open",
            Routes.ALL.any { Routes.inChecklists(it) },
        )
        assertTrue(
            "no route starts with the rig prefix — that session can never open",
            Routes.ALL.any { Routes.inRigScreens(it) },
        )
    }

    /**
     * The Setup tab must not be caught by either prefix.
     *
     * It is where Rigs and Checklists are now reached from, so it sits one tap from both — and if it
     * ever began with `calibration` or `checklist` it would hold both sessions open for as long as
     * somebody left the app on that tab, which is the opposite of what §3.5 asks for.
     */
    @Test
    fun `the Setup tab holds no session open`() {
        assertTrue(!Routes.inChecklists(Routes.SETUP) && !Routes.inRigScreens(Routes.SETUP))
    }

    /** A duplicate would mean two `composable` declarations racing for one route. */
    @Test
    fun `no route is declared twice`() {
        assertEquals(Routes.ALL.size, Routes.ALL.toSet().size)
    }

    /**
     * Every tab must be a route the graph actually declares.
     *
     * `TopLevel` reads its routes from `Routes`, so this cannot drift by a typo — what it catches is a
     * route deleted from [Routes.ALL] while a tab still points at it, which would leave the bar with an
     * item that never lights up and navigates nowhere.
     */
    @Test
    fun `every tab is a declared route`() {
        TopLevel.entries.forEach { tab ->
            assertTrue("${tab.name} is not a declared route", tab.route in Routes.ALL)
        }
    }

    /**
     * Back out of any tab and you reach Run, then the app exits.
     *
     * `goToTab` pops up to the graph's start destination, so this is what makes system back behave the
     * way the platform expects rather than unwinding a history of tab visits.
     */
    @Test
    fun `Run is the start destination`() {
        assertEquals(Routes.MAIN, TopLevel.Session.route)
    }

    /**
     * The checklist session needs the route **and** an operator, and the two are not the same check.
     *
     * This is the half that had never run anywhere but a JVM: the dev phone has checklists switched
     * off, so opening the screen there exercises nothing — the session correctly declines to open, and
     * a broken *prefix* would look exactly the same. Which is why the whole condition lives in
     * `Routes.shouldSyncChecklists` rather than inline in a `LaunchedEffect`, where the only way to
     * reach it is to be holding the right phone.
     *
     * Anonymity is the reason for the second gate: a tick on a shared checklist that no station can
     * resolve to a person is worse than no tick.
     */
    @Test
    fun `the checklist session needs a route, the feature, and an identity`() {
        val ready = settings(enabled = true, operatorId = "op-1", operatorName = "Ted")

        assertTrue(Routes.shouldSyncChecklists(Routes.CHECKLISTS, ready))
        assertTrue(Routes.shouldSyncChecklists(Routes.CHECKLIST, ready))

        // Right settings, wrong screen.
        assertTrue(!Routes.shouldSyncChecklists(Routes.MAIN, ready))
        assertTrue(!Routes.shouldSyncChecklists(Routes.SETUP, ready))
        assertTrue(!Routes.shouldSyncChecklists(null, ready))

        // Right screen, and each half of the identity gate missing in turn.
        assertTrue(
            "switched off",
            !Routes.shouldSyncChecklists(Routes.CHECKLISTS, ready.copy(checklistEnabled = false)),
        )
        assertTrue(
            "no operator id",
            !Routes.shouldSyncChecklists(Routes.CHECKLISTS, ready.copy(operatorId = "")),
        )
        assertTrue(
            "no operator name",
            !Routes.shouldSyncChecklists(Routes.CHECKLISTS, ready.copy(operatorName = "")),
        )
    }

    /**
     * The platform session opens on the route alone — deliberately, and asserted so the asymmetry is
     * recorded rather than looking like an oversight next to the checklist gate above.
     */
    @Test
    fun `the platform session needs only the route`() {
        val bare = settings(enabled = false, operatorId = "", operatorName = "")
        assertTrue(Routes.shouldSyncPlatforms(Routes.RIGS))
        assertTrue(Routes.shouldSyncPlatforms(Routes.SENSOR_MOUNT))
        assertTrue(!Routes.shouldSyncPlatforms(Routes.SETUP))
        assertTrue(!Routes.shouldSyncPlatforms(null))
        // Nothing about the operator reaches this decision.
        assertTrue(Routes.shouldSyncPlatforms(Routes.RIG) && !bare.hasChecklistIdentity())
    }

    /** The built paths substitute the argument rather than respelling the pattern. */
    @Test
    fun `paths are built from their patterns`() {
        assertEquals("qos/LOCATION_FIX", Routes.subjectQos("LOCATION_FIX"))
        assertEquals("calibration/rig/ssrs18", Routes.rig("ssrs18"))
        assertEquals("calibration/rig/ssrs18/sensor/2", Routes.sensorMount("ssrs18", 2))
        // And a built path is still scoped to the session its pattern belongs to.
        assertTrue(Routes.inRigScreens(Routes.rig("ssrs18")))
        assertTrue(Routes.inRigScreens(Routes.sensorMount("ssrs18", 0)))
        // The detail route must miss both session prefixes, or opening a plot would tear down a
        // checklist or platform session — silently, since a broken prefix match still opens the screen.
        assertEquals("plot/AIR_PRESSURE", Routes.subjectDetail("AIR_PRESSURE"))
        assertFalse(Routes.inChecklists(Routes.subjectDetail("AIR_PRESSURE")))
        assertFalse(Routes.inRigScreens(Routes.subjectDetail("AIR_PRESSURE")))
    }
}
