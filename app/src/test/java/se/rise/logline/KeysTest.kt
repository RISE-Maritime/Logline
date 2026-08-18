package se.rise.logline

import se.rise.logline.keelson.Subjects
import se.rise.logline.keelson.livelinessKey
import se.rise.logline.keelson.pubsubKey
import org.junit.Assert.assertEquals
import org.junit.Test

class KeysTest {

    @Test
    fun `pubsub key follows the v0 layout`() {
        assertEquals(
            "rise/@v0/pixel_6/pubsub/location_fix/phone",
            pubsubKey("rise", "pixel_6", Subjects.LOCATION_FIX, "phone"),
        )
    }

    /**
     * The `*` is literal — protocol specification §5.1. Getting this wrong is invisible on the device
     * and only shows up as a consumer that never discovers the source.
     */
    @Test
    fun `liveliness key has a literal wildcard in the subject position`() {
        assertEquals(
            "rise/@v0/pixel_6/pubsub/*/phone",
            livelinessKey("rise", "pixel_6", "phone"),
        )
    }

    @Test
    fun `both keys agree on realm entity and source placement`() {
        val pubsub = pubsubKey("rise", "pixel_6", Subjects.ANGULAR_VELOCITY_RADPS, "phone").split("/")
        val liveliness = livelinessKey("rise", "pixel_6", "phone").split("/")

        // realm, @v0, entity, "pubsub" — identical; only the subject chunk differs.
        assertEquals(pubsub.take(4), liveliness.take(4))
        assertEquals(pubsub.last(), liveliness.last())
        assertEquals("*", liveliness[4])
    }

    /** The specification's own example source id contains a slash; it must survive unescaped. */
    @Test
    fun `a source id containing a slash is preserved`() {
        assertEquals(
            "keelson/@v0/landkrabban/pubsub/*/gnss/0",
            livelinessKey("keelson", "landkrabban", "gnss/0"),
        )
    }

    @Test
    fun `the version chunk is verbatim so wildcards cannot cross it`() {
        // A subscriber on "rise/**" matches nothing, because @-prefixed chunks are verbatim in Zenoh.
        // Encoding that here so a "simplification" of the key format has to argue with a test.
        assertEquals("@v0", pubsubKey("rise", "pixel_6", Subjects.LOCATION_FIX, "phone").split("/")[1])
        assertEquals("@v0", livelinessKey("rise", "pixel_6", "phone").split("/")[1])
    }
}
