package se.rise.logline.record

/**
 * What a tag is, on the way into a recording and back out of it.
 *
 * The words themselves live in `Settings` — they are a configuration, like the annotation buttons, and
 * persist between runs — and the set that was switched on when a file closed is written into that file
 * as an MCAP Metadata record. These are the rules both ends agree on.
 */
/**
 * A tag as it will be stored, or null if there is nothing left of it.
 *
 * Whitespace is collapsed and the separator is stripped: a tag carrying a newline would split into two
 * on the way back out, which is a quiet way to invent a tag nobody typed.
 */
fun normaliseTag(raw: String): String? = raw
    .replace(SEPARATOR, ' ')
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(MAX_TAG_LENGTH)
    .trim()
    .ifBlank { null }

/** Tags out of their stored form, in the order they were written. */
fun parseTags(stored: String): Set<String> =
    stored.split(SEPARATOR).mapNotNull(::normaliseTag).toSet()

/** Tags into their stored form. */
fun encodeTags(tags: Set<String>): String =
    tags.mapNotNull(::normaliseTag).joinToString(SEPARATOR.toString())

/**
 * A newline, which `normaliseTag` guarantees no tag contains.
 *
 * The same choice the endpoint list makes for the same reason — a delimiter that cannot appear in a
 * value needs no escaping, and escaping is where round trips go wrong.
 */
private const val SEPARATOR = '\n'

/** Long enough for a phrase, short enough that a row stays a row. */
private const val MAX_TAG_LENGTH = 40
