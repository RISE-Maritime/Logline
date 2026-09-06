package se.rise.logline

import se.rise.logline.checklist.ChecklistKeys
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The keys, pinned against the literals in crowsnest's `src/services/checklistSync.js`.
 *
 * There is exactly one other implementation of this protocol, and it builds its keys by string
 * concatenation from `CHECKLIST_BASE` in its `services/keelsonRealm.js`, currently
 * `"rise/@v0/roc1/pubsub"`. A drift here does not fail:
 * the phone publishes to a key nobody subscribes to and subscribes to a key nobody publishes on, and
 * both sides look like they are working. That is what this test exists to catch.
 */
class ChecklistKeysTest {

    private val keys = ChecklistKeys("rise", "roc1")

    @Test
    fun `event key matches crowsnest eventKeyExpr`() {
        assertEquals("rise/@v0/roc1/pubsub/checklist_event/ROC-A", keys.event("ROC-A"))
    }

    @Test
    fun `event subscription matches crowsnest eventSubscribeKeyExpr`() {
        assertEquals("rise/@v0/roc1/pubsub/checklist_event/*", keys.eventSubscription())
    }

    @Test
    fun `state key is addressed by procedure, not by site`() {
        assertEquals("rise/@v0/roc1/pubsub/checklist_state/proc_001", keys.state("proc_001"))
    }

    /**
     * Two tokens in the source position. Legal — the source id is the remainder of the key — and it is
     * what crowsnest publishes, so it is not ours to normalise.
     */
    @Test
    fun `presence key carries site and operator as separate tokens`() {
        assertEquals(
            "rise/@v0/roc1/pubsub/checklist_presence/ROC-A/op-7",
            keys.presence("ROC-A", "op-7"),
        )
    }

    /**
     * A single `*` matches one token, and the presence key has two after the subject — so `*` here
     * would match nothing at all, silently. This is the same class of mistake as expecting a wildcard
     * to cross `@v0`.
     */
    @Test
    fun `presence subscription uses a double wildcard`() {
        assertEquals(
            "rise/@v0/roc1/pubsub/checklist_presence/**",
            keys.presenceSubscription(),
        )
        val presence = keys.presence("ROC-A", "op-7")
        val prefix = keys.presenceSubscription().removeSuffix("**")
        // The subscription's prefix really is a prefix of what gets published — the part a `*` would
        // get right — and what remains is more than one token, which is the part it would not.
        assert(presence.startsWith(prefix))
        assertEquals(2, presence.removePrefix(prefix).split("/").size)
    }

    @Test
    fun `procedure keys address one definition and the whole library`() {
        assertEquals(
            "rise/@v0/roc1/pubsub/checklist_procedure/proc_002",
            keys.procedure("proc_002"),
        )
        assertEquals("rise/@v0/roc1/pubsub/checklist_procedure/*", keys.procedureQuery())
    }

    @Test
    fun `state query covers every procedure the storage holds`() {
        assertEquals("rise/@v0/roc1/pubsub/checklist_state/*", keys.stateQuery())
    }

    /**
     * The tree is configurable, and the app's own realm is `rise` — so a build that quietly used the
     * logger's realm would publish somewhere crowsnest is not listening.
     */
    @Test
    fun `realm and entity are the checklist's, not the logger's`() {
        val elsewhere = ChecklistKeys("rise", "pixel_6")
        assertEquals("rise/@v0/pixel_6/pubsub/checklist_event/site", elsewhere.event("site"))
    }
}
