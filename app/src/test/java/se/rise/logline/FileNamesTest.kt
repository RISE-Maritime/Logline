package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * [safeFileStem] is a security primitive rather than a formatting one, so what it is pinned on is the
 * property rather than the spelling: a stem can never escape the directory it is joined to.
 */
class FileNamesTest {

    @Test
    fun `ordinary identifiers pass through untouched`() {
        assertEquals("sealog", safeFileStem("sealog"))
        assertEquals("ev-01.a_B", safeFileStem("ev-01.a_B"))
    }

    @Test
    fun `no stem can leave its own directory`() {
        val hostile = listOf(
            "../tls/client_key",
            "../../tls/client_key",
            "/etc/passwd",
            "a/b",
            "..",
            ".",
        )
        val parent = File("/data/files/checklist-evidence")
        hostile.forEach { id ->
            val resolved = File(parent, safeFileStem(id) + ".jpg").canonicalPath
            assertEquals(
                "$id escaped its directory",
                parent.canonicalPath,
                File(resolved).parentFile!!.canonicalPath,
            )
        }
    }

    @Test
    fun `separators and traversal are encoded rather than dropped`() {
        assertFalse(safeFileStem("../a").contains('/'))
        assertEquals("..%2Fa", safeFileStem("../a"))
        // Reversible, so two different ids cannot collide on one file.
        assertFalse(safeFileStem("a/b") == safeFileStem("a%2Fb"))
    }
}
