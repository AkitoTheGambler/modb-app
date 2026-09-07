package io.github.manamiproject.modb.app.synonyms

import io.github.manamiproject.modb.core.anime.AnimeRaw

/**
 * Removes synonyms which name a related anime rather than the entry carrying them.
 * @since 1.0.0
 */
interface AmbiguousSynonymRemover {

    /**
     * A sequel often inherits the plain name of the franchise as a synonym, because a metadata provider states the
     * season in a way which is dropped when the title is parsed. The synonym then points at the first season just as
     * well as at the sequel, which makes a search for the first season return both.
     * @since 1.0.0
     * @param allEntries All entries of the dataset after merging.
     * @return The same list. Entries are modified in place.
     */
    fun removeSynonymsWhichNameARelatedAnime(allEntries: List<AnimeRaw>): List<AnimeRaw>
}
