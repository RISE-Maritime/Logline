package se.rise.logline

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.config.Keys
import se.rise.logline.config.Settings
import se.rise.logline.config.readSettings
import se.rise.logline.config.writeSettings
import se.rise.logline.monitor.CardKind
import se.rise.logline.monitor.DEFAULT_MONITOR_CARDS
import se.rise.logline.monitor.MonitorCard
import se.rise.logline.monitor.Topic
import se.rise.logline.monitor.isCircularSubject
import se.rise.logline.monitor.resolveTopic
import se.rise.logline.monitor.slotFromSourceId
import se.rise.logline.monitor.unitsForSubject

class MonitorCardsTest {

    private fun base() = Settings(
        realm = "rise",
        entityId = "pixel_6",
        routerEndpoints = listOf("tcp/127.0.0.1:7447"),
        locationSource = "phone",
        imuSource = "phone",
    )

    @Test
    fun `absent cards give the default set and case is watched`() {
        val settings = readSettings(mutablePreferencesOf(), defaultEntityId = "pixel_6")

        assertEquals(DEFAULT_MONITOR_CARDS, settings.monitorCards)
        assertEquals("case", settings.monitorEntity)
        assertEquals("rise", settings.monitorRealmOrDefault())
    }

    @Test
    fun `an emptied card list stays empty`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, base().copy(monitorCards = emptyList()))

        assertTrue(readSettings(prefs, defaultEntityId = "pixel_6").monitorCards.isEmpty())
    }

    @Test
    fun `cards survive a write and read, order and settings intact`() {
        val cards = listOf(
            MonitorCard("a", CardKind.Rudder, mapOf("slot" to "1", "maxAngle" to "35")),
            MonitorCard("b", CardKind.Plot, mapOf("subject" to "roll_deg", "source" to "imu/0")),
        )
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, base().copy(monitorEntity = "vessel", monitorUrl = "http://h:8000", monitorCards = cards))

        val out = readSettings(prefs, defaultEntityId = "pixel_6")
        assertEquals(cards, out.monitorCards)
        assertEquals("vessel", out.monitorEntity)
        assertEquals("http://h:8000", out.monitorUrl)
    }

    /** A shrinking list must remove the indices it no longer uses, or a deleted card comes back. */
    @Test
    fun `removing a card removes its key`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, base().copy(monitorCards = DEFAULT_MONITOR_CARDS))
        writeSettings(prefs, base().copy(monitorCards = DEFAULT_MONITOR_CARDS.take(1)))

        assertNull(prefs[Keys.monitorCard(1)])
        assertEquals(1, readSettings(prefs, defaultEntityId = "pixel_6").monitorCards.size)
    }

    @Test
    fun `a card of an unknown kind is dropped, the rest survive`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, base().copy(monitorCards = DEFAULT_MONITOR_CARDS.take(2)))
        prefs[Keys.monitorCard(0)] = """{"id":"x","kind":"FromTheFuture","params":{}}"""

        assertEquals(listOf(DEFAULT_MONITOR_CARDS[1]), readSettings(prefs, defaultEntityId = "pixel_6").monitorCards)
    }

    @Test
    fun `settings fall back to the kind's defaults and are clamped to its range`() {
        val card = MonitorCard("r", CardKind.Rudder, mapOf("maxAngle" to "500", "staleAfterS" to "junk"))

        assertEquals(70.0, card.number("maxAngle")!!, 0.0)
        assertEquals(5.0, card.number("staleAfterS")!!, 0.0)
        assertEquals("", card.string("slot"))
        assertNull("a blank-allowed number stays blank", MonitorCard("p", CardKind.Plot).number("yMin"))
        assertEquals("an optional series is off until chosen", "", MonitorCard("p", CardKind.Plot).string("subject2"))
        assertFalse(MonitorCard("p", CardKind.Plot).with("windowS", "").params.containsKey("windowS"))
    }

    private val topics = listOf(
        Topic("engine_rate_rpm", "engine/starboard"),
        Topic("engine_rate_rpm", "engine/port"),
        Topic("heading_true_north_deg", "gnss"),
        Topic("heading_true_north_deg", "compass"),
    )

    @Test
    fun `a pin wins, then the slot, then the first source in order`() {
        assertEquals(Topic("heading_true_north_deg", "gnss"), resolveTopic(topics, "heading_true_north_deg", "gnss"))
        assertEquals(Topic("engine_rate_rpm", "engine/port"), resolveTopic(topics, "engine_rate_rpm", slot = 0))
        assertEquals(Topic("engine_rate_rpm", "engine/starboard"), resolveTopic(topics, "engine_rate_rpm", slot = 1))
        assertEquals(Topic("heading_true_north_deg", "compass"), resolveTopic(topics, "heading_true_north_deg"))
        assertNull(resolveTopic(topics, "rudder_angle_deg"))
    }

    /** Reading another source while the pinned one is quiet would put the wrong sensor under its name. */
    @Test
    fun `a pinned source that is not on the bus is still the answer`() {
        assertEquals(Topic("heading_true_north_deg", "gyro"), resolveTopic(topics, "heading_true_north_deg", "gyro"))
    }

    @Test
    fun `slots come from the last chunk of the source`() {
        assertEquals(2, slotFromSourceId("engine/2"))
        assertEquals(0, slotFromSourceId("rudder/babord"))
        assertEquals(1, slotFromSourceId("rudder/STBD/"))
        assertNull(slotFromSourceId("gnss"))
        assertNull(slotFromSourceId("-1"))
    }

    @Test
    fun `units are read from the subject suffix, longest first`() {
        assertEquals("m/s²", unitsForSubject("linear_acceleration_mpss"))
        assertEquals("m/s", unitsForSubject("sway_velocity_mps"))
        assertEquals("dBm", unitsForSubject("radio_rsrp_dbm"))
        assertEquals("N·m", unitsForSubject("shaft_torque_newton_meter"))
        assertEquals("", unitsForSubject("vehicle_mode"))
    }

    @Test
    fun `only bearings are circular, not every angle`() {
        assertTrue(isCircularSubject("heading_true_north_deg"))
        assertTrue(isCircularSubject("course_over_ground_deg"))
        assertFalse(isCircularSubject("roll_deg"))
        assertFalse(isCircularSubject("rudder_angle_deg"))
    }
}
