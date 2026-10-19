package io.github.manamiproject.modb.app.merging

import io.github.manamiproject.modb.core.anime.AnimeRaw

/**
 * Some metadata providers publish an entry's alternative titles as one comma-joined string, so a title containing a
 * comma reaches a merged entry whole from other sources and in pieces from these. When every comma-separated piece of
 * a title is listed as well, those pieces are the split rather than titles of their own, so they are dropped and the
 * whole title stays. Titles are compared regardless of case, apostrophe style and closing dots, because the whole
 * title and its pieces often come from different providers.
 *
 * A comma string can also be several titles joined together, like `Make a Girl, Un Amor Artificial`. Its pieces are
 * then real titles and stay: that is assumed when a piece is (nearly) the main title or every piece opens with the
 * same two words.
 * @since 1.0.0
 */
internal object CommaSplitSynonyms {

    /**
     * Removes the synonyms which are the pieces of another title of the same entry.
     * @since 1.0.0
     * @param anime Merged entry.
     * @return Same instance.
     */
    fun dropPieces(anime: AnimeRaw): AnimeRaw {
        val titles = anime.synonyms + anime.title
        val listed = titles.map { normalize(it) }.toSet()
        val mainTitle = normalize(anime.title)

        val pieces = titles.asSequence()
            .filter { it.contains(SEPARATOR) }
            .map { title -> title.split(SEPARATOR).map { normalize(it) }.filter { it.isNotEmpty() } }
            .filter { it.size > 1 && it.all { piece -> piece in listed } }
            .filterNot { isJoinedList(it, mainTitle) }
            .flatten()
            .toSet()

        return when {
            pieces.isEmpty() -> anime
            else -> anime.removeSynonymIf { normalize(it) in pieces }
        }
    }

    private fun isJoinedList(pieces: List<String>, mainTitle: String): Boolean {
        if (pieces.any { distance(it, mainTitle) <= MAX_DISTANCE_TO_MAIN_TITLE }) return true
        val openings = pieces.map { it.split(' ').filter(String::isNotEmpty).take(SHARED_OPENING_WORDS) }.toSet()
        return openings.size == 1 && openings.first().size == SHARED_OPENING_WORDS
    }

    private fun normalize(title: String): String = title
        .replace('’', '\'')
        .replace('‘', '\'')
        .trim()
        .trimEnd('.', '…')
        .trim()
        .lowercase()

    private fun distance(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > MAX_DISTANCE_TO_MAIN_TITLE) return MAX_DISTANCE_TO_MAIN_TITLE + 1
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }

    private const val SEPARATOR = ','
    private const val MAX_DISTANCE_TO_MAIN_TITLE = 2
    private const val SHARED_OPENING_WORDS = 2
}
