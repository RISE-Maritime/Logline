package se.rise.logline

import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.record.subjectSchemaNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `McapSchemas.kt` says this map is "pinned by a test". It was not — this is that test.
 *
 * A subject missing from the map still records, but under the empty self-describing schema, so the file
 * opens and its messages cannot be decoded without knowing the type from somewhere else. That is the
 * silent kind of wrong the registry exists to prevent, and it is invisible until someone tries to read
 * a recording back weeks later.
 */
class McapSchemasTest {

    @Test
    fun `every published subject has a schema`() {
        val missing = PublishedSubject.entries.map { it.subject }.distinct()
            .filterNot { subjectSchemaNames.containsKey(it) }

        assertTrue("no schema type for $missing — a recording of it would not decode", missing.isEmpty())
    }

    @Test
    fun `the map names no subject the app does not publish`() {
        val published = PublishedSubject.entries.map { it.subject }.toSet()

        assertEquals(emptySet<String>(), subjectSchemaNames.keys - published)
    }

    /** Every type named here has to be one protoc actually put in the descriptor set. */
    @Test
    fun `schema types are keelson or foxglove payload types`() {
        subjectSchemaNames.forEach { (subject, type) ->
            assertTrue(
                "$subject names \"$type\", which is not a vendored payload package",
                type.startsWith("keelson.") || type.startsWith("foxglove."),
            )
        }
    }
}
