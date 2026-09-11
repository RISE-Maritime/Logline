package se.rise.logline

import java.util.Locale

/**
 * A file name stem that survives whatever a foreign identifier turns out to contain.
 *
 * Anything outside `[A-Za-z0-9._-]` is percent-encoded — reversible, collision-free, and it cannot
 * produce `.`, `..` or a path separator. Uppercase hex through `Locale.ROOT`, because `%X` on a
 * Turkish phone is its own small adventure.
 *
 * This lives in the root package because two unrelated stores need the *same* implementation, and a
 * second transcription of it is exactly the kind of thing that drifts: a platform's entity id is free
 * text somebody typed, and a checklist evidence id is a token that could one day arrive off the bus.
 * Both become file names under `filesDir`, which is where the mTLS client key lives — so an id
 * containing `../tls/client_key.pem` must be unable to escape its own directory whichever door it
 * came through.
 */
fun safeFileStem(id: String): String = buildString {
    id.forEach { c ->
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '_') {
            append(c)
        } else {
            append(String.format(Locale.ROOT, "%%%02X", c.code))
        }
    }
}
