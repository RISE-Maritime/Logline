package se.rise.logline

import se.rise.logline.keelson.Subjects
import se.rise.logline.keelson.entityIdFromKey
import se.rise.logline.keelson.legacyLivelinessKey
import se.rise.logline.keelson.sourceLivelinessKey
import se.rise.logline.keelson.pubsubKey
import se.rise.logline.keelson.pubsubSubjectAndSource
import se.rise.logline.keelson.rpcInterfaceLivelinessKey
import se.rise.logline.keelson.rpcKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
     * The `*` is literal, and **which chunk it occupies is the whole difference** between the two
     * tiers — specification §5.1 against §5.7. A source token wildcards the *category* slot, where
     * `pubsub` or `@rpc` would be; the legacy coarse token wildcards the *subject* slot one along.
     *
     * §5.5 classifies a received token by exactly that position, so a source key built one chunk out
     * arrives looking like the legacy shape. Nothing on the phone would show it: the failure is a
     * health monitor reading the phone as advertising nothing.
     */
    @Test
    fun `the source token wildcards the category slot and the legacy one the subject slot`() {
        assertEquals("rise/@v0/pixel_6/*/phone", sourceLivelinessKey("rise", "pixel_6", "phone"))
        assertEquals("rise/@v0/pixel_6/pubsub/*/phone", legacyLivelinessKey("rise", "pixel_6", "phone"))

        assertEquals("*", sourceLivelinessKey("rise", "pixel_6", "phone").split("/")[3])
        assertEquals("pubsub", legacyLivelinessKey("rise", "pixel_6", "phone").split("/")[3])
    }

    @Test
    fun `all three shapes agree on realm entity and source placement`() {
        val pubsub = pubsubKey("rise", "pixel_6", Subjects.ANGULAR_VELOCITY_RADPS, "phone").split("/")
        val source = sourceLivelinessKey("rise", "pixel_6", "phone").split("/")
        val legacy = legacyLivelinessKey("rise", "pixel_6", "phone").split("/")

        // realm, @v0, entity — identical across all three; the category chunk is where they part.
        assertEquals(pubsub.take(3), source.take(3))
        assertEquals(pubsub.take(4), legacy.take(4))
        assertEquals(pubsub.last(), source.last())
        assertEquals(pubsub.last(), legacy.last())
        assertEquals("*", legacy[4])
    }

    /**
     * The subject tier has no key function of its own **on purpose**: the token key *is* the publisher
     * key, so `SensorPublisher` declares tokens from the very map it declared publishers from. This
     * pins the equivalence the design rests on — if these two ever needed to differ, that would be a
     * protocol change and this test is where it surfaces.
     */
    @Test
    fun `a subject token key is the publisher key itself`() {
        assertEquals(
            "rise/@v0/pixel_6/pubsub/location_fix/phone",
            pubsubKey("rise", "pixel_6", Subjects.LOCATION_FIX, "phone"),
        )
    }

    /** The specification's own example source id contains a slash; it must survive unescaped. */
    @Test
    fun `a source id containing a slash is preserved`() {
        assertEquals(
            "keelson/@v0/landkrabban/*/gnss/0",
            sourceLivelinessKey("keelson", "landkrabban", "gnss/0"),
        )
        assertEquals(
            "keelson/@v0/landkrabban/pubsub/*/gnss/0",
            legacyLivelinessKey("keelson", "landkrabban", "gnss/0"),
        )
    }

    @Test
    fun `the version chunk is verbatim so wildcards cannot cross it`() {
        // A subscriber on "rise/**" matches nothing, because @-prefixed chunks are verbatim in Zenoh.
        // Encoding that here so a "simplification" of the key format has to argue with a test.
        assertEquals("@v0", pubsubKey("rise", "pixel_6", Subjects.LOCATION_FIX, "phone").split("/")[1])
        assertEquals("@v0", sourceLivelinessKey("rise", "pixel_6", "phone").split("/")[1])
        assertEquals("@v0", legacyLivelinessKey("rise", "pixel_6", "phone").split("/")[1])
    }

    /**
     * The RPC layout, §3.1 — two more chunks than the pre-interface shape this app also served until
     * crowsnest moved off it.
     */
    @Test
    fun `rpc key follows the interface-and-version layout`() {
        assertEquals(
            "rise/@v0/ssrs18/@rpc/configurable/v1/get_config/calibration",
            rpcKey("rise", "ssrs18", "configurable", "v1", "get_config", "calibration"),
        )
    }

    @Test
    fun `the entity id comes out of the third chunk`() {
        assertEquals("ssrs18", entityIdFromKey("rise/@v0/ssrs18/pubsub/frame_transform/calibration"))
        assertEquals("ssrs18", entityIdFromKey(rpcKey("rise", "ssrs18", "configurable", "v1", "get_config", "x")))
    }

    /**
     * A key without a verbatim `@v0` is not a keelson key, and a wildcard is not an entity.
     *
     * Both matter to discovery: reading the chunk positionally without the check would report the
     * subject of a malformed key as an entity id, and would offer `*` as a platform to adopt.
     */
    @Test
    fun `a key that is not keelson-shaped yields no entity`() {
        assertNull(entityIdFromKey("rise/pixel_6/pubsub/location_fix/phone"))
        assertNull(entityIdFromKey("rise/@v1/pixel_6/pubsub/location_fix/phone"))
        assertNull(entityIdFromKey("rise/@v0/*/pubsub/location_fix/phone"))
        assertNull(entityIdFromKey(""))
    }

    /**
     * The third wildcard position, and the one most easily put a chunk out.
     *
     * `sourceLivelinessKey` wildcards the category slot, `legacyLivelinessKey` the subject slot, and
     * this one the **procedure** slot — three different claims that all look like "a liveliness key
     * with a star in it". §5.5 classifies a received token by chunk position, so a key built one along
     * is not an error, it is a different and wrong statement.
     */
    @Test
    fun `the rpc interface token wildcards the procedure slot`() {
        val key = rpcInterfaceLivelinessKey("rise", "platform_a", "configurable", "v1", "survey")

        assertEquals("rise/@v0/platform_a/@rpc/configurable/v1/*/survey", key)
        val chunks = key.split("/")
        assertEquals("@rpc", chunks[3])
        assertEquals("*", chunks[6])
        // One chunk further along than the legacy token's star — the thing worth pinning.
        assertEquals("*", legacyLivelinessKey("rise", "platform_a", "survey").split("/")[4])
    }

    /**
     * Both `@`-prefixed chunks are verbatim, so no wildcard reaches this token — a discovery client
     * has to spell `@rpc` out. A subscriber on the realm's own recursive wildcard receives nothing
     * here, which is the specification's own warning and the likeliest reason somebody concludes the
     * phone serves no RPC.
     */
    @Test
    fun `the rpc token sits behind two verbatim chunks`() {
        val chunks = rpcInterfaceLivelinessKey("rise", "platform_a", "configurable", "v1", "gnss/0").split("/")

        assertEquals("@v0", chunks[1])
        assertEquals("@rpc", chunks[3])
        // A multi-chunk source id survives unescaped, as everywhere else.
        assertEquals(
            "rise/@v0/platform_a/@rpc/configurable/v1/*/gnss/0",
            rpcInterfaceLivelinessKey("rise", "platform_a", "configurable", "v1", "gnss/0"),
        )
    }

    /**
     * **A source may carry further levels, so the source is not "the last chunk".**
     *
     * The specification allows it — "`source_id` may contain any number of additional levels (i.e.
     * forward slashes), ei. camera/rbg/0" — and this app publishes `location_fix/{source}/gnss`. Two
     * places read a topic by counting from the end, and both answered `gnss` for the *subject* the
     * moment a source was nested: the Files tab stopped recognising its own fix channel, and the
     * recording detail screen printed the wrong pair. Anchoring on the verbatim `pubsub` chunk is the
     * only position that cannot move.
     */
    @Test
    fun `a nested source is one source, not a subject and a chunk`() {
        assertEquals(
            "location_fix" to "phone",
            pubsubSubjectAndSource("rise/@v0/pixel_6/pubsub/location_fix/phone"),
        )
        assertEquals(
            "location_fix" to "phone/gnss",
            pubsubSubjectAndSource("rise/@v0/pixel_6/pubsub/location_fix/phone/gnss"),
        )
        // The specification's own example, three levels deep.
        assertEquals(
            "image_compressed" to "camera/rbg/0",
            pubsubSubjectAndSource("rise/@v0/pixel_6/pubsub/image_compressed/camera/rbg/0"),
        )
    }

    /** Anything that is not a pubsub key answers null rather than a guess. */
    @Test
    fun `a key that is not pubsub is not parsed`() {
        // An RPC key: the category chunk is `@rpc`, so this is not a subject and a source.
        assertNull(pubsubSubjectAndSource("rise/@v0/ssrs18/@rpc/configurable/v1/get_config/calibration"))
        // A liveliness token, whose category slot is a wildcard.
        assertNull(pubsubSubjectAndSource("rise/@v0/pixel_6/*/phone"))
        assertNull(pubsubSubjectAndSource("not a key"))
        // No source at all.
        assertNull(pubsubSubjectAndSource("rise/@v0/pixel_6/pubsub/location_fix"))
    }
}
